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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 算法调优（第 6 视图）：建立在「特征验证」结果之上，把若干特征（kind 产物）组合成匹配算法并验证分类准确率。
 *
 * <p>内置三种算法，特征集合由验证结果自动组合（见 {@link #buildAlgos}）：
 * <ol>
 * <li><b>单一最佳匹配特征</b>：在「生成成功率 A = 100%」的特征里取「匹配正确率 D」最高的一个，只用它判定；</li>
 * <li><b>所有正确率 100% 特征</b>：取全部 D = 100% 的特征，若它们的判定不能覆盖所有分类，则对每个缺失分类
 * 补入「该分类匹配正确率最高」的特征，直到每个分类都至少有一个特征能判定；补不满时给出界面提示。</li>
 * <li><b>所有正确率 80%-99% 特征</b>：同上的「补满」口径，种子换成 D ∈ [80%, 100%) 的全部特征。</li>
 * </ol>
 *
 * <p>每个特征的基础分 {@code X = B（自分类平均匹配值）− C（其它分类平均匹配值）}；界面权重 Y ∈ [0,1]，默认 1。
 * 某分类的总分 = Σ「(100 − 不匹配占比) × X × Y」/ Σ「X × Y」（只累加该分类能判定的特征），总分唯一最高的分类
 * 即判定结果，与样本归属分类一致即命中。
 *
 * <p>打平（并列）处理，与特征验证的 E（无法区分率）对齐：
 * <ul>
 * <li>逐样本逐特征先查「该特征在这张样本上是否分不开」——某特征的最高匹配值被 ≥2 个分类并列，即视为该特征
 * 有问题，本样本的加权平均里整张放弃该特征（对该样本全部分类一致移除，避免只改一半造成两套口径）；</li>
 * <li>放弃后重新加权，若最高匹配度仍被 ≥2 个分类并列（或本样本可用特征被全部放弃、无从判定），则给出
 * <b>无法区分</b>的最终结论：既不算命中也不算判错，单独计入 {@code tie}（界面对应「无法区分率」）；</li>
 * <li>归属分类在本算法参与特征上没有「可判定的产物」（无有效产物、或产物存在但比对不了）的样本整张跳过、
 * 不进分母（与特征验证的「可匹配样本」同口径）。</li>
 * </ul>
 * 一次验证跑完全部算法，给出整体与分类级匹配正确率 + 无法区分率。与特征验证同理：会清空像素缓存并重算矩阵，
 * 结果只存内存不落盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizeService {

    private final StoragePaths storage;
    private final ClassifyStore classifyStore;
    private final FrameClassifier classifier;
    private final VerifyService verify;

    /** 单线程后台执行器（矩阵内逐样本比对用并行流加速）。 */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "optimize");
        t.setDaemon(true);
        return t;
    });

    /** 组合结果缓存（验证指纹不变时复用，避免 1 秒轮询反复扫描全部 kind 明细）。 */
    private volatile String algoSig;
    private volatile List<Algo> algoCache = List.of();

    /** 当前 / 最近一次任务进度。 */
    private volatile Run run;

    /** 最近一次验证结果。 */
    private volatile Result result;

    // ---------------------------------------------------------------- 数据结构

    /** 一个特征（算法成员）：静态指标 + 界面权重的基础分。 */
    public static final class Feature {
        public final String kind;
        /** 基础分 X = B 自分类平均匹配值 − C 其它分类平均匹配值（越大越能区分自己与其它分类）。 */
        public final double x;
        public final Double a;      // B 自分类平均匹配值（%）
        public final Double other;  // C 其它分类平均匹配值（%）
        public final Double b;      // A 生成成功率（%）
        public final Double c;      // D 匹配正确率（%）
        /** 入选依据（自动组合理由，界面展示）。 */
        public final String why;

        Feature(String kind, double x, Double a, Double other, Double b, Double c, String why) {
            this.kind = kind;
            this.x = x;
            this.a = a;
            this.other = other;
            this.b = b;
            this.c = c;
            this.why = why;
        }
    }

    /** 一个「匹配算法」= 若干特征 + 各自的界面权重 Y（默认 1）。 */
    public static final class Algo {
        public final String id;
        public final String name;
        public final String desc;
        /** 组合过程中的提示（如无法补满全部分类），null = 正常。 */
        public final String note;
        public final List<Feature> features;

        Algo(String id, String name, String desc, String note, List<Feature> features) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.note = note;
            this.features = features;
        }
    }

    /** 进度快照。 */
    public static class Run {
        public volatile boolean running = true;
        public volatile boolean finished;
        public volatile String error;
        public volatile String stage = "准备数据";
        /** 外层进度（阶段一 = 特征个数，阶段二 = 算法个数）与当前外层项。 */
        public volatile int done;
        public volatile int total;
        public volatile String cur;
        /** 当前外层项内的「张数」进度：每一层都要跑全部张样本，必须逐张回报，否则一张矩阵跑几分钟看着不动。
         *  并行矩阵按「已开始处理」计数（每张都会计入，收尾正好到 totalSamples）。 */
        public volatile int processed;
        public volatile int totalSamples;
        /** 最近处理的样本文件名（精确到张，供界面显示「正在比对 img_xxx.png」）。 */
        public volatile String sample;
        /** 跨阶段合计张数进度：进度条按它连续推进，不再随阶段切换回零。 */
        public volatile int allDone;
        public volatile int allTotal;
        public final long startedMs = System.currentTimeMillis();
        public volatile long endedMs;
    }

    /** 某特征的不匹配占比矩阵 + 各分类该特征是否「生成了有效产物」（与特征验证 Cand.valid 同口径）。 */
    private record Matrix(double[][] m, boolean[] valid) { }

    /** 匹配度比较容差：并列（打平）判定用（分值范围 0~100，1e-9 远小于任何有意义的差异）。 */
    private static final double EPS = 1e-9;

    /** 最近一次验证结果（按算法存放分类级匹配正确率）。 */
    public static class Result {
        public volatile boolean finished;
        public volatile long costMs;
        public volatile String error;
        public volatile int samples;
        public volatile int groups;
        public volatile String fp;
        public volatile List<Map<String, Object>> algos = List.of();
    }

    /** summary/ 下的一个有效分类目录（与特征验证同口径）。 */
    private record Ctx(int idx, String state, String dir, int w, int h,
                       String action, Integer attnLeft, Integer attnTop,
                       Integer actLeft, Integer actTop) {
    }

    /** classify/ 下的一张已标注原图（归属某分类目录）。 */
    private record Smp(Path png, Ctx own) {
    }

    /** 某 kind 在验证结果里的静态指标（用于组合算法，不重复跑比对）。 */
    private record Feat(String kind, Double a, Double b, Double c, Double other,
                        Map<String, Double> catC, Map<String, Boolean> catValid) {
        double x() {
            return (a == null ? 0.0 : a) - (other == null ? 0.0 : other);
        }
    }

    // ---------------------------------------------------------------- 对外

    /** 是否正在验证。 */
    public boolean running() {
        Run r = run;
        return r != null && r.running && !r.finished;
    }

    /** 特征验证是否已完成且与当前样本 / 产物一致（算法调优的前提）。 */
    public boolean ready() {
        return !algorithms().isEmpty();
    }

    /**
     * 启动一次「全部算法」的匹配正确率验证。
     *
     * @param weightsByAlgo 界面上的权重 Y（key = 算法 id，value 与算法特征顺序一一对应；缺省 = 1，超出 [0,1] 截断）
     * @return 是否成功启动
     */
    public synchronized boolean start(Map<String, List<Double>> weightsByAlgo) {
        if (running()) {
            return false;
        }
        List<Algo> algos = algorithms();
        if (algos.isEmpty()) {
            return false;
        }
        Run r = new Run();
        run = r;
        exec.submit(() -> doRun(r, algos, weightsByAlgo == null ? Map.of() : weightsByAlgo));
        return true;
    }

    /** 状态 / 结果（供前端 1 秒轮询）：前置验证情况 + 算法及其特征（X）+ 任务进度 + 最近结果。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> order = classifier.verifyOrder();
        String fp = verify.fingerprint();
        int computed = 0;
        int fresh = 0;
        for (String kind : order) {
            VerifyService.KindStat st = verify.statOf(kind);
            if (st == null) {
                continue;
            }
            computed++;
            if (fp.equals(st.fp)) {
                fresh++;
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("total", order.size());
        v.put("computed", computed);
        v.put("fresh", fresh);
        v.put("fp", fp);
        v.put("ready", fresh > 0 && fresh == order.size());
        v.put("running", verify.running());
        out.put("verify", v);

        out.put("running", running());
        out.put("ready", ready());
        out.put("samples", classifyStore.listClassifiedPngs().size());
        out.put("groups", scanGroups().size());

        List<Algo> built = algorithms();
        List<Map<String, Object>> algos = new ArrayList<>();
        for (Algo a : (built.isEmpty() ? placeholders() : built)) {
            algos.add(algoMap(a));
        }
        out.put("algos", algos);

        Run r = run;
        if (r != null) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("finished", r.finished);
            task.put("error", r.error);
            task.put("stage", r.stage);
            task.put("done", r.done);
            task.put("total", r.total);
            task.put("cur", r.cur);
            task.put("processed", r.processed);
            task.put("totalSamples", r.totalSamples);
            task.put("sample", r.sample);
            task.put("allDone", r.allDone);
            task.put("allTotal", r.allTotal);
            if (r.finished && r.endedMs > 0) {
                task.put("costMs", Math.max(0, r.endedMs - r.startedMs));
            } else if (!r.finished) {
                task.put("elapsedMs", Math.max(0, System.currentTimeMillis() - r.startedMs));
            }
            out.put("task", task);
        }

        Result res = result;
        if (res != null) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("finished", res.finished);
            rm.put("error", res.error);
            rm.put("costMs", res.costMs);
            rm.put("samples", res.samples);
            rm.put("groups", res.groups);
            rm.put("fp", res.fp);
            rm.put("stale", res.fp == null || !res.fp.equals(fp));
            rm.put("algos", res.algos);
            out.put("result", rm);
        }
        return out;
    }

    private Map<String, Object> algoMap(Algo a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("name", a.name);
        m.put("desc", a.desc);
        m.put("note", a.note);
        List<Map<String, Object>> fs = new ArrayList<>();
        for (Feature f : a.features) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("kind", f.kind);
            fm.put("x", round(f.x));
            fm.put("a", f.a);
            fm.put("other", f.other);
            fm.put("b", f.b);
            fm.put("c", f.c);
            fm.put("why", f.why);
            fs.add(fm);
        }
        m.put("features", fs);
        return m;
    }

    // ---------------------------------------------------------------- 组合算法

    private static final String ALGO1_ID = "algo1";
    private static final String ALGO1_NAME = "单一最佳匹配特征";
    private static final String ALGO1_DESC = "在生成成功率 100% 的特征里，选匹配正确率最高的一个，只用它的判定结果。";
    private static final String ALGO2_ID = "algo2";
    private static final String ALGO2_NAME = "所有正确率100%特征";
    private static final String ALGO2_DESC = "取全部匹配正确率 100% 的特征；若它们的判定不能覆盖所有分类，"
            + "则对每个缺失分类补入该分类正确率最高的特征，直到每个分类至少有一个特征能判定。";
    private static final String ALGO3_ID = "algo3";
    private static final String ALGO3_NAME = "所有正确率80%-99%特征";
    private static final String ALGO3_DESC = "取全部匹配正确率 80%–99%（不含 100%）的特征；补满口径与"
            + "「所有正确率100%特征」完全一致：若它们的判定不能覆盖所有分类，则对每个缺失分类补入"
            + "该分类正确率最高的特征，直到每个分类至少有一个特征能判定。";

    /** 特征验证结果缺失时同样下发全部算法的骨架（特征为空，前端结果显示「未计算」）。 */
    private static List<Algo> placeholders() {
        return List.of(new Algo(ALGO1_ID, ALGO1_NAME, ALGO1_DESC, null, List.of()),
                new Algo(ALGO2_ID, ALGO2_NAME, ALGO2_DESC, null, List.of()),
                new Algo(ALGO3_ID, ALGO3_NAME, ALGO3_DESC, null, List.of()));
    }

    /** 按当前特征验证结果组合算法；验证未完成 / 已过期时返回空列表。 */
    private synchronized List<Algo> algorithms() {
        List<String> order = classifier.verifyOrder();
        String fp = verify.fingerprint();
        List<Feat> feats = new ArrayList<>();
        List<String> states = new ArrayList<>();
        for (String kind : order) {
            VerifyService.KindStat st = verify.statOf(kind);
            if (st == null || !fp.equals(st.fp)) {
                return List.of();   // 未验证 / 已过期：需先在「特征验证」视图重跑
            }
            feats.add(toFeat(st));
            if (states.isEmpty() && st.rows != null) {
                for (Map<String, Object> row : st.rows) {
                    states.add(String.valueOf(row.get("state")));
                }
            }
        }
        String sig = fp + "|" + states.size();
        if (sig.equals(algoSig)) {
            return algoCache;
        }
        List<Algo> built = buildAlgos(feats, states);
        algoSig = sig;
        algoCache = built;
        return built;
    }

    private Feat toFeat(VerifyService.KindStat st) {
        Map<String, Double> catC = new HashMap<>();
        Map<String, Boolean> catValid = new HashMap<>();
        if (st.rows != null) {
            for (Map<String, Object> row : st.rows) {
                String state = String.valueOf(row.get("state"));
                Object co = row.get("c");
                catC.put(state, co instanceof Number n ? n.doubleValue() : null);
                catValid.put(state, Boolean.TRUE.equals(row.get("valid")));
            }
        }
        return new Feat(st.kind, st.a, st.b, st.c, st.other, catC, catValid);
    }

    /** 组合三种内置算法（判定都是按 D 取特征、生成率按 A）。 */
    private List<Algo> buildAlgos(List<Feat> feats, List<String> states) {
        List<Algo> out = new ArrayList<>();
        out.add(buildBestSingle(feats));
        out.add(buildAllPerfect(feats, states));
        out.add(buildAllGood(feats, states));
        return out;
    }

    /** 算法 1：在生成成功率 100% 的特征里，取匹配正确率 D 最高的一个。 */
    private Algo buildBestSingle(List<Feat> feats) {
        Feat best = null;
        for (Feat f : feats) {
            if (f.b() == null || f.b() < 99.999 || f.c() == null) {
                continue;
            }
            if (best == null || better(f, best)) {
                best = f;
            }
        }
        if (best == null) {
            return new Algo(ALGO1_ID, ALGO1_NAME, ALGO1_DESC,
                    "没有「生成成功率 100%」且能给出匹配正确率的特征，无法组合。", List.of());
        }
        List<Feature> fs = List.of(new Feature(best.kind(), best.x(), best.a(), best.other(),
                best.b(), best.c(), "生成成功率 100% 里匹配正确率最高"));
        return new Algo(ALGO1_ID, ALGO1_NAME, ALGO1_DESC, null, fs);
    }

    /** 算法 2：取全部匹配正确率 100% 的特征，再按缺失分类补入该分类正确率最高的特征，直到分类全覆盖。 */
    private Algo buildAllPerfect(List<Feat> feats, List<String> states) {
        return buildAllInRange(feats, states, ALGO2_ID, ALGO2_NAME, ALGO2_DESC,
                99.999, Double.POSITIVE_INFINITY, "匹配正确率 100%",
                "当前没有匹配正确率 100% 的特征，已改为按各分类正确率最高的特征逐个补满。");
    }

    /** 算法 3：取全部匹配正确率 80%–99%（不含 100%）的特征，补满口径与算法 2 完全一致。 */
    private Algo buildAllGood(List<Feat> feats, List<String> states) {
        return buildAllInRange(feats, states, ALGO3_ID, ALGO3_NAME, ALGO3_DESC,
                80.0, 99.999, "匹配正确率 80%–99%",
                "当前没有匹配正确率 80%–99% 的特征，已改为按各分类正确率最高的特征逐个补满。");
    }

    /**
     * 取「匹配正确率 D ∈ [lo, hi)」的全部特征作种子，再按缺失分类补入该分类正确率最高的特征，直到分类全覆盖。
     *
     * @param seedWhy 种子特征的入选依据文案；@param emptySeedNote 一个种子都没有时的提示
     */
    private Algo buildAllInRange(List<Feat> feats, List<String> states, String id, String name, String desc,
                                 double lo, double hi, String seedWhy, String emptySeedNote) {
        Set<String> chosen = new LinkedHashSet<>();
        Map<String, String> why = new LinkedHashMap<>();
        for (Feat f : feats) {
            Double c = f.c();
            if (c == null || c < lo || c >= hi) {
                continue;
            }
            chosen.add(f.kind());
            why.putIfAbsent(f.kind(), seedWhy);
        }
        int seed = chosen.size();
        Set<String> covered = coveredBy(feats, chosen);
        Set<String> remaining = new LinkedHashSet<>(states);
        remaining.removeAll(covered);
        boolean progressed = true;
        while (!remaining.isEmpty() && progressed) {
            progressed = false;
            for (String state : new ArrayList<>(remaining)) {
                if (covered.contains(state)) {
                    continue;
                }
                Feat pick = bestForState(feats, state);
                if (pick == null) {
                    continue;   // 该分类在所有特征下都没有有效产物，先跳过（多为无法补满）
                }
                chosen.add(pick.kind());
                why.putIfAbsent(pick.kind(), "为补全分类「" + state + "」补入（该分类正确率最高）");
                covered = coveredBy(feats, chosen);
                progressed = true;
            }
            remaining.removeAll(covered);
        }
        remaining.removeAll(covered);

        List<Feature> fs = new ArrayList<>();
        for (String kind : chosen) {
            Feat f = featOf(feats, kind);
            if (f != null) {
                fs.add(new Feature(f.kind(), f.x(), f.a(), f.other(), f.b(), f.c(),
                        why.getOrDefault(kind, "")));
            }
        }
        String note = null;
        if (fs.isEmpty()) {
            note = "没有任何特征可用于组合该算法。";
        } else if (!remaining.isEmpty()) {
            note = "无法补满：分类「" + String.join("、", remaining)
                    + "」在所有特征下都没有生成有效产物，没有特征能判定这些分类。";
        } else if (seed == 0) {
            note = emptySeedNote;
        }
        return new Algo(id, name, desc, note, fs);
    }

    /** D 更高的优先；并列时 X（区分度）更大者优先、再按 B 更大者。 */
    private boolean better(Feat a, Feat b) {
        int cmp = Double.compare(a.c(), b.c());
        if (cmp != 0) {
            return cmp > 0;
        }
        cmp = Double.compare(a.x(), b.x());
        if (cmp != 0) {
            return cmp > 0;
        }
        double aa = a.a() == null ? 0 : a.a();
        double ba = b.a() == null ? 0 : b.a();
        return aa > ba;
    }

    /** 某分类下匹配正确率最高的特征（要求该特征对该分类生成了有效产物且有正确率数据）；并列时取基础分 X 更大者。 */
    private Feat bestForState(List<Feat> feats, String state) {
        Feat best = null;
        double bestC = -1;
        for (Feat f : feats) {
            if (!Boolean.TRUE.equals(f.catValid().get(state))) {
                continue;
            }
            Double c = f.catC().get(state);
            if (c == null) {
                continue;
            }
            if (best == null || Double.compare(c, bestC) > 0
                    || (Double.compare(c, bestC) == 0 && f.x() > best.x())) {
                bestC = c;
                best = f;
            }
        }
        return best;
    }

    /** 已选特征集合能「正确判定」的分类并集（该分类的匹配正确率 = 100%）。 */
    private Set<String> coveredBy(List<Feat> feats, Set<String> chosen) {
        Set<String> out = new LinkedHashSet<>();
        for (String kind : chosen) {
            Feat f = featOf(feats, kind);
            if (f == null) {
                continue;
            }
            for (Map.Entry<String, Double> e : f.catC().entrySet()) {
                if (e.getValue() != null && e.getValue() >= 99.999
                        && Boolean.TRUE.equals(f.catValid().get(e.getKey()))) {
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    private Feat featOf(List<Feat> feats, String kind) {
        for (Feat f : feats) {
            if (f.kind().equals(kind)) {
                return f;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 验证

    private void doRun(Run r, List<Algo> algos, Map<String, List<Double>> weightsByAlgo) {
        // 与特征验证同理：先把识别/汇总累积的常驻像素缓存清掉腾堆，矩阵内按需解码、用后即释放
        classifier.clearPxCaches();
        List<Ctx> groups;
        List<Smp> samples;
        try {
            groups = scanGroups();
            samples = scanSamples(groups);
        } catch (Exception e) {
            fail(r, "准备数据失败：" + e);
            return;
        }
        if (groups.isEmpty() || samples.isEmpty()) {
            fail(r, "没有可用的分类产物或已标注样本，无法验证算法。");
            return;
        }
        String fp = verify.fingerprint();

        // 参与特征 = 各算法用到的 kind 并集（按验证顺序，保证矩阵遍历顺序稳定）
        Set<String> used = new LinkedHashSet<>();
        for (Algo a : algos) {
            for (Feature f : a.features) {
                used.add(f.kind);
            }
        }
        List<String> kinds = classifier.verifyOrder().stream().filter(used::contains).toList();
        if (kinds.isEmpty()) {
            fail(r, "全部算法都没有可用的特征。");
            return;
        }

        int sn = samples.size();
        int gn = groups.size();
        Map<String, FrameClassifier.FrameWork> works = new ConcurrentHashMap<>();
        Map<String, Matrix> mats = new HashMap<>();
        // 进度按「张数」回报：阶段一每个特征、阶段二每个算法都要跑全部 sn 张样本，
        // 所以外层项数会长时间不跳（一张矩阵几分钟），进度条改用合计张数连续推进
        int baseEval = kinds.size() * sn;
        r.allTotal = baseEval + algos.size() * sn;
        r.allDone = 0;
        r.stage = "逐特征比对矩阵";
        r.total = kinds.size();
        r.done = 0;
        r.totalSamples = sn;
        r.processed = 0;
        for (int ki = 0; ki < kinds.size(); ki++) {
            String kind = kinds.get(ki);
            r.cur = kind;
            r.processed = 0;
            try {
                mats.put(kind, buildMatrix(kind, groups, samples, works, r, ki * sn));
            } catch (Throwable e) {
                log.warn("算法调优矩阵失败 kind={}: {}", kind, e.toString());
                fail(r, "特征 " + kind + " 比对矩阵失败：" + e);
                return;
            }
            r.done = ki + 1;
            r.processed = sn;
            r.allDone = (ki + 1) * sn;
        }

        r.stage = "算法评估";
        r.total = algos.size();
        r.done = 0;
        r.processed = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int ai = 0; ai < algos.size(); ai++) {
            Algo a = algos.get(ai);
            r.cur = a.name;
            r.processed = 0;
            rows.add(evalAlgo(a, weightsByAlgo.get(a.id), mats, groups, samples, r, baseEval + ai * sn));
            r.done = ai + 1;
            r.processed = sn;
            r.allDone = baseEval + (ai + 1) * sn;
        }
        Result res = new Result();
        res.finished = true;
        res.costMs = Math.max(0, System.currentTimeMillis() - t0);
        res.samples = sn;
        res.groups = gn;
        res.fp = fp;
        res.algos = rows;
        result = res;

        r.stage = "完成";
        r.finished = true;
        r.endedMs = System.currentTimeMillis();
        r.running = false;
    }

    private void fail(Run r, String msg) {
        Result res = new Result();
        res.finished = true;
        res.error = msg;
        result = res;
        r.error = msg;
        r.finished = true;
        r.endedMs = System.currentTimeMillis();
        r.running = false;
        log.warn("算法调优失败: {}", msg);
    }

    /**
     * 某 kind 的 [样本][分类] 不匹配占比矩阵（<0 = 该分类没有该 kind 有效产物，跳过）。
     *
     * @param r 进度快照；@param base 本特征在「合计张数」里的起始偏移
     */
    private Matrix buildMatrix(String kind, List<Ctx> groups, List<Smp> samples,
                               Map<String, FrameClassifier.FrameWork> works, Run r, int base) {
        String file = classifier.verifyFile(kind);
        int gn = groups.size();
        int sn = samples.size();
        List<FrameClassifier.CachedPx> arts = new ArrayList<>(gn);
        boolean[] valid = new boolean[gn];
        for (int j = 0; j < gn; j++) {
            Path f = Path.of(groups.get(j).dir(), file);
            FrameClassifier.CachedPx art = Files.isRegularFile(f) ? classifier.verifyArtifact(f, kind) : null;
            arts.add(art);
            // 与特征验证 Cand.valid 同一口径：产物存在且至少一个不透明像素才算「该分类生成了有效产物」
            valid[j] = VerifyService.hasPixels(art);
        }
        double[][] m = new double[sn][gn];
        java.util.concurrent.atomic.AtomicInteger step = new java.util.concurrent.atomic.AtomicInteger();
        java.util.stream.IntStream.range(0, sn).parallel().forEach(i -> {
            Smp smp = samples.get(i);
            r.sample = smp.png().getFileName().toString();   // 精确到张：界面显示正在比对哪一张
            int n = step.incrementAndGet();
            r.processed = n;                                 // 并行流按「已开始处理」计，收尾正好到 sn
            r.allDone = base + n;
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
            for (int j = 0; j < gn; j++) {
                FrameClassifier.CachedPx art = arts.get(j);
                double s;
                if (art == null) {
                    s = 100.0;   // 无产物 = 无判别点，与空图同口径判完全不匹配
                } else {
                    Ctx c = groups.get(j);
                    s = classifier.verifyKindScore(work, art, kind, new FrameClassifier.Centers(
                            c.actLeft() == null ? -1 : c.actLeft(),
                            c.actTop() == null ? -1 : c.actTop(),
                            c.attnLeft() == null ? 0 : c.attnLeft(),
                            c.attnTop() == null ? 0 : c.attnTop()));
                }
                m[i][j] = s;
            }
        });
        return new Matrix(m, valid);
    }

    /**
     * 按某算法 + 界面权重 Y 逐样本判定：匹配度 = Σ「(100 − 不匹配占比) × X × Y」÷ Σ「X × Y」，最高的分类即判定结果。
     *
     * <p>打平处理：逐特征先剔除「这张样本上分不开」的特征（最高匹配值被 ≥2 个分类并列），再用剩余特征加权；
     * 结果仍并列（或无可用特征）则给「无法区分」结论，既不算命中也不算判错。
     *
     * @param r 进度快照；@param base 本算法在「合计张数」里的起始偏移
     */
    private Map<String, Object> evalAlgo(Algo a, List<Double> yIn,
                                         Map<String, Matrix> mats,
                                         List<Ctx> groups, List<Smp> samples, Run r, int base) {
        long t0 = System.currentTimeMillis();
        int nf = a.features.size();
        double[][][] mm = new double[nf][][];
        boolean[][] vv = new boolean[nf][];
        double[] w = new double[nf];
        List<Double> yOut = new ArrayList<>(nf);
        for (int f = 0; f < nf; f++) {
            Feature ft = a.features.get(f);
            double y = 1.0;
            if (yIn != null && f < yIn.size() && yIn.get(f) != null) {
                y = clamp(yIn.get(f));
            }
            yOut.add(round(y));
            w[f] = ft.x * y;
            Matrix mx = mats.get(ft.kind);
            mm[f] = mx.m();
            vv[f] = mx.valid();
        }
        int gn = groups.size();
        int sn = samples.size();
        int[] cnt = new int[gn];    // 可判定样本：归属分类在本算法参与特征上有有效产物（= 特征验证的「可匹配样本」）
        int[] hit = new int[gn];    // 其中：命中（自家匹配度唯一最高）
        int[] tie = new int[gn];    // 其中：无法区分（最终匹配度仍并列第一，或本样本可用特征被全部放弃）
        int skipped = 0;            // 不进分母：归属分类在本算法参与特征上没有可判定的产物（无产物 / 比对不了）
        for (int i = 0; i < sn; i++) {
            r.sample = samples.get(i).png().getFileName().toString();
            r.processed = i + 1;
            r.allDone = base + i + 1;
            int own = samples.get(i).own().idx();
            // 归属分类在本算法参与的特征里有没有「能判定的产物」：产物有效且比对得了（比对失败 = -1，
            // 与无产物同口径）。一个都没有 → 该算法对它无从判定，与特征验证的「可匹配样本」一样整张跳过
            // （不算命中也不算判错），否则会凭空拉低准确率
            boolean ownOk = false;
            for (int f = 0; f < nf; f++) {
                if (vv[f][own] && mm[f][i][own] >= 0) {
                    ownOk = true;
                    break;
                }
            }
            if (!ownOk) {
                skipped++;
                continue;
            }
            // 逐特征查「这张样本上该特征是否分不开」：最高匹配值被 ≥2 个分类并列 = 该特征有问题，
            // 本样本的加权平均里整张放弃它（对该样本全部分类一致移除：只对一边移除等于改成两套口径）
            boolean[] useF = new boolean[nf];
            for (int f = 0; f < nf; f++) {
                double bestP = Double.NEGATIVE_INFINITY;
                for (int j = 0; j < gn; j++) {
                    if (!vv[f][j]) {
                        continue;   // 该分类没有此特征的有效产物：无判别点，不该参与「有没有并列」的判定
                    }
                    double d = mm[f][i][j];
                    if (d < 0) {
                        continue;   // 分辨率 / 产物异常，比不了
                    }
                    double p = 100.0 - d;
                    if (p > bestP) {
                        bestP = p;
                    }
                }
                if (bestP == Double.NEGATIVE_INFINITY) {
                    continue;       // 该特征对所有分类都没有有效产物，本来就不参与
                }
                int at = 0;
                for (int j = 0; j < gn; j++) {
                    if (!vv[f][j]) {
                        continue;
                    }
                    double d = mm[f][i][j];
                    if (d < 0) {
                        continue;
                    }
                    if (100.0 - d >= bestP - EPS) {
                        at++;
                    }
                }
                useF[f] = at < 2;   // 并列 ≥2 → 该特征分不开这几类，本样本放弃使用它
            }
            double ownScore = Double.NEGATIVE_INFINITY;
            double bestScore = Double.NEGATIVE_INFINITY;
            int bestCnt = 0;    // 与最高匹配度并列的分类个数
            for (int j = 0; j < gn; j++) {
                double sumW = 0;       // Σ「X × Y」
                double sumWV = 0;      // Σ「匹配值 × X × Y」
                double sumP = 0;       // Σ匹配值（仅 X × Y 全 0 时兜底）
                int valid = 0;
                for (int f = 0; f < nf; f++) {
                    if (!useF[f]) {
                        continue;      // 该特征在本样本上分不开几类 → 放弃
                    }
                    double d = mm[f][i][j];
                    if (d < 0) {
                        continue;      // 该分类没有此特征的有效产物：该特征对它不参与
                    }
                    double p = 100.0 - d;   // 匹配值
                    sumP += p;
                    sumW += w[f];
                    sumWV += w[f] * p;
                    valid++;
                }
                if (valid == 0) {
                    continue;          // 该分类在本样本上一个可用特征都没有 → 不参与判定
                }
                // 匹配度 = Σ「匹配值 × X × Y」÷ Σ「X × Y」（加权平均，只算该分类能判定的特征）
                double score = Math.abs(sumW) > 1e-9 ? sumWV / sumW : sumP / valid;
                if (score > bestScore + EPS) {
                    bestScore = score;
                    bestCnt = 1;
                } else if (score >= bestScore - EPS) {
                    bestCnt++;
                }
                if (j == own) {
                    ownScore = score;
                }
            }
            cnt[own]++;
            if (bestScore == Double.NEGATIVE_INFINITY || ownScore == Double.NEGATIVE_INFINITY) {
                // 本样本可用特征被「分不开」全部放弃（所有分类都判不了，或归属分类的可判定特征都被放弃）
                // → 无法区分：判错要求「确实存在唯一一个更好的分类」才成立
                tie[own]++;
                continue;
            }
            if (bestCnt >= 2) {
                // 放弃有问题的特征后，最终匹配度仍被 ≥2 个分类并列 → 给出「无法区分」的最终结论
                tie[own]++;
                continue;
            }
            if (ownScore >= bestScore - EPS) {
                hit[own]++;   // 唯一最高且恰是自家 = 命中
            }
        }
        int totalHit = 0;
        int totalCnt = 0;
        int totalTie = 0;
        for (int j = 0; j < gn; j++) {
            totalHit += hit[j];
            totalCnt += cnt[j];
            totalTie += tie[j];
        }
        List<Map<String, Object>> rows = new ArrayList<>(gn);
        for (int j = 0; j < gn; j++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("state", groups.get(j).state());
            row.put("action", groups.get(j).action());
            row.put("samples", cnt[j]);
            row.put("hit", hit[j]);
            row.put("tie", tie[j]);
            row.put("miss", cnt[j] - hit[j] - tie[j]);
            row.put("acc", cnt[j] > 0 ? round(100.0 * hit[j] / cnt[j]) : null);
            row.put("tieRate", cnt[j] > 0 ? round(100.0 * tie[j] / cnt[j]) : null);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.id);
        out.put("name", a.name);
        out.put("weights", yOut);
        out.put("samples", totalCnt);
        out.put("hit", totalHit);
        out.put("tie", totalTie);
        out.put("miss", totalCnt - totalHit - totalTie);
        out.put("skipped", skipped);
        out.put("accuracy", totalCnt > 0 ? round(100.0 * totalHit / totalCnt) : null);
        out.put("tieRate", totalCnt > 0 ? round(100.0 * totalTie / totalCnt) : null);
        out.put("costMs", Math.max(0, System.currentTimeMillis() - t0));
        out.put("features", a.features.stream().map(f -> f.kind).toList());
        out.put("rows", rows);
        return out;
    }

    // ---------------------------------------------------------------- 工具

    /** 枚举 summary/ 有效分类目录（有 state 且尺寸有效），按目录名排序。 */
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
            if (action == null || action.isEmpty()) {
                action = CaptureMark.ACTION_NONE;
            }
            Integer cl = pickInt(info, "attnLeft", "clickLeft");
            Integer ct = pickInt(info, "attnTop", "clickTop");
            if (cl == null || ct == null) {
                cl = w / 2;
                ct = h / 2;
            }
            out.add(new Ctx(idx++, state, dir.toString(), w, h, action, cl, ct,
                    intOf(info.get("actLeft")), intOf(info.get("actTop"))));
        }
        return out;
    }

    /** 枚举 classify/ 原图并归属到其 state 对应的分类目录（无对应目录的原图不参与）。 */
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

    private static Integer intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Integer pickInt(Map<String, Object> m, String primary, String legacy) {
        Integer v = intOf(m.get(primary));
        return v != null ? v : intOf(m.get(legacy));
    }

    private static double clamp(double v) {
        if (v < 0) {
            return 0;
        }
        if (v > 1) {
            return 1;
        }
        return v;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
