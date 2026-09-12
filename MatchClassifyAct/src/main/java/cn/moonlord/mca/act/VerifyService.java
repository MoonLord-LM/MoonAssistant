package cn.moonlord.mca.act;

import cn.moonlord.mca.config.StoragePaths;
import cn.moonlord.mca.mark.CaptureMark;
import cn.moonlord.mca.mark.ClassifyStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 特征验证：对每一种「汇总图算法」（kind 产物，与识别参与比对的全套 kind 一致）做自回归评分。
 *
 * <p>按某一种算法生成了每个分类的汇总图后，把 resource/classify/ 的全部已标注原图逐张与【全部分类】的
 * 同 kind 汇总图按识别同口径逐点比对（复用 {@link FrameClassifier} 的产物/原图缓存与比对桥接），
 * 统计五个评价指标（对外顺序 A → E，内部字段对应见下）：
 * <ul>
 * <li>A = {@link KindStat#b} 生成成功率：能生成有效合成图（产物存在且非全透明）的分类占参与分类的比例
 * （越高说明该算法适用的分类越多）；</li>
 * <li>B = {@link KindStat#a} 自分类平均匹配值：归属分类生成了有效图的样本，其原图与「自己分类的该算法
 * 生成图」比对的匹配占比均值（= 100 − 不匹配占比），越高样本越集中；产物无效分类的样本不参与统计；</li>
 * <li>C = {@link KindStat#other} 其它分类平均匹配值：其它分类的原图与「该分类的该算法生成图」比对的
 * 匹配占比均值（= 100 − 不匹配占比），越低越说明该产物不易被别的分类原图匹配、区分度越好；同样只统计
 * 产物有效的分类；</li>
 * <li>D = {@link KindStat#c} 匹配正确率：在产物有效的分类里，样本与【全部分类】同 kind 汇总图比对、
 * 最佳命中「唯一最高」且恰是自己分类的占比（越高说明算法区分度越好）。无法生成有效图的不参与统计，
 * 无法给出最高值的结果（最高分被 ≥2 个分类并列、分不出该选哪一类）的也不参与统计（只计入 E）
 * → 分母 = 命中 + 误判；</li>
 * <li>E = {@link KindStat#e} 无法区分率：可匹配样本里「无法给出最高值的结果」的占比（越低越好）。
 * 分母 = 全部可匹配样本：命中 + 无法区分 + 误判 = 可匹配样本数（与 D 的分母不同）。</li>
 * </ul>
 * 没有该 kind 产物的分类（如旧目录尚未重算补齐点击区图等）＝无判别点：与「0 像素空图」同口径，判
 * 完全不匹配，但不进任何一个指标的分母（其明细行的数值列显示「——」而不是 0%），也不因缺产物而
 * 跳过这些分类及其原图的枚举。
 * 按算法逐 kind 独立计算、完成后按「当前样本/产物指纹」缓存结果并标记是否过期（样本或产物有
 * 改动后需重新验证）。独立后台任务线程跑，一次完整跑完后把全部 kind 的结果（五种指标 + 分类级明细
 * + 逐图明细）<b>完整落盘到 resource/summary/verify.json</b>；另外把每个 kind 逐图比对的原始分值
 * （「哪张原图 × 哪个分类的产物」的不匹配占比）按各图自己的大小 / 修改时间记进
 * <b>resource/summary/verify-matrix.json</b>（见 {@link VerifyMatrixCache}）：没变过的图下次验证与算法调优
 * 直接取用、不必重比。进程启动后首次访问本服务时若缓存文件还在且
 * 「resource/classify/ 已标注 + resource/summary/ 产物」的指纹与缓存里记录的一致，则直接恢复上次结果（界面显示「已计算」，
 * 无需重算）；指纹变了则恢复出来的结果一律标记为过期（界面「需重算」并提示重新验证）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyService {

    private final StoragePaths storage;
    private final ClassifyStore classifyStore;
    private final FrameClassifier classifier;
    private final VerifyMatrixCache matrix;

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

    /** 任务代次：每次新请求（点「开始验证」/ 数据变动后自动重跑）都 +1，用于把正在跑着的旧任务当场作废。 */
    private final AtomicLong runGen = new AtomicLong();

    /** 正在跑的验证线程：新请求直接打断它（阻塞式解码立刻退出，样本循环在下个检查点收手）。 */
    private final AtomicReference<Thread> runThread = new AtomicReference<>();

    /** 指纹缓存（3 秒 TTL，避免每次轮询全量 stat 产物/样本）。 */
    private volatile long fpAt;
    private volatile String fpLast;

    /** 结果缓存文件名（放 resource/summary/ 根，与产物目录并列：可手删、随 *.json 一并被 git 忽略）。 */
    private static final String CACHE_FILE = "verify.json";

    /** 指纹不统计的非产物数据文件（本服务缓存 + 逐图比对缓存 + 其它模块的缓存 / 权重 / 去重数据）：
     *  写这些文件不能反过来把自己判成过期。 */
    private static final Set<String> NON_ARTIFACT =
            Set.of(CACHE_FILE, VerifyMatrixCache.CACHE_FILE, "opt-result.json", "opt-weights.json", "dedup-cache.json");

    /** 是否是不计入指纹的文件：① 任何 {@code *.tmp} —— 写端统一「先写 .tmp 再原子改名」，.tmp 一定是写了一半的
     *  半成品（产物换新时它先出现、改名后又消失，计入只会让指纹在写盘瞬间抖动）；② 上面那份非产物数据文件名单。 */
    private static boolean isDataFile(String name) {
        if (name.endsWith(".tmp")) {
            return true;
        }
        return NON_ARTIFACT.contains(name);
    }

    /** JSON 读写（缓存的解析与落盘）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 是否已尝试从缓存文件恢复（每个进程只试一次）。 */
    private volatile boolean cacheTried;

    /** 本次进程的结果是否来自缓存文件（前端提示「已恢复上次结果」用；跑完一次新验证即清零）。 */
    private volatile boolean cacheRestored;

    /** 一个 kind 的完整验证结果。 */
    public static class KindStat {
        public final String kind;
        public final String fp;
        public final long doneMs;
        public final int costMs;
        /** 卡片 B = 自分类平均匹配值（%）：产物有效分类的样本「与自家产物比对」的匹配占比均值；无效产物样本不计。 */
        public final Double a;
        /** 卡片 A = 生成成功率（%）：能生成有效合成图的分类数 / 参与分类数。 */
        public final Double b;
        /** 卡片 C = 其它分类平均匹配值（%）：其它分类样本与该分类产物比对的匹配占比均值，越低越好。 */
        public final Double other;
        /** 卡片 D = 匹配正确率（%）：最佳命中「唯一最高」且恰是自家分类的占比。无法生成有效图的不参与统计，
         *  无法给出最高值的结果（最高分被 ≥2 个分类并列）的也不参与统计（只计入 E）→ 分母 = 可匹配样本数 − 无法区分样本数。 */
        public final Double c;
        /** 卡片 E = 无法区分率（%）：全部可匹配样本里「无法给出最高值的结果（最高分被 ≥2 个分类并列、分不出该选哪一类）」的占比。 */
        public final Double e;
        /** 无法区分样本数（E 分子；D 分母 = cSamples − tie）。 */
        public final int tie;
        /** 有效产物分类数（A 分子）与参与分类数（A 分母）。 */
        public final int genOk;
        public final int genTotal;
        /** 可匹配样本数（B / E 分母；D 的分母 = 它 − 无法区分样本数）。 */
        public final int cSamples;
        public final int samples;
        public final int groups;
        public final List<Map<String, Object>> rows;

        public KindStat(String kind, String fp, long doneMs, int costMs,
                        Double a, Double b, Double other, Double c, Double e, int tie,
                        int genOk, int genTotal, int cSamples,
                        int samples, int groups, List<Map<String, Object>> rows) {
            this.kind = kind;
            this.fp = fp;
            this.doneMs = doneMs;
            this.costMs = costMs;
            this.a = a;
            this.b = b;
            this.other = other;
            this.c = c;
            this.e = e;
            this.tie = tie;
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
        /** 本轮直接复用逐图比对结果的样本张数（界面提示省下了多少重算）与整表复用的特征数。 */
        public volatile int reuseRows;
        public volatile int reuseKinds;
        public final long startedMs;
        public volatile long endedMs;   // 结束时刻（finished 置位时记录；0 = 尚未结束）
        /** 本次任务代次：新请求（点开始验证 / 数据变动后重跑）一进来就 +1，旧代次的结果一律作废。 */
        public final long gen;
        /** 已被更新的请求取代（数据又变了）：本轮结果不再有意义，不是失败（前端据此提示而不是报错）。 */
        public volatile boolean superseded;
        /** 本轮运行期间样本 / 产物变动过（标注保存、汇总分析自动重算产物等）：本轮结果算的是变动之前的数据，
         *  跑完必然被判「需重算」——界面据此说明原因，而不是让用户看着算完的结果莫名又变回「需重算」。 */
        public volatile boolean dataChanged;

        Run(long gen, int total) {
            this.gen = gen;
            this.total = total;
            this.startedMs = System.currentTimeMillis();
        }
    }

    /** resource/summary/ 下的一个有效分类目录（产物目录 = 分类标注）：attnLeft/attnTop = 注意点
     *  （注意区图框心，未设 = 屏幕中心），actLeft/actTop = 鼠标点击点（点击区图框心，仅 click 分类有）。 */
    private record Ctx(int idx, String state, String dir, int w, int h,
                       String action, Integer attnLeft, Integer attnTop,
                       Integer actLeft, Integer actTop) {
    }

    /** resource/classify/ 下的一张已标注原图（归属某分类目录）；mtime / size = 原图文件自身的大小与修改时间
     *  （逐图比对结果的缓存键：图被换掉就能识别出来，没换过的那一行直接复用）。 */
    private record Smp(Path png, Ctx own, long mtime, long size) {
    }

    /** 某 kind 计算时的参与目录（产物已成功解码，或整表复用时用上次记下的标记）。 */
    private static final class Cand {
        final Ctx ctx;
        final FrameClassifier.CachedPx art;
        /** 该分类此特征的产物解出来了（文件在且解得开）：复用缓存时不解码，用上次记下的标记。 */
        final boolean decoded;
        /** 该分类用此特征是否生成了有效合成图：产物存在且至少有一个不透明像素（非全透明空图）。 */
        final boolean valid;

        Cand(Ctx ctx, FrameClassifier.CachedPx art) {
            this(ctx, art, art != null, hasPixels(art));
        }

        Cand(Ctx ctx, FrameClassifier.CachedPx art, boolean decoded, boolean valid) {
            this.ctx = ctx;
            this.art = art;
            this.decoded = decoded;
            this.valid = valid;
        }
    }

    /** 产物是否有有效像素（存在任一点不透明，命中即返回）；解码失败 / 全透明空图 = 生成不出有效图。
     *  算法调优（OptimizeService）判定「可匹配样本」、画面识别（FrameClassifier）判定「并列」都用它
     *  （空图恒 0 分、不数进并列），保证 D / 匹配正确率与运行时识别完全对齐。 */
    static boolean hasPixels(FrameClassifier.CachedPx art) {
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

    /** 当前样本 / 产物指纹（供算法调优判断验证结果是否新鲜；内部 3 秒缓存）。 */
    public String fingerprint() {
        return fp();
    }

    /** 缓存里记的计算时指纹（{@code recordedFp}）与当前数据是否一致 —— 判「已计算 / 需重算」一律走这里
     *  （缓存里本来就没有指纹、或指纹是空的，同样算过期）。 */
    public boolean isFresh(String recordedFp) {
        return recordedFp != null && !recordedFp.isEmpty() && recordedFp.equals(fp());
    }

    /** 某 kind 的验证结果（未验证返回 null）：算法调优读取 A/B/C/D 与分类级明细来组合特征、算基础分 X。 */
    public KindStat statOf(String kind) {
        ensureCacheLoaded();
        return kind == null ? null : results.get(kind);
    }

    /**
     * 启动一次完整验证（计算全部 kind）：**抢断式** —— 已有任务在跑就当场作废它
     *（它读的是变动前的样本 / 产物，跑完也没有意义）并按最新状态立刻重跑，不再「等它跑完再点」。
     */
    public synchronized boolean start() {
        long gen = supersede();
        String fp = fpNow();
        List<String> order = classifier.verifyOrder();
        Run r = new Run(gen, order.size());
        run = r;
        exec.submit(() -> doRun(r, fp, order));
        return true;
    }

    /** 抢断：把正在跑的旧任务当场作废（输入已过期）并打断它，返回本次新代次。 */
    private long supersede() {
        long gen = runGen.incrementAndGet();
        Run prev = run;
        if (prev != null && !prev.finished) {
            prev.superseded = true;
        }
        Thread th = runThread.get();
        if (th != null) {
            th.interrupt();   // 阻塞式解码立刻抛错；样本循环在下个检查点收手
        }
        return gen;
    }

    /** 收尾：置结束标记并解绑本轮线程（被取代的任务同样走它，只是结果不落盘）。 */
    private void endRun(Run r) {
        r.finished = true;
        r.endedMs = System.currentTimeMillis();
        r.running = false;
        runThread.compareAndSet(Thread.currentThread(), null);
        Thread.interrupted();   // 清掉打断标志，避免污染串行池的后续任务
    }

    private void doRun(Run r, String fp, List<String> order) {
        runThread.set(Thread.currentThread());   // 记下本轮线程：新请求会直接打断它
        cacheRestored = false;   // 本轮算出来的结果即将覆盖「从缓存文件恢复」的旧结果
        // 验证逐 kind 解码全部参与分类的产物像素，走不写缓存的按需解码、每 kind 用后即释放；
        // 常驻软引用缓存是否回收交给 JVM 的 GC 决定，这里不做任何手动清理
        List<Ctx> groups = new ArrayList<>();
        List<Smp> samples = new ArrayList<>();
        try {
            groups = scanGroups();
            samples = scanSamples(groups);
        } catch (Exception e) {
            if (r.superseded) {
                endRun(r);   // 打断造成的异常：本轮已被最新一次验证取代，不当失败
                return;
            }
            r.error = "准备数据失败：" + e;
            endRun(r);
            log.warn("特征验证准备失败: {}", e.toString());
            return;
        }
        // 每张样本的画面块压缩序列（FrameWork）整轮只建一次、全部 kind 复用（样本自身全幅像素与产物
        // 复用 FrameClassifier 缓存，这里只多放每样本一份小体积的 8/32 块序列）
        Map<String, FrameClassifier.FrameWork> works = new ConcurrentHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            if (r.superseded) {
                break;   // 数据又变了：本任务当场作废，让最新一轮接着跑（旧结果算出来也没有意义）
            }
            // 每个 kind 开始前轻量比一次指纹（fp() 带 3 秒缓存，不会反复全量 stat）：样本 / 产物一旦在本轮
            // 运行期间变过，本轮算的就仍是变动前的数据、跑完必然被判「需重算」——记下来，界面要说明原因
            if (i > 0 && !r.dataChanged) {
                String now = fp();
                if (!now.equals(fp)) {
                    r.dataChanged = true;
                    log.warn("特征验证运行期间数据发生变动（指纹 {} → {}）：本轮结果将标记为需重算", fp, now);
                }
            }
            String kind = order.get(i);
            r.cur = kind;
            r.done = i;
            try {
                KindStat st = computeKind(r, kind, fp, groups, samples, works);
                if (st == null) {
                    break;   // 被新请求作废：一个不完整的 kind 结果也不该采用
                }
                results.put(kind, st);
            } catch (Throwable e) {
                if (r.superseded) {
                    break;   // 打断造成的异常（解码途中被打断等）：本轮已被取代，不当失败
                }
                r.error = "验证 kind=" + kind + " 失败：" + e;
                log.warn("特征验证 kind={} 失败: {}", kind, e.toString());
                break;
            }
            r.done = i + 1;
        }
        // 一轮完整跑完（无中断）→ 结果完整落盘 + 逐图比对结果一起落盘：下次启动只要指纹没变就直接复用，
        // 指纹变了也能按「每张图的大小 / 修改时间」把没变过的部分接着复用（算法调优同样直接取用）；
        // 被新请求作废的那一轮不落盘（结果既不完整、也已经过期）
        if (r.error == null && !r.superseded) {
            if (!r.dataChanged && !fpNow().equals(fp)) {
                r.dataChanged = true;   // 最后一个 kind 跑完后才发生的变动：同样要把这一轮标出来
            }
            saveCache(fp, samples.size(), groups.size());
            matrix.save(fp);
        }
        endRun(r);
    }

    /** 结果 / 总览（供前端轮询）：任务进度 + 全部 kind 的评分与状态。 */
    public Map<String, Object> status() {
        ensureCacheLoaded();
        Map<String, Object> out = new LinkedHashMap<>();
        String fp = fp();
        out.put("running", running());
        out.put("samples", classifyStore.listClassifiedPngs().size());
        out.put("groups", scanGroups().size());
        // 结果从缓存文件恢复（本进程）与缓存文件名：界面据此提示「已加载上次结果 / 结果落在哪」
        out.put("cached", cacheRestored);
        out.put("cacheFile", CACHE_FILE);
        // 逐图比对结果缓存（resource/summary/verify-matrix.json）：界面提示「省掉了多少重算」与缓存规模
        out.putAll(matrix.status());
        int staleCount = 0;
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
                k.put("e", null);
                k.put("genOk", 0);
                k.put("genTotal", 0);
                k.put("samples", 0);
            } else {
                boolean fresh = isFresh(st.fp);
                if (!fresh) {
                    staleCount++;   // 需重算的算法数（前端提示「数据有变动，请重新验证」）
                }
                k.put("state", fresh ? "done" : "stale");
                k.put("a", st.a);
                k.put("b", st.b);          // 生成成功率（%）
                k.put("c", st.c);          // 匹配正确率（%）
                k.put("e", st.e);          // 无法区分率（%）
                k.put("genOk", st.genOk);
                k.put("genTotal", st.genTotal);
                k.put("samples", st.samples);
            }
            kinds.add(k);
        }
        out.put("stale", staleCount);   // 数据有变动、需重算的算法数
        out.put("fp", fp);              // 当前样本/产物指纹（前端按它去重「需重新验证」提示）
        out.put("kinds", kinds);
        Run r = run;
        if (r != null) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("finished", r.finished);
            if (r.finished && r.endedMs > 0) {
                task.put("costMs", Math.max(0, r.endedMs - r.startedMs));
            }
            task.put("error", r.error);
            task.put("superseded", r.superseded);   // 跑一半被更新的请求作废：界面提示「已被最新一轮取代」而不是失败
            task.put("dataChanged", r.dataChanged); // 运行期间数据变过：本轮结果算的是变动前的数据，界面说明原因
            task.put("done", r.done);
            task.put("total", r.total);
            task.put("cur", r.cur);
            task.put("processed", r.processed);
            task.put("totalSamples", r.totalSamples);
            task.put("startedMs", r.startedMs);     // 开始时刻：界面按「当前时间 − 它」显示逐秒走动的已耗时
            task.put("reuseRows", r.reuseRows);     // 本轮直接复用逐图比对结果的样本张数
            task.put("reuseKinds", r.reuseKinds);   // 本轮整表复用的特征数
            out.put("task", task);
        }
        return out;
    }

    /** 某 kind 的分类级明细（未计算返回 null；fresh 表示与当前样本/产物一致）。 */
    public Map<String, Object> detail(String kind) {
        ensureCacheLoaded();
        if (kind == null || !classifier.verifyOrder().contains(kind)) {
            return null;
        }
        KindStat st = results.get(kind);
        if (st == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("fresh", isFresh(st.fp));
        out.put("doneMs", st.doneMs);
        out.put("costMs", st.costMs);
        out.put("a", st.a);              // 自分类平均匹配值（%）
        out.put("b", st.b);              // 生成成功率（%）：genOk / genTotal
        out.put("other", st.other);      // 其它分类平均匹配值（%）：越低越好
        out.put("c", st.c);              // 匹配正确率（%）：能给出结果的样本（可匹配 − 无法区分）里最佳命中恰是自家分类的占比
        out.put("e", st.e);              // 无法区分率（%）：全部可匹配样本里无法给出最高值的结果（并列）的占比
        out.put("tie", st.tie);          // 无法区分样本数（D 分母 = cSamples − tie）
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

    /** 样本 / 产物变动指纹：classify 原图与标注 json、summary 产物（png + info.json）的相对路径 + 大小 + 修改时间。 */
    private synchronized String fpNow() {
        StringBuilder sb = new StringBuilder();
        appendTreeFiles("classify", storage.classify(), sb);
        appendTreeFiles("summary", storage.summary(), sb);
        fpAt = System.currentTimeMillis();
        fpLast = Integer.toHexString(sb.toString().hashCode());
        return fpLast;
    }

    /** 把一个分区目录树下的全部常规文件按「分区名 / 相对路径 | 大小 | 修改时间」记进指纹。
     *  指纹只回答「这批数据是不是同一份」，所以只记相对各自分区根的路径，绝不带绝对路径 / 盘符：
     *  整个运行目录搬到别处、分区根改名（如从 {@code classify/} 挪到 {@code resource/classify/}）、
     *  或换一个工作目录启动，只要文件本身（名字、大小、修改时间）没动，指纹就不该变——否则会白算一轮。
     *  （分区名 + 相对路径 = 文件列表与文件名的完整身份：增删图 / 改名 / 换内容 / 换分类归属都仍会变指纹。） */
    private void appendTreeFiles(String tag, Path root, StringBuilder sb) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> dirs = Files.walk(root)) {
            dirs.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            return;
        }
        // 排序键也用相对路径：绝对前缀变了不影响行序（顺序本身只是为了让指纹稳定可复现）
        files.sort(Comparator.comparing(p -> root.relativize(p).toString()));
        for (Path p : files) {
            if (isDataFile(p.getFileName().toString())) {
                continue;   // 缓存 / 结果 / 权重 / 去重数据不是产物：写它们（含本服务自己的缓存）不能反过来改变指纹
            }
            try {
                BasicFileAttributes at = Files.readAttributes(p, BasicFileAttributes.class);
                sb.append(tag).append('/').append(root.relativize(p)).append('|').append(at.size()).append('|')
                  .append(at.lastModifiedTime().toMillis()).append('\n');
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 落盘缓存（resource/summary/verify.json）

    /** 缓存文件：resource/summary/verify.json（完整缓存 = 五种指标 + 分类级明细 + 「查看详细」的逐图明细）。 */
    public Path cacheFile() {
        return storage.summary().resolve(CACHE_FILE);
    }

    /** 首次访问时从缓存文件完整恢复上次结果（幂等；文件不存在 / 解析失败只记日志）。
     *  恢复出来的 {@link KindStat} 带的是「上次计算时」的指纹，与当前指纹不符即自动判为过期
     *  （{@link #status()} 里 state=stale）→ 界面提示重新验证。 */
    private synchronized void ensureCacheLoaded() {
        if (cacheTried) {
            return;
        }
        cacheTried = true;
        Path f = cacheFile();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(f.toFile());
            JsonNode kinds = root == null ? null : root.get("kinds");
            if (kinds == null || !kinds.isObject()) {
                return;
            }
            int n = 0;
            var it = kinds.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                KindStat st = parseStat(e.getKey(), e.getValue());
                if (st != null) {
                    results.put(e.getKey(), st);
                    n++;
                }
            }
            cacheRestored = n > 0;
            log.info("特征验证结果已从缓存恢复（{}）：{} 种算法，缓存指纹 {}；与当前指纹一致才显示「已计算」",
                    f, n, root.path("fp").asText(""));
        } catch (Exception e) {
            log.warn("特征验证缓存读取失败 {}：{}", f, e.toString());
        }
    }

    /** 把本轮全部 kind 的结果完整落盘（一次完整跑完后调用；先写 .tmp 再原子改名）。 */
    private synchronized void saveCache(String fp, int samples, int groups) {
        Map<String, Object> kinds = new LinkedHashMap<>();
        for (String kind : classifier.verifyOrder()) {
            KindStat st = results.get(kind);
            if (st != null) {
                kinds.put(kind, statJson(st));
            }
        }
        if (kinds.isEmpty()) {
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("fp", fp);                       // 计算时的样本/产物指纹：与当前不一致 = 需重算
        root.put("savedMs", System.currentTimeMillis());
        root.put("samples", samples);
        root.put("groups", groups);
        root.put("kinds", kinds);
        Path f = cacheFile();
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            Files.createDirectories(f.getParent());
            JSON.writeValue(tmp.toFile(), root);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            log.info("特征验证结果已缓存：{}（{} 种算法）", f, kinds.size());
        } catch (IOException e) {
            log.warn("特征验证缓存写入失败 {}：{}", f, e.toString());
        }
    }

    /** {@link KindStat} → JSON 节点（rows 里的 wrong / tied 逐图明细一并写入，供前端离线打开「查看详细」）。 */
    private static Map<String, Object> statJson(KindStat st) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("fp", st.fp);
        o.put("doneMs", st.doneMs);
        o.put("costMs", st.costMs);
        o.put("a", st.a);
        o.put("b", st.b);
        o.put("other", st.other);
        o.put("c", st.c);
        o.put("e", st.e);
        o.put("tie", st.tie);
        o.put("genOk", st.genOk);
        o.put("genTotal", st.genTotal);
        o.put("cSamples", st.cSamples);
        o.put("samples", st.samples);
        o.put("groups", st.groups);
        o.put("rows", st.rows);
        return o;
    }

    /** JSON 节点 → {@link KindStat}（缺字段按 null / 0 兜底；rows 反序列化回原本的 Map / List 结构）。 */
    private KindStat parseStat(String kind, JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode arr = n.get("rows");
        if (arr != null && arr.isArray()) {
            for (JsonNode r : arr) {
                rows.add(JSON.convertValue(r, new TypeReference<LinkedHashMap<String, Object>>() {}));
            }
        }
        return new KindStat(kind, n.path("fp").asText(""), n.path("doneMs").asLong(0), n.path("costMs").asInt(0),
                numOf(n, "a"), numOf(n, "b"), numOf(n, "other"), numOf(n, "c"), numOf(n, "e"),
                n.path("tie").asInt(0), n.path("genOk").asInt(0), n.path("genTotal").asInt(0),
                n.path("cSamples").asInt(0), n.path("samples").asInt(0), n.path("groups").asInt(0), rows);
    }

    /** JSON 里的可空数值（null / 缺失 → null，界面显示「——」）。 */
    private static Double numOf(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asDouble();
    }

    // ---------------------------------------------------------------- 计算

    /** 枚举 resource/summary/ 有效分类目录（有 state 且尺寸有效），按目录名排序。 */
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

    /** 枚举 resource/classify/ 原图并归属到其 state 对应的分类目录（无对应目录的原图不参与验证）。 */
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
                long[] at = stat(png);
                out.add(new Smp(png, own, at[0], at[1]));
            }
        }
        return out;
    }

    /** 文件的大小 / 修改时间（-1 = 读不到）；逐图比对结果的缓存键用它，故每次扫描只 stat 一遍。 */
    private static long[] stat(Path p) {
        try {
            BasicFileAttributes at = Files.readAttributes(p, BasicFileAttributes.class);
            return new long[]{at.lastModifiedTime().toMillis(), at.size()};
        } catch (IOException e) {
            return new long[]{-1, 0};
        }
    }

    /** 算一个 kind 的四个指标（A 生成成功率 / B 自分类平均匹配值 / C 其它分类平均匹配值 / D 匹配正确率）与分类级明细。
     *  works = 本验证轮共享的每样本画面块压缩缓存（键 = 原图路径）。
     *  逐图比对结果按「原图 + 产物各自的名称/大小/修改时间」记账（{@link VerifyMatrixCache}）：没变过的
     *  整表 / 整行直接复用，连产物与原图都不解码。 */
    private KindStat computeKind(Run r, String kind, String fp, List<Ctx> groups, List<Smp> samples,
                                 Map<String, FrameClassifier.FrameWork> works) {
        long t0 = System.currentTimeMillis();
        String file = classifier.verifyFile(kind);
        if (r.superseded) {
            return null;   // 已被新请求作废：不再开新的特征计算
        }

        // 参与目录 = 全部分类目录（每个分类都生成全套 42 张产物，含 12 张注意区图；
        // click 分类另有 12 张点击区图 = 54 张；缺该 kind 产物只发生在旧目录尚未重算补齐时）。
        // 没有本 kind 产物的分类＝无判别点，与「0 像素空图」同口径：判完全不匹配（内部按不匹配占比 100 算）、
        // 不进任何指标统计（只用于让该分类行显示「——」）；解码失败同视为无产物。
        // 列键 = 各分类该 kind 的产物 + info.json 的大小/修改时间（+ 尺寸与裁剪框心）：全对得上说明产物像素
        // 与裁剪都没变，整表（含「该分类有没有有效产物」）直接复用、一个 PNG 都不解码；对不上才整表重算
        List<VerifyMatrixCache.ColKey> cols = new ArrayList<>(groups.size());
        for (Ctx c : groups) {
            Path dir = Path.of(c.dir());
            cols.add(VerifyMatrixCache.colKey(c.state(), c.w(), c.h(), c.attnLeft(), c.attnTop(),
                    c.actLeft(), c.actTop(), dir.resolve(file), dir.resolve(ArtifactKind.FILE_INFO)));
        }
        VerifyMatrixCache.Table cachedTab = matrix.table(kind, cols);
        List<Cand> cands = new ArrayList<>();
        int real = 0;
        final VerifyMatrixCache.Table tab;   // 列对得上 = 整表照用，对不上 = 现算一张新表
        if (cachedTab != null) {
            tab = cachedTab;
            for (int j = 0; j < groups.size(); j++) {
                boolean dec = tab.decoded(j);
                cands.add(new Cand(groups.get(j), null, dec, tab.valid(j)));
                if (dec) {
                    real++;
                }
            }
            r.reuseKinds++;
        } else {
            boolean[] dec = new boolean[groups.size()];
            boolean[] val = new boolean[groups.size()];
            for (int j = 0; j < groups.size(); j++) {
                Ctx c = groups.get(j);
                Path f = Path.of(c.dir(), file);
                FrameClassifier.CachedPx art = Files.isRegularFile(f) ? classifier.verifyArtifact(f, kind) : null;
                dec[j] = art != null;
                val[j] = hasPixels(art);
                if (art != null) {
                    real++;
                }
                cands.add(new Cand(c, art, dec[j], val[j]));
            }
            tab = matrix.begin(kind, cols, dec, val);
        }
        if (real == 0) {
            return new KindStat(kind, fp, System.currentTimeMillis(), (int) (System.currentTimeMillis() - t0),
                    null, null, null, null, null, 0, 0, 0, 0, 0, 0, List.of());
        }

        // 目录下标 → 参与目录下标（用于定位样本的“自分类”产物）
        Map<Integer, Integer> posOf = new LinkedHashMap<>();
        for (int j = 0; j < cands.size(); j++) {
            posOf.put(cands.get(j).ctx.idx(), j);
        }

        // 该 kind 的样本群：全部分类都有参与位（无产物者按不匹配占比满值 100 参与比对，但不进任何指标统计），
        // 故归属在扫描目录里的原图全数计入
        List<Smp> pop = samples.stream().filter(s -> posOf.containsKey(s.own().idx())).toList();
        int n = pop.size();
        double[] selfScore = new double[n];
        double[] bestScore = new double[n];
        // 每张样本最佳命中（不匹配占比最小）的分类下标：用于 D 的误判明细；-1 = 未进统计（样本被跳过等）
        int[] bestPos = new int[n];
        // C 其它分类平均匹配值：内部累加「不匹配占比」（对外取 100 − 均值）；只统计产物有效的其它分类与归属分类有效的样本
        double[] otherSum = new double[1];
        int[] otherCnt = new int[1];
        for (int i = 0; i < n; i++) {
            selfScore[i] = -1;
            bestScore[i] = Double.POSITIVE_INFINITY;
            bestPos[i] = -1;
        }
        // 无法区分（最佳匹配被 ≥2 个分类并列）：E 的分子按分类累计，并列到的分类名写进 tiedOf 供明细弹窗展示
        String[] tiedOf = new String[n];
        int[] tieCnt = new int[cands.size()];
        double[] selfSum = new double[cands.size()];
        int[] cnt = new int[cands.size()];
        int[] hit = new int[cands.size()];
        int[] selfOf = new int[n];
        for (int i = 0; i < n; i++) {
            selfOf[i] = posOf.get(pop.get(i).own().idx());
        }

        r.processed = 0;
        r.totalSamples = n;
        // 逐样本并行：与全部分类同 kind 汇总图逐点比对（识别同口径）。
        // 逐图比对结果缓存：这张原图（文件名 + 大小 + 修改时间 + 所属分类尺寸）算过的整行直接取用，
        // 连原图都不解码；对不上（新图 / 图被换过）才真比一遍，并把新算的行记回缓存
        AtomicInteger step = new AtomicInteger();
        AtomicInteger reuse = new AtomicInteger();
        // 整表复用路径的产物按需解码槽（列号 → 产物图；解不开的不缓存，下次再看）：
        // 只有「没命中缓存行」的样本才会真的比对，故多数情况下一张产物都不解
        ConcurrentHashMap<Integer, FrameClassifier.CachedPx> lazyArts = new ConcurrentHashMap<>();
        Stream.iterate(0, i -> i + 1).limit(n).parallel().forEach(i -> {
            if (r.superseded) {
                return;   // 数据又变了：剩余样本不再比对（本轮已作废，省下的是真金白银的解码 + 比对）
            }
            Smp smp = pop.get(i);
            Ctx own = smp.own();
            Run pr = run;
            if (pr != null) {
                pr.processed = step.incrementAndGet();   // 并行流按「已开始处理」计，收尾正好到 n
            }
            int vs = cands.size();
            int ownPos = selfOf[i];
            boolean ownOk = cands.get(ownPos).valid;   // 自己的分类生成了有效图才算「可匹配样本」（B / D / E 分母）
            String name = smp.png().getFileName().toString();
            double[] sv = tab.row(name, smp.mtime(), smp.size(), own.w(), own.h());
            if (sv != null && sv.length == vs) {
                reuse.incrementAndGet();
            } else {
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
                sv = new double[vs];   // 本样本与每个分类同类产物的不匹配占比；<0 = 比不了（分辨率等异常）
                for (int j = 0; j < vs; j++) {
                    Cand cd = cands.get(j);
                    // 整表复用路径没预先解码产物：上次记着「没有产物 / 解不开」的列算 100，
                    // 其余按需解码一次（同一列解出来的图全行共用，只有真没命中缓存的行才会走到）
                    FrameClassifier.CachedPx art = null;
                    if (cd.decoded) {
                        art = cd.art != null ? cd.art : lazyArts.computeIfAbsent(j, k -> {
                            Path fa = Path.of(groups.get(k).dir(), file);
                            return Files.isRegularFile(fa) ? classifier.verifyArtifact(fa, kind) : null;
                        });
                    }
                    double s;
                    if (art == null) {
                        // 该分类没有本 kind 产物（如旧目录尚未重算、缺方框图）＝无判别点：同 0 像素空图判完全不匹配
                        // （不匹配占比 100）；该分类及其样本不进任何指标统计，只在明细行显示「——」
                        s = 100.0;
                    } else {
                        // 点击区图按鼠标点击点裁框、注意区图按注意点裁框；缺点击点的分类由识别端回退注意点
                        s = classifier.verifyKindScore(work, art, kind, new FrameClassifier.Centers(
                                cd.ctx.actLeft() == null ? -1 : cd.ctx.actLeft(),
                                cd.ctx.actTop() == null ? -1 : cd.ctx.actTop(),
                                cd.ctx.attnLeft() == null ? 0 : cd.ctx.attnLeft(),
                                cd.ctx.attnTop() == null ? 0 : cd.ctx.attnTop()));
                        if (s < 0) {
                            sv[j] = -1;
                            continue;
                        }
                    }
                    sv[j] = s;
                }
                tab.fill(name, smp.mtime(), smp.size(), own.w(), own.h(), sv);
            }
            // 以下统计只读 sv：复用与重算共用同一套口径，谁都不在这里加分值
            double self = -1;
            double omSum = 0;   // 本样本与「其它分类的该类产物」的不匹配占比之和（C 的分子）
            int omCnt = 0;
            for (int j = 0; j < vs; j++) {
                if (sv[j] < 0) {
                    continue;
                }
                if (j == ownPos) {
                    self = sv[j];
                } else if (cands.get(j).valid && ownOk) {
                    omSum += sv[j];   // C：别的分类的原图 vs 该分类产物（仅产物有效、且样本归属分类有效时统计）
                    omCnt++;
                }
            }
            // 最佳命中只在「生成了有效产物」的分类里选：没有产物的分类＝无判别点（不匹配占比恒 100），
            // 不可能被选中，也就不该参与「有没有并列」的判定
            double best = Double.POSITIVE_INFINITY;
            int bestAt = -1;    // 本样本最佳命中的分类下标（D 的误判明细用）
            int bestCnt = 0;    // 与最佳命中同分的有效分类个数：≥2 = 分值一样、分不出该选哪一类
            for (int j = 0; j < vs; j++) {
                if (sv[j] < 0 || !cands.get(j).valid) {
                    continue;
                }
                if (sv[j] < best) {
                    best = sv[j];
                    bestAt = j;
                    bestCnt = 1;
                } else if (sv[j] == best) {
                    bestCnt++;
                }
            }
            if (self < 0 || best == Double.POSITIVE_INFINITY) {
                return;
            }
            // 并列（分值一样、分不出该选哪一类）= 「无法给出最高值的结果」：不进 D 的分母（命中 + 误判），
            // 单独计入 E（无法区分率）；并列到哪些分类记下来，供前端「查看详细」弹窗解释
            boolean tie = ownOk && bestCnt >= 2;
            String tiedStr = null;
            if (tie) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < vs; j++) {
                    if (j == ownPos || sv[j] < 0 || sv[j] != best || !cands.get(j).valid) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append('、');
                    }
                    sb.append(cands.get(j).ctx.state());
                }
                tiedStr = sb.toString();
            }
            synchronized (selfSum) {
                selfScore[i] = self;
                bestScore[i] = best;
                bestPos[i] = bestAt;
                tiedOf[i] = tiedStr;
                selfSum[ownPos] += self;
                cnt[ownPos]++;
                if (ownOk) {
                    if (tie) {
                        tieCnt[ownPos]++;   // E：可匹配样本里最佳匹配被 ≥2 个分类并列的
                    } else if (self == best) {
                        hit[ownPos]++;      // D：最佳命中唯一且恰是自家才算命中（产物无效的样本一定不算）
                    }
                }
                otherSum[0] += omSum;
                otherCnt[0] += omCnt;
            }
        });
        r.reuseRows += reuse.get();   // 整轮累加（computeKind 按 kind 顺序单线程跑）

        // 整库汇总 + 分类级行（B / C / D 的统计一律排除「该分类没生成有效图」的样本）
        int totalSamples = 0;     // 全部参与分类的样本数（仅用于「样本 N 张」展示）
        double selfValidSum = 0;  // B（自分类平均匹配值）分子：产物有效分类样本的不匹配占比之和
        int totalValid = 0;       // B / E 的分母：可匹配样本数（归属分类生成了有效合成图）
        int hitValid = 0;         // D（匹配正确率）分子：其中最佳命中唯一且恰是自家分类的样本数
        int tieValid = 0;         // E（无法区分率）分子：其中最佳匹配被 ≥2 个分类并列的样本数
        int genOk = 0;            // A（生成成功率）分子：生成了有效合成图的分类数
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
            if (cd.valid) {
                totalValid += c;
                hitValid += hit[j];
                tieValid += tieCnt[j];
                selfValidSum += selfSum[j];
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("state", cd.ctx.state());
            row.put("action", cd.ctx.action());
            row.put("attnLeft", cd.ctx.attnLeft());
            row.put("attnTop", cd.ctx.attnTop());
            row.put("valid", cd.valid);       // 该分类此特征是否生成了有效合成图：false → 前端数值列显示「——」
            row.put("missing", !cd.decoded);  // 连产物文件都没有（与「产物存在但全透明」区分展示）
            row.put("samples", c);
            // B（该分类行）= 自分类匹配占比均值；内部 selfSum 累加的是不匹配占比，故输出取 100 − 均值；
            // 无法生成有效图 → null（显示「——」而不是 0%，其样本不进任何指标统计）
            row.put("a", cd.valid ? 100.0 - selfSum[j] / c : null);
            // D（该分类行）：无法生成有效图的不参与统计、无法给出最高值的结果（最高分被 ≥2 个分类并列）的
            // 也不参与统计（只计入 E）→ 分母 = 可匹配样本 − 无法区分样本（= 命中 + 误判）；
            // 一个都判不出来时给 null（显示「——」，而不是 0%）；无法生成有效图同样给 null
            int decided = c - tieCnt[j];
            row.put("c", cd.valid && decided > 0 ? hit[j] * 100.0 / decided : null);
            row.put("hit", hit[j]);
            row.put("decided", cd.valid ? decided : null);   // 本分类「能给出结果」的样本数（D 的分母）
            // E（该分类行）= 无法区分率：分母 = 本分类全部可匹配样本（命中 + 无法区分 + 误判）
            row.put("e", cd.valid ? tieCnt[j] * 100.0 / c : null);
            row.put("tie", tieCnt[j]);
            // D 列「查看详细」（匹配错误明细）弹窗逐图列出：可匹配样本里最佳命中「唯一且」不是自家分类的，
            // 按「被误判到的分类」再按原图名排序；命中 / 自家分值 = 匹配占比（100 − 不匹配占比），越高越像
            List<Map<String, Object>> wrongs = new ArrayList<>();
            // E 列「查看详细」（无法区分明细）弹窗逐图列出：最佳匹配并列 ≥2 个分类的样本，列出并列到的分类名
            List<Map<String, Object>> tieds = new ArrayList<>();
            if (cd.valid) {
                for (int i = 0; i < n; i++) {
                    // 并列的样本归 E 明细（tieds），不算「误判」
                    if (selfOf[i] != j || tiedOf[i] != null || bestPos[i] < 0 || bestScore[i] >= selfScore[i]) {
                        continue;
                    }
                    Cand hitCand = cands.get(bestPos[i]);
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("file", pop.get(i).png().getFileName().toString());
                    w.put("hitState", hitCand.ctx.state());
                    w.put("hitAction", hitCand.ctx.action());
                    w.put("hitScore", 100.0 - bestScore[i]);
                    w.put("selfScore", 100.0 - selfScore[i]);
                    wrongs.add(w);
                }
                for (int i = 0; i < n; i++) {
                    if (selfOf[i] != j || tiedOf[i] == null) {
                        continue;
                    }
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("file", pop.get(i).png().getFileName().toString());
                    w.put("selfScore", 100.0 - selfScore[i]);
                    w.put("hitScore", 100.0 - bestScore[i]);
                    w.put("tiedWith", tiedOf[i]);
                    tieds.add(w);
                }
                wrongs.sort(Comparator.comparing((Map<String, Object> w) -> String.valueOf(w.get("hitState")))
                        .thenComparing(w -> String.valueOf(w.get("file"))));
                tieds.sort(Comparator.comparing((Map<String, Object> w) -> String.valueOf(w.get("tiedWith")))
                        .thenComparing(w -> String.valueOf(w.get("file"))));
            }
            row.put("wrong", wrongs);
            row.put("wrongCount", wrongs.size());
            row.put("tied", tieds);
            row.put("tiedCount", tieds.size());
            rows.add(row);
        }
        rows.sort((a, b) -> String.valueOf(a.get("state")).compareTo(String.valueOf(b.get("state"))));

        // selfValidSum 是产物有效分类样本的不匹配占比之和 → B = 匹配占比均值 = 100 − 均值
        Double A = totalValid == 0 ? null : 100.0 - selfValidSum / totalValid;   // 自分类平均匹配值（按可匹配样本计）
        Double B = cands.isEmpty() ? null : genOk * 100.0 / cands.size();        // 生成成功率（按分类计）
        Double OTHER = otherCnt[0] == 0 ? null : 100.0 - otherSum[0] / otherCnt[0];   // 其它分类平均匹配值
        int decidedValid = totalValid - tieValid;   // D 的分母 = 能给出结果的可匹配样本（命中 + 误判），不含无法区分的
        Double D = decidedValid == 0 ? null : hitValid * 100.0 / decidedValid;   // 匹配正确率（按能给出结果的样本计）
        Double E = totalValid == 0 ? null : tieValid * 100.0 / totalValid;       // 无法区分率（按全部可匹配样本计）
        if (r.superseded) {
            return null;   // 中途被作废：这一轮统计不完整（有样本被跳过），直接丢弃
        }
        return new KindStat(kind, fp, System.currentTimeMillis(), (int) (System.currentTimeMillis() - t0),
                A, B, OTHER, D, E, tieValid, genOk, cands.size(), totalValid, totalSamples, cands.size(), rows);
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
