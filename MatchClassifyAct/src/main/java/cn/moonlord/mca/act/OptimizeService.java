package cn.moonlord.mca.act;

import cn.moonlord.mca.config.StoragePaths;
import cn.moonlord.mca.mark.CaptureMark;
import cn.moonlord.mca.mark.ClassifyStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 算法调优：以「已标注样本对自家分类产物的命中率」为目标，自动搜索匹配算法可调参数。
 *
 * <p>当前实现聚焦五族权重（A 全图交集 50 / B 多数 15 / C 均值 10 / D 去重均值 10 / E 方框交集区 15，
 * 见 {@link FrameClassifier} 默认值）。调优思路与验证视图同源：
 * 把 classify/ 全部已标注原图当作“画面”，与全部分类的同尺寸产物（含 -unique 与注意区/点击区交集图，
 * 缺产物/空图按不匹配满值 100 计、与验证一致）逐 kind 逐点比对一次，先聚合出
 * 「样本 × 分类 × 五族」的平均不匹配占比矩阵；此后每次评估一组权重只做纯数值加权
 * （与识别端 aggregateDiff 同公式：差异度 = Σ w_i·族均值 / Σ 参与族权重），目标 = 命中自家分类的
 * 样本占比最大（并列时取安全边际更大者）。搜索 = 从默认/均分/随机种子出发做逐坐标爬山 + 细扫，
 * 权重可含 0（= 整族不参与）。</p>
 *
 * <p>运行结果存内存不落盘；点击「应用」才写入 {@code FrameClassifier} 运行时权重并持久化
 * （{@link StoragePaths#weights()}），重启后自动加载。调优期间与验证/执行模式识别勿并行
 * （清空常驻缓存后识别每帧需重解码）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizeService {

    /** 族展示名（下标与 {@link ArtifactKind} 的 Family 枚举 / familyKinds(i) 顺序一一对应，仅供界面展示；
     *  长度须等于 ArtifactKind.familyCount()）。 */
    public static final List<String> FAMILY_LABELS = List.of(
            "A 全图交集图", "B 多数族", "C 均值族", "D 去重均值族", "E 方框交集图");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StoragePaths storage;
    private final ClassifyStore classifyStore;
    private final FrameClassifier classifier;
    private final ObjectMapper json = new ObjectMapper();

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "optimize");
        t.setDaemon(true);
        return t;
    });

    /** summary/ 下的一个有效分类目录（产物目录 = 分类标注）：attnLeft/attnTop = 注意点
     *  （注意区图框心，未设 = 屏幕中心），actLeft/actTop = 鼠标点击点（点击区图框心，仅 click 分类有）。 */
    private record Ctx(int idx, String state, String dir, int w, int h,
                       String action, Integer attnLeft, Integer attnTop,
                       Integer actLeft, Integer actTop) {
    }

    /** classify/ 下的一张已标注原图（归属某分类目录）。 */
    private record Smp(int idx, Path png, Ctx own) {
    }

    /** 一组权重跑出的指标。 */
    public static final class Metrics {
        public final int samples;
        public final int hit;
        public final double acc1;          // 命中自家分类占比 %
        public final double avgSelf;       // 自家分类平均差异度
        public final double nearestOther;  // 其它分类最近者平均差异度
        public final double margin;        // nearestOther - avgSelf（越大越稳）

        Metrics(int samples, int hit, double acc1, double avgSelf, double nearestOther, double margin) {
            this.samples = samples;
            this.hit = hit;
            this.acc1 = acc1;
            this.avgSelf = avgSelf;
            this.nearestOther = nearestOther;
            this.margin = margin;
        }
    }

    /** 单次调优任务进度。 */
    public static final class Run {
        public volatile boolean running = true;
        public volatile boolean finished;
        public volatile String error;
        public volatile String stage = "准备数据";
        public volatile int done;          // stage=matrix 时已完成 kind 数
        public final int total;            // matrix 总 kind 数
        public volatile String cur;
        public volatile int processed;     // 评估过的候选权重组数
        public volatile double bestAcc;    // 迄今最优命中率
        public volatile String bestDesc;
        public final long startedMs;
        public volatile long endedMs;

        Run(int total) {
            this.total = total;
            this.startedMs = System.currentTimeMillis();
        }
    }

    /** 最近一次调优结果（完成 / 进行中逐步更新）。 */
    public static final class Result {
        public volatile boolean finished;
        public volatile int[] bestWeights;
        public volatile Metrics bestMetrics;    // 找到的最优
        public volatile Metrics baseMetrics;    // 默认权重对照
        public volatile int step;
        public volatile int samples;
        public volatile int groups;
        public volatile long costMs;
        public volatile String error;
    }

    private final Result result = new Result();
    private volatile Run run;
    private volatile List<Map<String, Object>> effCache;   // 生效范围表短时缓存（状态 1s 轮询，避免反复读全部 info.json）
    private volatile long effCacheMs;

    // ---------------------------------------------------------------- 对外

    @PostConstruct
    void loadWeights() {
        Path f = storage.weights();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Map<String, Object> m = json.readValue(text, new TypeReference<Map<String, Object>>() {
            });
            Object w = m.get("weights");
            if (w instanceof List<?> list && list.size() == FAMILY_LABELS.size()) {
                int[] arr = new int[list.size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = Integer.parseInt(String.valueOf(list.get(i)));
                }
                classifier.setFamilyWeights(arr);
                log.info("已加载算法调优权重 {}（文件 {}）", List.of(toBoxed(arr)).toString(), f);
            }
        } catch (Exception e) {
            log.warn("加载调优权重文件失败（使用默认权重）：{}", e.toString());
        }
    }

    public boolean running() {
        Run r = run;
        return r != null && r.running && !r.finished;
    }

    public synchronized boolean start(int step, int seeds) {
        if (running()) {
            return false;
        }
        final int st = step < 1 || step > 100 ? 10 : step;
        final int sd = seeds < 4 || seeds > 128 ? 24 : seeds;
        int[] current = classifier.familyWeights();
        Run r = new Run(classifier.verifyOrder().size());
        run = r;
        exec.submit(() -> doRun(r, current, st, sd));
        return true;
    }

    /** 应用一组权重到运行时并持久化（重启后仍生效）。 */
    public synchronized Map<String, Object> apply(int[] weights) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            classifier.setFamilyWeights(weights);
            Path f = storage.weights();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("weights", toBoxed(weights));
            m.put("appliedAt", LocalDateTime.now().format(TS));
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, json.writeValueAsString(m), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            out.put("ok", true);
            out.put("weights", toBoxed(weights));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    /** 恢复默认权重（运行时 + 删除持久化文件）。 */
    public synchronized Map<String, Object> reset() {
        Map<String, Object> out = new LinkedHashMap<>();
        classifier.resetFamilyWeights();
        try {
            Files.deleteIfExists(storage.weights());
        } catch (IOException e) {
            log.debug("删除调优权重文件失败：{}", e.toString());
        }
        out.put("ok", true);
        out.put("weights", toBoxed(classifier.familyWeights()));
        return out;
    }

    /** 状态总览（任务进度 + 当前权重 + 最近结果），供前端轮询。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", running());
        out.put("current", toBoxed(classifier.familyWeights()));
        out.put("defW", toBoxed(classifier.defaultWeights()));
        out.put("samples", classifyStore.listClassifiedPngs().size());
        out.put("families", FAMILY_LABELS);
        Run r = run;
        if (r != null) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("finished", r.finished);
            task.put("stage", r.stage);
            task.put("done", r.done);
            task.put("total", r.total);
            task.put("cur", r.cur);
            task.put("processed", r.processed);
            task.put("bestAcc", r.bestAcc);
            task.put("bestDesc", r.bestDesc);
            task.put("error", r.error);
            task.put("costMs", r.finished && r.endedMs > 0 ? Math.max(0, r.endedMs - r.startedMs) : 0);
            out.put("task", task);
        }
        out.put("result", resultMap());
        // 特征生效范围表（D2）：每 kind 在多少分类有效、平均覆盖多少（读 info.json 的 eff；短时缓存）
        out.put("eff", effTableCached());
        return out;
    }

    private Map<String, Object> resultMap() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("finished", result.finished);
        res.put("step", result.step);
        res.put("samples", result.samples);
        res.put("groups", result.groups);
        res.put("costMs", result.costMs);
        res.put("error", result.error);
        res.put("best", metricsMap(result.bestMetrics));
        res.put("base", metricsMap(result.baseMetrics));
        res.put("bestWeights", result.bestWeights == null ? null : toBoxed(result.bestWeights));
        res.put("appliedWeights", toBoxed(classifier.familyWeights()));
        return res;
    }

    private static Map<String, Object> metricsMap(Metrics m) {
        if (m == null) {
            return null;
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("samples", m.samples);
        o.put("hit", m.hit);
        o.put("acc1", Math.round(m.acc1 * 10000) / 100.0);
        o.put("avgSelf", Math.round(m.avgSelf * 10000) / 100.0);
        o.put("nearestOther", Math.round(m.nearestOther * 10000) / 100.0);
        o.put("margin", Math.round(m.margin * 10000) / 100.0);
        return o;
    }

    private static List<Integer> toBoxed(int[] a) {
        List<Integer> out = new ArrayList<>(a.length);
        for (int v : a) {
            out.add(v);
        }
        return out;
    }

    // ---------------------------------------------------------------- 计算

    private void doRun(Run r, int[] baseWeights, int step, int seeds) {
        classifier.clearPxCaches();   // 同验证：逐 kind 解码产物，先清常驻像素缓存腾堆
        List<Ctx> groups = new ArrayList<>();
        List<Smp> samples = new ArrayList<>();
        try {
            groups = scanGroups();
            samples = scanSamples(groups);
        } catch (Exception e) {
            fail(r, "准备数据失败：" + e);
            return;
        }
        int S = samples.size();
        int G = groups.size();
        if (S == 0 || G == 0) {
            fail(r, "没有可用的已标注样本/分类产物（请先在标注页完成分析），无法调优");
            return;
        }
        int F = FAMILY_LABELS.size();
        // 样本 × 分类 × 五族 的平均不匹配占比（-1 = 整族无可比 kind）
        double[][] famMean = new double[S][G * F];
        boolean[][] famPresent = new boolean[S][G];
        try {
            r.stage = "逐 kind 比对矩阵";
            computeMatrix(r, groups, samples, famMean, famPresent);
        } catch (Throwable e) {
            log.warn("调优矩阵计算失败: {}", e.toString());
            fail(r, "矩阵计算失败：" + e);
            return;
        }
        r.stage = "权重寻优";

        int[] selfJ = new int[S];
        for (int i = 0; i < S; i++) {
            selfJ[i] = samples.get(i).own().idx();
        }

        // 基准（默认权重）指标
        r.stage = "评估基准权重";
        int[] defW = classifier.defaultWeights();
        Metrics baseM = evaluate(defW, famMean, famPresent, selfJ);

        // 种子 + 逐坐标爬山
        int[] best = null;
        Metrics bestM = null;
        List<int[]> seedsList = new ArrayList<>();
        seedsList.add(baseWeights.clone());
        seedsList.add(new int[]{20, 20, 20, 20, 20});
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < seeds; i++) {
            int[] s = new int[F];
            for (int j = 0; j < F; j++) {
                s[j] = snap(rnd.nextInt(0, 121), step);
            }
            seedsList.add(s);
        }
        int pass = 0;
        for (int[] seed : seedsList) {
            int[] w = seed.clone();
            boolean improved = true;
            int guard = 4;   // 每种子最多轮数
            while (improved && guard-- > 0) {
                improved = false;
                for (int f = 0; f < F; f++) {
                    int curBestV = w[f];
                    Metrics curM = evaluate(w, famMean, famPresent, selfJ);
                    Metrics bestForF = curM;
                    int bestV = curBestV;
                    for (int v = 0; v <= 120; v += step) {
                        if (v == curBestV) {
                            continue;
                        }
                        int[] c = w.clone();
                        c[f] = v;
                        Metrics m = evaluate(c, famMean, famPresent, selfJ);
                        if (better(m, bestForF)) {
                            bestForF = m;
                            bestV = v;
                        }
                    }
                    if (bestV != curBestV) {
                        w[f] = bestV;
                        improved = true;
                    }
                }
                r.processed++;
            }
            Metrics m = evaluate(w, famMean, famPresent, selfJ);
            if (best == null || better(m, bestM)) {
                best = w.clone();
                bestM = m;
                publishBest(r, best, m);
            }
        }
        // 最优结果附近细扫（步长 1），弥补粗步长的局部遗漏
        if (best != null && step > 1) {
            boolean improved = true;
            int guard = 3;
            while (improved && guard-- > 0) {
                improved = false;
                for (int f = 0; f < F; f++) {
                    int curV = best[f];
                    Metrics curM = evaluate(best, famMean, famPresent, selfJ);
                    int bestV = curV;
                    Metrics bestForF = curM;
                    for (int v = Math.max(0, curV - step); v <= Math.min(120, curV + step); v++) {
                        if (v == curV) {
                            continue;
                        }
                        int[] c = best.clone();
                        c[f] = v;
                        Metrics m = evaluate(c, famMean, famPresent, selfJ);
                        if (better(m, bestForF)) {
                            bestForF = m;
                            bestV = v;
                        }
                    }
                    if (bestV != curV) {
                        best[f] = bestV;
                        improved = true;
                    }
                }
                r.processed++;
            }
            Metrics m = evaluate(best, famMean, famPresent, selfJ);
            if (better(m, bestM)) {
                bestM = m;
            }
        }
        r.stage = "完成";
        publishBest(r, best, bestM);
        int[] finalW = best == null ? defW.clone() : best.clone();
        synchronized (result) {
            result.finished = true;
            result.bestWeights = finalW;
            result.bestMetrics = bestM;
            result.baseMetrics = baseM;
            result.step = step;
            result.samples = S;
            result.groups = G;
            result.costMs = System.currentTimeMillis() - r.startedMs;
            result.error = r.error;
        }
        r.finished = true;
        r.endedMs = System.currentTimeMillis();
        r.running = false;
        log.info("算法调优完成：基准命中 {}/{} ({}%)，最优 {}/{} ({}%)，权重 {}",
                baseM.hit, S, String.format("%.2f", baseM.acc1),
                bestM.hit, S, String.format("%.2f", bestM.acc1), toBoxed(finalW));
    }

    private static void publishBest(Run r, int[] w, Metrics m) {
        if (m == null) {
            return;
        }
        r.bestAcc = m.acc1;
        r.bestDesc = w == null ? "{null}" : toBoxed(w).toString();
    }

    private static boolean better(Metrics a, Metrics b) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        if (a.hit != b.hit) {
            return a.hit > b.hit;
        }
        return a.margin > b.margin;
    }

    private static int snap(int v, int step) {
        return Math.max(0, Math.min(120, v / step * step));
    }

    private void fail(Run r, String err) {
        r.error = err;
        synchronized (result) {
            result.error = err;
            result.finished = true;
        }
        r.finished = true;
        r.endedMs = System.currentTimeMillis();
        r.running = false;
        log.warn("算法调优失败: {}", err);
    }

    /** 逐 kind 解码产物并把每（样本×分类）得分聚合进五族均值矩阵。 */
    private void computeMatrix(Run r, List<Ctx> groups, List<Smp> samples,
                               double[][] famMean, boolean[][] famPresent) {
        int S = samples.size();
        int G = groups.size();
        int F = FAMILY_LABELS.size();
        double[][] famSum = new double[S][G * F];
        int[][] famCnt = new int[S][G * F];
        Map<String, FrameClassifier.FrameWork> works = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> order = classifier.verifyOrder();
        for (int i = 0; i < order.size(); i++) {
            String kind = order.get(i);
            r.cur = kind;
            r.done = i;
            int f = classifier.familyIndexOf(kind);
            if (f < 0) {
                continue;
            }
            String file = classifier.verifyFile(kind);
            // 解码全部分类的该 kind 产物（一次用后释放）
            List<FrameClassifier.CachedPx> arts = new ArrayList<>(G);
            for (Ctx c : groups) {
                Path p = Path.of(storage.summary().toString(), c.dir(), file);
                arts.add(Files.isRegularFile(p) ? classifier.verifyArtifact(p, kind) : null);
            }
            IntStream.range(0, S).parallel().forEach(si -> {
                Smp smp = samples.get(si);
                String wkey = smp.png().toString();
                FrameClassifier.FrameWork work = works.get(wkey);
                if (work == null) {
                    FrameClassifier.CachedPx raw = classifier.verifySample(smp.png(), smp.own().w(), smp.own().h());
                    if (raw == null) {
                        return;   // 尺寸与自家分类产物不一致 → 无法参与比对
                    }
                    FrameClassifier.FrameWork built = new FrameClassifier.FrameWork(raw.px, raw.w, raw.h);
                    FrameClassifier.FrameWork prev = works.putIfAbsent(wkey, built);
                    work = prev == null ? built : prev;
                }
                for (int g = 0; g < G; g++) {
                    FrameClassifier.CachedPx art = arts.get(g);
                    double sc;
                    if (art == null) {
                        sc = 100.0;   // 该分类没有本 kind 产物 = 无判别点，同验证按满值参与
                    } else {
                        Ctx c = groups.get(g);
                        // 点击区图按鼠标点击点裁框、注意区图按注意点裁框
                        sc = classifier.verifyKindScore(work, art, kind, new FrameClassifier.Centers(
                                c.actLeft() == null ? -1 : c.actLeft(),
                                c.actTop() == null ? -1 : c.actTop(),
                                c.attnLeft() == null ? 0 : c.attnLeft(),
                                c.attnTop() == null ? 0 : c.attnTop()));
                    }
                    if (sc >= 0) {
                        famSum[si][g * F + f] += sc;
                        famCnt[si][g * F + f]++;
                    }
                }
            });
            arts.clear();
        }
        // 归一化：cnt==0 的族视为不可比（-1），否则取平均
        for (int i = 0; i < S; i++) {
            for (int g = 0; g < G; g++) {
                boolean any = false;
                for (int f = 0; f < F; f++) {
                    int idx = g * F + f;
                    if (famCnt[i][idx] > 0) {
                        famMean[i][idx] = famSum[i][idx] / famCnt[i][idx];
                        any = true;
                    } else {
                        famMean[i][idx] = -1;
                    }
                }
                famPresent[i][g] = any;
            }
        }
        works.clear();
    }

    /** 评估一组权重的命中率等指标（样本 i 的分类 = 对该样本五族加权差异度最小的那个目录；与识别端同口径）。 */
    private Metrics evaluate(int[] w, double[][] famMean, boolean[][] famPresent, int[] selfJ) {
        int S = selfJ.length;
        int G = famPresent[0].length;
        int F = FAMILY_LABELS.size();
        int hit = 0;
        double selfSum = 0;
        double nearestSum = 0;
        for (int i = 0; i < S; i++) {
            int own = selfJ[i];
            double selfDiff = Double.POSITIVE_INFINITY;
            int bestG = -1;
            double bestD = Double.POSITIVE_INFINITY;
            for (int g = 0; g < G; g++) {
                if (!famPresent[i][g]) {
                    continue;   // 该分类没有任何可比 kind（极端异常）
                }
                double sum = 0;
                int wsum = 0;
                for (int f = 0; f < F; f++) {
                    double mean = famMean[i][g * F + f];
                    if (mean >= 0) {
                        sum += w[f] * mean;
                        wsum += w[f];
                    }
                }
                double d = wsum == 0 ? Double.POSITIVE_INFINITY : sum / wsum;
                if (g == own) {
                    selfDiff = d;
                }
                if (d < bestD - 1e-9 || (Math.abs(d - bestD) <= 1e-9 && (bestG == -1 || g < bestG))) {
                    bestD = d;
                    bestG = g;
                }
            }
            if (bestG == own) {
                hit++;
            }
            if (selfDiff < Double.POSITIVE_INFINITY) {
                selfSum += selfDiff;
            }
            // 最近异类：除自家外最小
            double nearest = Double.POSITIVE_INFINITY;
            for (int g = 0; g < G; g++) {
                if (g == own || !famPresent[i][g]) {
                    continue;
                }
                double sum = 0;
                int wsum = 0;
                for (int f = 0; f < F; f++) {
                    double mean = famMean[i][g * F + f];
                    if (mean >= 0) {
                        sum += w[f] * mean;
                        wsum += w[f];
                    }
                }
                double d = wsum == 0 ? Double.POSITIVE_INFINITY : sum / wsum;
                if (d < nearest) {
                    nearest = d;
                }
            }
            if (nearest < Double.POSITIVE_INFINITY) {
                nearestSum += nearest;
            }
        }
        double avgSelf = S == 0 ? 0 : selfSum / S;
        double nearestOther = S == 0 ? 0 : nearestSum / S;
        return new Metrics(S, hit, 100.0 * hit / S, avgSelf, nearestOther, nearestOther - avgSelf);
    }

    // ---------------------------------------------------------------- 数据准备

    /** 枚举 summary/ 有效分类目录（state 与尺寸有效），按目录名排序。 */
    private List<Ctx> scanGroups() {
        List<Ctx> out = new ArrayList<>();
        Path sum = storage.summary();
        if (!Files.isDirectory(sum)) {
            return out;
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(sum)) {
            dirs = s.filter(Files::isDirectory)
                    .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return out;
        }
        int idx = 0;
        for (Path dir : dirs) {
            Map<String, Object> info = classifier.verifyInfo(dir);
            Object sv = info.get("state");
            String state = sv == null ? "" : String.valueOf(sv).trim();
            Integer w = intOf(info.get("width"));
            Integer h = intOf(info.get("height"));
            if (state.isEmpty() || w == null || h == null || w <= 0 || h <= 0) {
                continue;
            }
            Object av = info.get("action");
            String action = av == null ? CaptureMark.ACTION_NONE : String.valueOf(av);
            Integer cl = pickInt(info, "attnLeft", "clickLeft");
            Integer ct = pickInt(info, "attnTop", "clickTop");
            if (cl == null || ct == null) {   // 注意点未设 = 屏幕中心（与产物/识别端同口径）
                cl = w / 2;
                ct = h / 2;
            }
            out.add(new Ctx(idx++, state, dir.getFileName().toString(), w, h, action, cl, ct,
                    intOf(info.get("actLeft")), intOf(info.get("actTop"))));
        }
        return out;
    }

    /** 枚举 classify/ 原图并归属其 state 对应的分类目录（无对应目录的原图不参与）。 */
    private List<Smp> scanSamples(List<Ctx> groups) {
        Map<String, Ctx> byState = new LinkedHashMap<>();
        for (Ctx c : groups) {
            byState.putIfAbsent(c.state(), c);
        }
        List<Smp> out = new ArrayList<>();
        int idx = 0;
        for (Path png : classifyStore.listClassifiedPngs()) {
            CaptureMark m = classifyStore.sampleOf(png);
            if (m == null) {
                continue;
            }
            String st = m.getState() == null ? "" : m.getState().trim();
            Ctx own = st.isEmpty() ? null : byState.get(st);
            if (own != null) {
                out.add(new Smp(idx++, png, own));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 生效范围表（D2）

    /** 生效范围表短时缓存（状态 1s 轮询，避免反复读全部 info.json）。 */
    private List<Map<String, Object>> effTableCached() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> c = effCache;
        if (c != null && now - effCacheMs < 5000) {
            return c;
        }
        List<Map<String, Object>> out = effTable();
        effCache = out;
        effCacheMs = now;
        return out;
    }

    /** 每 kind 的生效范围统计：在多少分类有该产物、多少分类有有效像素、平均覆盖（每分类 info.json 只读一次）。 */
    private List<Map<String, Object>> effTable() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Ctx> groups;
        try {
            groups = scanGroups();
        } catch (Exception e) {
            return out;
        }
        List<Map<String, Object>> infos = new ArrayList<>(groups.size());
        for (Ctx c : groups) {
            try {
                infos.add(classifier.verifyInfo(Path.of(storage.summary().toString(), c.dir())));
            } catch (Exception e) {
                infos.add(null);
            }
        }
        for (String kind : classifier.verifyOrder()) {
            int total = 0;
            int withData = 0;
            double sumEff = 0;
            for (Map<String, Object> info : infos) {
                if (info == null) {
                    continue;
                }
                total++;
                Object eo = info.get("eff");
                if (eo instanceof Map<?, ?> em) {
                    Object v = em.get(kind);
                    if (v instanceof Number num) {
                        double d = num.doubleValue();
                        sumEff += d;
                        if (d > 0) {
                            withData++;
                        }
                    }
                }
            }
            if (total == 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind);
            row.put("file", classifier.verifyFile(kind));
            row.put("family", classifier.familyIndexOf(kind));
            row.put("total", total);
            row.put("withData", withData);
            row.put("avgEff", Math.round(sumEff / total * 100) / 100.0);
            out.add(row);
        }
        return out;
    }

    private static Integer intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    /** 依次取 info 里的两个键（前者优先）：attnLeft/attnTop 为裁剪中心权威键，
     *  clickLeft/clickTop 为历史键（旧产物只有它，旧数据里它就是注意点）。 */
    private static Integer pickInt(Map<String, Object> info, String primary, String legacy) {
        Integer v = intOf(info.get(primary));
        return v != null ? v : intOf(info.get(legacy));
    }
}
