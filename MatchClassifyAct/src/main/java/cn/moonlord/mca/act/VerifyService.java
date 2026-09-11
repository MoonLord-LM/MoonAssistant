package cn.moonlord.mca.act;

import cn.moonlord.mca.config.StoragePaths;
import cn.moonlord.mca.mark.CaptureMark;
import cn.moonlord.mca.mark.ClassifyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 特征验证：对每一种「汇总图算法」（kind 产物，与识别参与比对的全套 kind 一致）做自回归评分。
 *
 * <p>按某一种算法生成了每个分类的汇总图后，把 classify/ 的全部已标注原图逐张与【全部分类】的
 * 同 kind 汇总图按识别同口径逐点比对（复用 {@link FrameClassifier} 的产物/原图缓存与比对桥接），
 * 统计三个评价指标：
 * <ul>
 * <li>A：每个原图与「自己分类的该算法生成图」比对的匹配像素占比（= 100 − 不匹配占比），
 * 取全部原图的平均值（越高越好，表明样本比较集中；无法生成有效图的样本按 0 计入）；</li>
 * <li>B：生成成功率 = 能生成有效合成图（产物存在且非全透明）的分类占参与分类的比例
 * （越高说明该算法适用的分类越多）；</li>
 * <li>C：匹配正确率 = 只在「能生成有效图的分类」的样本里，与【全部分类】同 kind 汇总图比对、
 * 匹配度最高的刚好是自己分类的占比（越高说明算法区分度越好）；无法生成有效图的分类不进这个
 * 分母，其明细行的 A/C 显示为「——」而不是 0%。</li>
 * </ul>
 * 没有该 kind 产物的分类（如旧目录尚未重算补齐点击区图等）＝无判别点：与「0 像素空图」同口径，判
 * 完全不匹配、对外匹配占比按 0 计入并照常参与 A（其样本自分类 A 恒为 0），但不计入 C 的分母与命中，
 * 也不因缺产物而跳过这些分类及其原图。
 * 按算法逐 kind 独立计算、完成后按「当前样本/产物指纹」缓存结果并标记是否过期（样本或产物有
 * 改动后需重新验证）。独立后台任务线程跑，结果只存内存不落盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyService {

    private final StoragePaths storage;
    private final ClassifyStore classifyStore;
    private final FrameClassifier classifier;

    /** 单线程后台执行器（任务内部的逐样本比对用并行流加速）。 */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "verify");
        t.setDaemon(true);
        return t;
    });

    /** 每 kind 的验证结果（key = kind，与验证维度的全部 kind 一致；过期旧值保留便于对照）。 */
    private final Map<String, KindStat> results = new ConcurrentHashMap<>();

    /** 正在跑 / 刚跑完的任务进度（volatile 快照式，每次轮询读取）。 */
    private volatile Run run;

    /** 指纹缓存（3 秒 TTL，避免每次轮询全量 stat 产物/样本）。 */
    private volatile long fpAt;
    private volatile String fpLast;

    /** 一个 kind 的完整验证结果。 */
    public static class KindStat {
        public final String kind;
        public final String fp;
        public final long doneMs;
        public final int costMs;
        /** A = 自分类平均匹配值（%，匹配占比均值；无有效产物的样本按 0 计入）。 */
        public final Double a;
        /** B = 生成成功率（%）：能生成有效合成图的分类数 / 参与分类数。 */
        public final Double b;
        /** C = 匹配正确率（%）：可匹配样本（归属分类有有效合成图）里最佳命中恰是自家分类的占比。 */
        public final Double c;
        /** 有效产物分类数（B 分子）与参与分类数（B 分母）。 */
        public final int genOk;
        public final int genTotal;
        /** 可匹配样本数（C 分母）。 */
        public final int cSamples;
        public final int samples;
        public final int groups;
        public final List<Map<String, Object>> rows;

        public KindStat(String kind, String fp, long doneMs, int costMs,
                        Double a, Double b, Double c, int genOk, int genTotal, int cSamples,
                        int samples, int groups, List<Map<String, Object>> rows) {
            this.kind = kind;
            this.fp = fp;
            this.doneMs = doneMs;
            this.costMs = costMs;
            this.a = a;
            this.b = b;
            this.c = c;
            this.genOk = genOk;
            this.genTotal = genTotal;
            this.cSamples = cSamples;
            this.samples = samples;
            this.groups = groups;
            this.rows = rows;
        }
    }

    /** 进度快照。 */
    public static class Run {
        public volatile boolean running = true;
        public volatile boolean finished;
        public volatile String error;
        public volatile int done;
        public final int total;
        public volatile String cur;
        public volatile int processed;
        public volatile int totalSamples;
        public final long startedMs;
        public volatile long endedMs;   // 结束时刻（finished 置位时记录；0 = 尚未结束）

        Run(String fp, int total) {
            this.total = total;
            this.startedMs = System.currentTimeMillis();
        }
    }

    /** summary/ 下的一个有效分类目录（产物目录 = 分类标注）：attnLeft/attnTop = 注意点
     *  （注意区图框心，未设 = 屏幕中心），actLeft/actTop = 鼠标点击点（点击区图框心，仅 click 分类有）。 */
    private record Ctx(int idx, String state, String dir, int w, int h,
                       String action, Integer attnLeft, Integer attnTop,
                       Integer actLeft, Integer actTop) {
    }

    /** classify/ 下的一张已标注原图（归属某分类目录）。 */
    private record Smp(Path png, Ctx own) {
    }

    /** 某 kind 计算时的参与目录（产物已成功解码）。 */
    private static final class Cand {
        final Ctx ctx;
        final FrameClassifier.CachedPx art;
        /** 该分类用此特征是否生成了有效合成图：产物存在且至少有一个不透明像素（非全透明空图）。 */
        final boolean valid;

        Cand(Ctx ctx, FrameClassifier.CachedPx art) {
            this.ctx = ctx;
            this.art = art;
            this.valid = hasPixels(art);
        }
    }

    /** 产物是否有有效像素（存在任一点不透明，命中即返回）；解码失败 / 全透明空图 = 生成不出有效图。 */
    private static boolean hasPixels(FrameClassifier.CachedPx art) {
        if (art == null) {
            return false;
        }
        int[] px = art.px;
        for (int i = 0; i < px.length; i++) {
            if ((px[i] >>> 24) != 0) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 对外

    /** 是否正在计算（未完成的任务才算 running）。 */
    public boolean running() {
        Run r = run;
        return r != null && r.running && !r.finished;
    }

    /** 当前任务进度（无任务时 null）。 */
    public Run currentRun() {
        return run;
    }

    /** 启动一次完整验证（计算全部 kind）；已有任务在跑时拒绝并返回 false。 */
    public synchronized boolean start() {
        if (running()) {
            return false;
        }
        String fp = fpNow();
        List<String> order = classifier.verifyOrder();
        Run r = new Run(fp, order.size());
        run = r;
        exec.submit(() -> doRun(r, fp, order));
        return true;
    }

    private void doRun(Run r, String fp, List<String> order) {
        // 验证会逐 kind 解码全部参与分类的产物像素：先清空识别/汇总阶段累积的常驻像素缓存腾出堆空间
        // （验证走不写缓存的按需解码、每 kind 用后即释放；执行模式后续首次识别会自动重新解码补齐），
        // 否则 81 组 × 各 kind 全幅产物叠加会直接把堆撑爆
        classifier.clearPxCaches();
        List<Ctx> groups = new ArrayList<>();
        List<Smp> samples = new ArrayList<>();
        try {
            groups = scanGroups();
            samples = scanSamples(groups);
        } catch (Exception e) {
            r.error = "准备数据失败：" + e;
            r.finished = true;
            r.endedMs = System.currentTimeMillis();
            r.running = false;
            log.warn("特征验证准备失败: {}", e.toString());
            return;
        }
        // 每张样本的画面块压缩序列（FrameWork）整轮只建一次、全部 kind 复用（样本自身全幅像素与产物
        // 复用 FrameClassifier 缓存，这里只多放每样本一份小体积的 8/32 块序列）
        Map<String, FrameClassifier.FrameWork> works = new ConcurrentHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            String kind = order.get(i);
            r.cur = kind;
            r.done = i;
            try {
                KindStat st = computeKind(kind, fp, groups, samples, works);
                results.put(kind, st);
            } catch (Throwable e) {
                r.error = "验证 kind=" + kind + " 失败：" + e;
                log.warn("特征验证 kind={} 失败: {}", kind, e.toString());
                break;
            }
            r.done = i + 1;
        }
        r.finished = true;
        r.endedMs = System.currentTimeMillis();
        r.running = false;
    }

    /** 结果 / 总览（供前端轮询）：任务进度 + 全部 kind 的评分与状态。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        String fp = fp();
        out.put("running", running());
        out.put("samples", classifyStore.listClassifiedPngs().size());
        out.put("groups", scanGroups().size());
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (String kind : classifier.verifyOrder()) {
            KindStat st = results.get(kind);
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", kind);
            if (st == null) {
                k.put("state", "none");
                k.put("a", null);
                k.put("b", null);
                k.put("c", null);
                k.put("genOk", 0);
                k.put("genTotal", 0);
                k.put("samples", 0);
            } else {
                boolean fresh = fp.equals(st.fp);
                k.put("state", fresh ? "done" : "stale");
                k.put("a", st.a);
                k.put("b", st.b);          // B 生成成功率（%）
                k.put("c", st.c);          // C 匹配正确率（%）
                k.put("genOk", st.genOk);
                k.put("genTotal", st.genTotal);
                k.put("samples", st.samples);
            }
            kinds.add(k);
        }
        out.put("kinds", kinds);
        Run r = run;
        if (r != null) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("finished", r.finished);
            if (r.finished && r.endedMs > 0) {
                task.put("costMs", Math.max(0, r.endedMs - r.startedMs));
            }
            task.put("error", r.error);
            task.put("done", r.done);
            task.put("total", r.total);
            task.put("cur", r.cur);
            task.put("processed", r.processed);
            task.put("totalSamples", r.totalSamples);
            out.put("task", task);
        }
        return out;
    }

    /** 某 kind 的分类级明细（未计算返回 null；fresh 表示与当前样本/产物一致）。 */
    public Map<String, Object> detail(String kind) {
        if (kind == null || !classifier.verifyOrder().contains(kind)) {
            return null;
        }
        KindStat st = results.get(kind);
        if (st == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("fresh", fp().equals(st.fp));
        out.put("doneMs", st.doneMs);
        out.put("costMs", st.costMs);
        out.put("a", st.a);
        out.put("b", st.b);              // B 生成成功率（%）：genOk / genTotal
        out.put("c", st.c);              // C 匹配正确率（%）：可匹配样本里最佳命中恰是自家分类的占比
        out.put("genOk", st.genOk);
        out.put("genTotal", st.genTotal);
        out.put("cSamples", st.cSamples);
        out.put("samples", st.samples);
        out.put("groups", st.groups);
        out.put("rows", st.rows);
        return out;
    }

    // ---------------------------------------------------------------- 指纹

    /** 最新指纹（3 秒缓存）。 */
    private String fp() {
        long now = System.currentTimeMillis();
        String v = fpLast;
        if (v != null && now - fpAt < 3000) {
            return v;
        }
        return fpNow();
    }

    /** 样本 / 产物变动指纹：classify 原图与标注 json、summary 产物（png + info.json）的 名称+大小+修改时间。 */
    private synchronized String fpNow() {
        StringBuilder sb = new StringBuilder();
        appendTreeFiles(storage.classify(), sb);
        appendTreeFiles(storage.summary(), sb);
        String s = sb.toString();
        fpAt = System.currentTimeMillis();
        fpLast = Integer.toHexString(s.hashCode());
        return fpLast;
    }

    private void appendTreeFiles(Path root, StringBuilder sb) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> dirs = Files.walk(root)) {
            dirs.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            return;
        }
        files.sort((a, b) -> a.toString().compareTo(b.toString()));
        for (Path p : files) {
            try {
                BasicFileAttributes at = Files.readAttributes(p, BasicFileAttributes.class);
                sb.append(p.toString()).append('|').append(at.size()).append('|')
                  .append(at.lastModifiedTime().toMillis()).append('\n');
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 计算

    /** 枚举 summary/ 有效分类目录（有 state 且尺寸有效），按目录名排序。 */
    private List<Ctx> scanGroups() {
        List<Ctx> out = new ArrayList<>();
        Path sum = storage.summary();
        if (!Files.isDirectory(sum)) {
            return out;
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(sum)) {
            dirs = s.filter(Files::isDirectory).sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
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
            if (action == null || action.isEmpty()) {
                action = CaptureMark.ACTION_NONE;
            }
            Integer cl = pickInt(info, "attnLeft", "clickLeft");
            Integer ct = pickInt(info, "attnTop", "clickTop");
            if (cl == null || ct == null) {   // 注意点未设 = 屏幕中心（产物端同口径，验证端需一致才能裁对框）
                cl = w / 2;
                ct = h / 2;
            }
            out.add(new Ctx(idx++, state, dir.toString(), w, h, action, cl, ct,
                    intOf(info.get("actLeft")), intOf(info.get("actTop"))));
        }
        return out;
    }

    /** 枚举 classify/ 原图并归属到其 state 对应的分类目录（无对应目录的原图不参与验证）。 */
    private List<Smp> scanSamples(List<Ctx> groups) {
        Map<String, Ctx> byState = new LinkedHashMap<>();
        for (Ctx c : groups) {
            byState.putIfAbsent(c.state(), c);
        }
        List<Smp> out = new ArrayList<>();
        for (Path png : classifyStore.listClassifiedPngs()) {
            CaptureMark m = classifyStore.sampleOf(png);
            if (m == null) {
                continue;
            }
            String st = m.getState() == null ? "" : m.getState().trim();
            Ctx own = st.isEmpty() ? null : byState.get(st);
            if (own != null) {
                out.add(new Smp(png, own));
            }
        }
        return out;
    }

    /** 算一个 kind 的 A / B 与分类级明细。works = 本验证轮共享的每样本画面块压缩缓存（键 = 原图路径）。 */
    private KindStat computeKind(String kind, String fp, List<Ctx> groups, List<Smp> samples,
                                 Map<String, FrameClassifier.FrameWork> works) {
        long t0 = System.currentTimeMillis();
        String file = classifier.verifyFile(kind);

        // 参与目录 = 全部分类目录（每个分类都生成全套 42 张产物，含 12 张注意区图；
        // click 分类另有 12 张点击区图 = 54 张；缺该 kind 产物只发生在旧目录尚未重算补齐时）。
        // 没有本 kind 产物的分类＝无判别点，与「0 像素空图」同口径：判完全不匹配（内部按不匹配占比 100 算，
        // 对外 A 即匹配占比 0）、照常参与 A/B（自分类无法匹配、也永远不会被别的样本命中）；解码失败同视为无产物。
        List<Cand> cands = new ArrayList<>();
        int real = 0;
        for (Ctx c : groups) {
            Path f = Path.of(c.dir(), file);
            FrameClassifier.CachedPx art = Files.isRegularFile(f) ? classifier.verifyArtifact(f, kind) : null;
            if (art != null) {
                real++;
            }
            cands.add(new Cand(c, art));
        }
        if (real == 0) {
            return new KindStat(kind, fp, System.currentTimeMillis(), (int) (System.currentTimeMillis() - t0),
                    null, null, null, 0, 0, 0, 0, 0, List.of());
        }

        // 目录下标 → 参与目录下标（用于定位样本的“自分类”产物）
        Map<Integer, Integer> posOf = new LinkedHashMap<>();
        for (int j = 0; j < cands.size(); j++) {
            posOf.put(cands.get(j).ctx.idx(), j);
        }

        // 该 kind 的样本群：全部分类都有参与位（无产物者按不匹配占比满值 100 参与，对外 A = 匹配占比 0），故归属在扫描目录里的原图全数计入
        List<Smp> pop = samples.stream().filter(s -> posOf.containsKey(s.own().idx())).toList();
        int n = pop.size();
        double[] selfScore = new double[n];
        double[] bestScore = new double[n];
        for (int i = 0; i < n; i++) {
            selfScore[i] = -1;
            bestScore[i] = Double.POSITIVE_INFINITY;
        }
        double[] selfSum = new double[cands.size()];
        int[] cnt = new int[cands.size()];
        int[] hit = new int[cands.size()];
        int[] selfOf = new int[n];
        for (int i = 0; i < n; i++) {
            selfOf[i] = posOf.get(pop.get(i).own().idx());
        }

        Run r = run;
        if (r != null) {
            r.processed = 0;
            r.totalSamples = n;
        }
        // 逐样本并行：与全部分类同 kind 汇总图逐点比对（识别同口径）
        Stream.iterate(0, i -> i + 1).limit(n).parallel().forEach(i -> {
            Smp smp = pop.get(i);
            Ctx own = smp.own();
            String wkey = smp.png().toString();
            FrameClassifier.FrameWork work = works.get(wkey);
            if (work == null) {
                FrameClassifier.CachedPx raw = classifier.verifySample(smp.png(), own.w(), own.h());
                if (raw == null) {
                    return;
                }
                FrameClassifier.FrameWork built = new FrameClassifier.FrameWork(raw.px, raw.w, raw.h);
                FrameClassifier.FrameWork prev = works.putIfAbsent(wkey, built);
                work = prev == null ? built : prev;
            }
            double self = -1;
            double best = Double.POSITIVE_INFINITY;
            int ownPos = selfOf[i];
            boolean ownOk = cands.get(ownPos).valid;   // 自己的分类生成了有效图才算「可匹配样本」（C 的分母）
            for (int j = 0; j < cands.size(); j++) {
                Cand cd = cands.get(j);
                double s;
                if (cd.art == null) {
                    // 该分类没有本 kind 产物（如旧目录尚未重算、缺方框图）＝无判别点：同 0 像素空图判完全不匹配
                    // （不匹配占比 100，对外 A = 匹配占比 0）
                    s = 100.0;
                } else {
                    // 点击区图按鼠标点击点裁框、注意区图按注意点裁框；缺点击点的分类由识别端回退注意点
                    s = classifier.verifyKindScore(work, cd.art, kind, new FrameClassifier.Centers(
                            cd.ctx.actLeft() == null ? -1 : cd.ctx.actLeft(),
                            cd.ctx.actTop() == null ? -1 : cd.ctx.actTop(),
                            cd.ctx.attnLeft() == null ? 0 : cd.ctx.attnLeft(),
                            cd.ctx.attnTop() == null ? 0 : cd.ctx.attnTop()));
                    if (s < 0) {
                        continue;
                    }
                }
                if (j == ownPos) {
                    self = s;
                }
                if (s < best) {
                    best = s;
                }
            }
            if (self < 0 || best == Double.POSITIVE_INFINITY) {
                return;
            }
            synchronized (selfSum) {
                selfScore[i] = self;
                bestScore[i] = best;
                selfSum[ownPos] += self;
                cnt[ownPos]++;
                if (ownOk && self == best) {
                    hit[ownPos]++;   // 无法生成有效图的样本命中一定不算（C 只统计可匹配样本）
                }
            }
            Run rr = run;
            if (rr != null) {
                rr.processed++;
            }
        });

        // 整库汇总 + 分类级行
        int totalSamples = 0;
        double totalA = 0;
        int totalValid = 0;    // 可匹配样本数（C 分母）：归属分类生成了有效合成图的样本
        int hitValid = 0;      // 其中最佳命中恰是自家分类的样本数（C 分子）
        int genOk = 0;         // 生成了有效合成图的分类数（B 分子）
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int j = 0; j < cands.size(); j++) {
            Cand cd = cands.get(j);
            if (cd.valid) {
                genOk++;
            }
            int c = cnt[j];
            if (c == 0) {
                continue;
            }
            totalSamples += c;
            totalA += selfSum[j];
            if (cd.valid) {
                totalValid += c;
                hitValid += hit[j];
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("state", cd.ctx.state());
            row.put("action", cd.ctx.action());
            row.put("attnLeft", cd.ctx.attnLeft());
            row.put("attnTop", cd.ctx.attnTop());
            row.put("valid", cd.valid);           // 该分类此特征是否生成了有效合成图：false → 前端数值列显示「——」
            row.put("missing", cd.art == null);   // 连产物文件都没有（与「产物存在但全透明」区分展示）
            row.put("samples", c);
            // A 对外 = 匹配像素占比均值；内部 selfSum 累加的是不匹配占比，故输出取 100 − 均值
            row.put("a", 100.0 - selfSum[j] / c);
            // C（该分类行）：只有有效产物分类才有可匹配样本；无法生成有效图 → null（显示「——」而不是 0%）
            row.put("c", cd.valid ? hit[j] * 100.0 / c : null);
            row.put("hit", hit[j]);
            rows.add(row);
        }
        rows.sort((a, b) -> String.valueOf(a.get("state")).compareTo(String.valueOf(b.get("state"))));

        // totalA 是全部样本的不匹配占比之和 → 对外 A = 匹配占比均值 = 100 − 均值
        Double A = totalSamples == 0 ? null : 100.0 - totalA / totalSamples;
        Double B = cands.isEmpty() ? null : genOk * 100.0 / cands.size();    // B = 生成成功率（按分类计）
        Double C = totalValid == 0 ? null : hitValid * 100.0 / totalValid;   // C = 匹配正确率（按可匹配样本计）
        return new KindStat(kind, fp, System.currentTimeMillis(), (int) (System.currentTimeMillis() - t0),
                A, B, C, genOk, cands.size(), totalValid, totalSamples, cands.size(), rows);
    }

    private static Integer intOf(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    /** 依次取 info 里的两个键（前者优先）：attnLeft/attnTop 为裁剪中心权威键，
     *  clickLeft/clickTop 为历史键（旧产物只有它，旧数据里它就是注意点）。 */
    private static Integer pickInt(Map<String, Object> info, String primary, String legacy) {
        Integer v = intOf(info.get(primary));
        return v != null ? v : intOf(info.get(legacy));
    }

}
