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
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 算法调优（第 6 视图）：建立在「特征验证」结果之上，把若干特征（kind 产物）组合成匹配算法并验证分类匹配正确率。
 *
 * <p>内置五种算法，特征集合由验证结果自动组合（见 {@link #buildAlgos}）：
 * <ol>
 * <li><b>单一最佳匹配特征</b>：取「(生成成功率 A − 无法区分率 E) × 匹配正确率 D」最大的一个特征，只用它判定
 * （A 按能不能生成有效产物打折、E 扣掉分不出结果的那部分，再乘 D）；</li>
 * <li><b>单一最佳+100%正确率特征</b>：取上面那个最佳特征，再加全部 D = 100% 的特征；若它们的判定不能覆盖
 * 所有分类，则对每个缺失分类补入「该分类匹配正确率最高」的特征，直到每个分类都至少有一个特征能判定；</li>
 * <li><b>单一最佳+90+%正确率特征</b>：同上的「最佳特征 + 补满」口径，种子换成 D ≥ 90%（含 100%）的全部特征；</li>
 * <li><b>单一最佳+80+%正确率特征</b>：同上，种子换成 D ≥ 80%（含 100%）的全部特征。补不满时给出界面提示；</li>
 * <li><b>单一最佳+70+%正确率特征</b>：同上，种子换成 D ≥ 70%（含 100%）的全部特征。补不满时给出界面提示。</li>
 * </ol>
 *
 * <p>每个特征的基础分 {@code X = B（自分类平均匹配值）− C（其它分类平均匹配值）}；界面权重 Y ∈ [0, 10]，默认 1。
 * 某分类的总分 = Σ「(100 − 不匹配占比) × X × Y」/ Σ「X × Y」（只累加该分类能判定的特征），总分唯一最高的分类
 * 即判定结果，与样本归属分类一致即命中。
 *
 * <p>打平（并列）处理，与特征验证的 E（无法区分率）对齐：
 * <ul>
 * <li>逐样本逐特征先查「该特征在这张样本上是否分不开」——某特征的最高匹配值被 ≥2 个分类并列，即视为该特征
 * 有问题，本样本的加权平均里整张放弃该特征（对该样本全部分类一致移除，避免只改一半造成两套口径）；</li>
 * <li>放弃后重新加权，若最高匹配度仍被 ≥2 个分类并列（或本样本可用特征被全部放弃、无从判定），则给出
 * <b>无法区分</b>的最终结论：既不算命中也不算判错，单独计入 {@code tie}（界面对应「无法区分率」）
 * ——所以它也不进「匹配正确率」的分母；</li>
 * <li>归属分类在本算法参与特征上没有「可判定的产物」（无有效产物、或产物存在但比对不了）的样本整张跳过、
 * 不进分母（与特征验证的「可匹配样本」同口径）。</li>
 * </ul>
 * 一次验证（默认全部算法，也可只重算指定的那几个）给出整体与分类级匹配正确率 + 无法区分率。评价口径与特征验证的 D / E 完全一致：
 * <b>匹配正确率 = 命中 / (可判定样本 − 无法区分) = 命中 / (命中 + 判错)</b>（无法区分既不算命中也不算判错，
 * 不进这个分母，只回答「能给出结果的样本里判对了多少」）；<b>无法区分率 = 无法区分 / 可判定样本</b>
 * （分母含无法区分样本）。与特征验证同理：逐张按需解码算矩阵、用后即释放（常驻缓存回收交给 JVM 的 GC），
 * 但矩阵本身与特征验证共用 {@link VerifyMatrixCache}（{@code summary/verify-matrix.json}）：特征验证刚跑完
 * 就直接在内存里命中，一张都不用重比；跨进程也按「每张原图 / 每个产物自己的大小 / 修改时间」复用，没变过的部分不重算。
 *
 * <p><b>结果完整缓存到 {@code summary/opt-result.json}</b>（与特征验证的 {@code summary/verify.json} 同一套做法）：
 * 一次跑完（无 error）即把全部算法的结果（含逐分类明细）+ 最近一次「自动调整参数」摘要完整落盘。缓存有效性
 * 取决于三点：<b>①已标注（{@code classify/}）、②汇总分析（{@code summary/} 产物）、③特征验证的结果</b> ——
 * 前两者体现为特征验证指纹 {@code fp}，第三者体现为「全部 kind 的验证结果都还是最新的（{@link #ready()}）」
 * 与「算法组合口径没变（{@code sig}）」。三者都没变时进程启动即恢复上次结果（界面「已计算」，无需重算）；
 * 任何一处有变动就把结果标记为过期，界面提示重新计算。
 *
 * <p><b>自动调整参数</b>（{@link #autoStart}）只在「验证所有算法」跑完之后才允许启动：对每个可调的权重 Y 分三轮
 * 各试 {@value #TUNE_TRIALS} 次（精确到 0.001）——第一轮在 [0, {@value #Y_MAX}] 之间整段随机，第二轮按
 * {@value #TUNE_GRID_STEP} ~ {@value #Y_MAX} 递增 {@value #TUNE_GRID_STEP} 的固定网格逐个走一遍（{@value #TUNE_TRIALS} 个值），
 * 第三轮在当前最好值附近按 ±0.001 ~ ±0.1（{@value #TUNE_FINE_STEPS} 档）就近微调；最终留下的就是这
 * {@value #TUNE_TRIALS} × {@value #TUNE_ROUNDS} = 300 次里找到的最优值。只要这次改动让该算法的<b>匹配正确率上升</b>或
 * <b>无法区分率下降</b>就采纳，并把最新权重保存到 {@code classify/opt-weights.json}（可手删，删了回到全 1）。
 * 某个权重这一遍里采纳过更好的值，就再<b>只跑随机轮</b>做追加重试（一遍 {@value #TUNE_RETRY_TRIALS} 个随机值，
 * 最多追加 {@value #TUNE_REPEAT} 遍），避免只试一遍就停在局部最优。
 * 每个算法调完还有一次<b>四舍五入收尾</b>：把整条权重依次按 0.01 → 0.1 → 1 四舍五入后重算一遍，只要匹配正确率与
 * 无法区分率<b>一点没变</b>，就说明多出来的小数位对结果没有影响，直接用四舍五入后的值（最终权重更简单直白）。
 * 另有一份<b>特征选择 + 权重数值快照</b>始终保存到 {@code summary/opt-weights.json}（每次跑完覆写，
 * 供后续功能 / 开发验证直接读取，见 {@link #snapshotFile()}）。
 * <b>单一特征算法的权重固定 1</b>：界面不可编辑、也不参与自动调整——只有一个特征时 Y 在 Σ「X × Y」里被约掉，
 * 改它对加权平均（因而对结果）没有任何影响。评估矩阵与「验证所有算法」共用一套，且与权重无关的部分只算一次
 * （见 {@link AlgoEval}），所以换一组权重评估一次很便宜；全部调完后用最终权重重算一遍，界面直接看到新数值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizeService {

    private final StoragePaths storage;
    private final ClassifyStore classifyStore;
    private final FrameClassifier classifier;
    private final VerifyService verify;
    private final VerifyMatrixCache matrix;

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

    /** 最近一次「自动调整参数」的结果摘要（界面右栏显示改了哪些权重、前后对比、落盘位置）。 */
    private volatile Tune tune;

    /** 已保存的权重（{@link #weightsFile()} 的内容；按文件 mtime 复用，手改文件也立刻生效）。 */
    private volatile Map<String, Saved> saved;
    private volatile long savedMtime = Long.MIN_VALUE;

    /** JSON 读写（权重文件的解析与落盘：键名 / 数值都由它正规转义，避免手写拼接）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 每个权重第一遍每一轮试探的次数（三轮：先整段随机、再走一遍固定网格、最后就近微调，都精确到 0.001）。 */
    private static final int TUNE_TRIALS = 100;

    /** 权重试探的轮数：第一轮在 0~{@value #Y_MAX} 之间整段随机，第二轮按 {@value #TUNE_GRID_STEP} ~ {@value #Y_MAX}
     *  递增 {@value #TUNE_GRID_STEP} 的固定网格逐个走一遍，第三轮在当前最好值附近 ±0.001 ~ ±0.1 就近微调。 */
    private static final int TUNE_ROUNDS = 3;

    /** 就近微调的步长档数：±1 ~ ±{@value #TUNE_FINE_STEPS} 个 0.001（即 ±0.001 ~ ±0.1）。 */
    private static final int TUNE_FINE_STEPS = 100;

    /** 追加重试的随机数个数：第一遍里找到更好的值才跑，且只跑随机轮、随机值个数换成它
     *  （每追加一遍 = {@value} 个 [0, {@value #Y_MAX}] 的随机值，精确到 0.001），网格轮与微调轮不再重复跑。 */
    private static final int TUNE_RETRY_TRIALS = 300;

    /** 追加重试的遍数上限：某个权重这一遍（{@value #TUNE_ROUNDS} 轮各 {@value #TUNE_TRIALS} 次）里找到更好的值就
     *  再随机试 {@value #TUNE_RETRY_TRIALS} 个值，又有改善就继续追加，最多 {@value} 遍
     *  （单个权重最多 {@value #TUNE_TRIALS} × {@value #TUNE_ROUNDS} + {@value} × {@value #TUNE_RETRY_TRIALS} 次）。 */
    private static final int TUNE_REPEAT = 3;

    /** 四舍五入收尾的精度档（依次 0.01 → 0.1 → 1）：每个算法调完把整条权重按档四舍五入后重算一遍，
     *  匹配正确率与无法区分率一点没变就用四舍五入后的值——少的小数位对结果没影响，权重就留更简单直白的那个。 */
    private static final double[] TUNE_SIMPLIFY = {0.01, 0.1, 1.0};

    /** 权重 Y 的步进 / 精度：区间 [0, {@value #Y_MAX}] 内精确到 0.001（界面输入框 step、手输截断、自动调整的随机取值、落盘与回读都按它统一）。 */
    private static final int Y_SCALE = 1000;

    /** 权重 Y 的上限（下限恒 0）：界面输入框 max、手输截断、自动调整的整段随机取值与就近微调的夹取都按它；前端同名常量 {@code OPT_Y_MAX} 必须同值。 */
    private static final double Y_MAX = 10.0;

    /** 第二轮（固定网格轮）的步长：从 {@value} 起每 {@value} 一个值、到 {@value #Y_MAX} 正好 {@value #TUNE_TRIALS} 个（0.1、0.2、…、10）。 */
    private static final double TUNE_GRID_STEP = Y_MAX / TUNE_TRIALS;

    /** 权重文件名（与去重缓存同放 classify/：可手删、随 *.json 一并忽略）。 */
    private static final String WEIGHTS_FILE = "opt-weights.json";

    /** 权重文件格式版本：不认识就整份忽略（下次落盘即改写为新格式）。 */
    private static final int WEIGHTS_VERSION = 1;

    /** 结果缓存文件名（放 summary/，与特征验证的 verify.json 并列：可手删、随 *.json 一并忽略）。 */
    private static final String RESULT_FILE = "opt-result.json";

    /** 特征选择 + 权重数值快照文件名（放 summary/：每次跑完覆写一份最新的，供后续功能读取）。 */
    private static final String SNAPSHOT_FILE = "opt-weights.json";

    /** 缓存 / 快照格式版本：不认识就整份忽略（下次落盘即改写为新格式）。 */
    private static final int CACHE_VERSION = 1;

    /** 是否已尝试从 summary/opt-result.json 恢复（每个进程只试一次）。 */
    private volatile boolean cacheTried;

    /** 本次进程的结果是否来自缓存文件（前端提示「已恢复上次结果」用）。 */
    private volatile boolean cacheRestored;

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
        public final Double e;      // E 无法区分率（%）
        /** 入选依据（自动组合理由，界面展示）。 */
        public final String why;

        Feature(String kind, double x, Double a, Double other, Double b, Double c, Double e, String why) {
            this.kind = kind;
            this.x = x;
            this.a = a;
            this.other = other;
            this.b = b;
            this.c = c;
            this.e = e;
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
        /** 本轮直接复用逐图比对结果的样本张数与整表复用的特征数（界面提示省下了多少重算）。 */
        public volatile int reuseRows;
        public volatile int reuseKinds;
        /** 跨阶段合计张数进度：进度条按它连续推进，不再随阶段切换回零。 */
        public volatile int allDone;
        public volatile int allTotal;
        /** 任务类型：verify = 验证所有算法；tune = 自动调整参数（阶段文案与完成提示都按它区分）。 */
        public volatile String mode = "verify";
        /** 本轮只重算的算法 id（空 = 全部）：改某个权重时只重算它，其余算法沿用上次结果、界面也不标「等待刷新」。 */
        public volatile List<String> only = List.of();
        /** 自动调整参数阶段：当前权重所属特征 kind、本轮名称（随机 / 网格 / 微调）、第几次尝试（共 trials 次）、本次 Y、基线与已找到的最好结果。 */
        public volatile String tuneKind;
        /** 当前权重试到第几遍（0 = 第一遍三轮；这一遍采纳了更好的值就再只跑随机轮追加一遍，最多 {@value #TUNE_REPEAT} 遍）。 */
        public volatile int tunePass;
        public volatile String trialRound;
        public volatile int trial;
        public volatile int trials;
        public volatile Double trialY;
        public volatile Double baseAcc;
        public volatile Double baseTie;
        public volatile Double bestAcc;
        public volatile Double bestTie;
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
        /** 算法结构签名（算法 id + 特征 kind 顺序）：代码里改了算法组合口径时，旧结果即视为过期。 */
        public volatile String sig;
        public volatile List<Map<String, Object>> algos = List.of();
    }

    /** 「自动调整参数」结果摘要（试探了多少权重、采纳了几个、每个算法的前后对比与落盘位置）。 */
    public static class Tune {
        public volatile boolean finished;
        public volatile String error;
        public volatile long costMs;
        /** 试探过的权重个数（每个权重第一遍 {@value #TUNE_ROUNDS} 轮各试 {@value #TUNE_TRIALS} 次，有改善再追加只跑随机的重试）。 */
        public volatile int weights;
        /** 第一遍每轮试探次数（随结果落盘并下发界面：结果卡 / 进度文案都按它显示，不写死数字）。 */
        public volatile int trials = TUNE_TRIALS;
        /** 被采纳（正确率上升或无法区分率下降）并落盘的权重调整次数。 */
        public volatile int improved;
        /** 因找到更好的值而追加的随机重试遍数合计（单个权重最多追加 {@value #TUNE_REPEAT} 遍，每遍 {@value #TUNE_RETRY_TRIALS} 个随机值）。 */
        public volatile int repeats;
        /** 四舍五入收尾里变简单的权重个数（按 {@code TUNE_SIMPLIFY} 三档四舍五入后两率一点没变，就留下更简单的值）。 */
        public volatile int simplified;
        /** 权重文件的落盘路径。 */
        public volatile String file;
        /** 记录时的验证指纹（与当前不一致 = 已标注 / 汇总分析有变动，摘要即过期）。 */
        public volatile String fp;
        /** 记录时的算法结构签名（同 {@link Result#sig}）。 */
        public volatile String sig;
        public volatile List<Map<String, Object>> algos = List.of();
    }

    /** 权重文件里记录的一份算法权重：特征 kind 顺序 + 一一对应的 Y（特征集合变了这条就作废）。 */
    private record Saved(List<String> kinds, List<Double> y) { }

    /** 验证 / 自动调整共用的一次性数据：分类、样本、参与 kind、各 kind 的比对矩阵（跑完即释放）。 */
    private record Env(List<Ctx> groups, List<Smp> samples, List<String> kinds, Map<String, Matrix> mats, String fp) { }

    /** summary/ 下的一个有效分类目录（与特征验证同口径）。 */
    private record Ctx(int idx, String state, String dir, int w, int h,
                       String action, Integer attnLeft, Integer attnTop,
                       Integer actLeft, Integer actTop) {
    }

    /** classify/ 下的一张已标注原图（归属某分类目录）；mtime / size = 原图文件自身的大小与修改时间
     *  （逐图比对结果的缓存键：图被换掉就能识别出来，没换过的那一行直接复用）。 */
    private record Smp(Path png, Ctx own, long mtime, long size) {
    }

    /** 某 kind 在验证结果里的静态指标（用于组合算法，不重复跑比对）。 */
    private record Feat(String kind, Double a, Double b, Double c, Double e, Double other,
                        Map<String, Double> catC, Map<String, Boolean> catValid) {
        double x() {
            return (a == null ? 0.0 : a) - (other == null ? 0.0 : other);
        }

        /** 「单一最佳匹配特征」的选品分 = (A 生成成功率 − E 无法区分率) × D 匹配正确率；三项缺一即不可用（−inf）。 */
        double score() {
            return b == null || e == null || c == null
                    ? Double.NEGATIVE_INFINITY
                    : (b - e) * c;
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

    /** 「自动调整参数」的前置：不在跑任务、验证已完成且结果没过期，并且存在可调的权重（算法特征数 ≥ 2）。 */
    public boolean tunable() {
        ensureCacheLoaded();
        if (running() || !ready()) {
            return false;
        }
        Result res = result;
        if (res == null || !res.finished || res.error != null || res.fp == null
                || !res.fp.equals(verify.fingerprint())) {
            return false;   // 必须先跑完一次「验证所有算法」，且结果对得上当前的样本 / 产物
        }
        if (!sigOf(algorithms()).equals(res.sig)) {
            return false;   // 算法组合口径变了（特征集合换了一套）：旧结果不能作为调整起点
        }
        for (Algo a : algorithms()) {
            if (a.features.size() >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 启动一次「自动调整参数」：逐算法逐权重分三轮各试 {@value #TUNE_TRIALS} 次（精确到 0.001、共 300 次）——先整段随机、
     * 再按 {@value #TUNE_GRID_STEP} ~ {@value #Y_MAX} 递增的固定网格全走一遍、最后在当前最好值附近按 ±0.001 ~ ±0.1 就近微调；
     * 最终留下的即这 300 次里找到的最优值：只要这次改动让该算法的匹配正确率上升或无法区分率下降就采纳并落盘，
     * 随后就这个权重再只跑随机轮重试（一遍 {@value #TUNE_RETRY_TRIALS} 个随机值，最多追加 {@value #TUNE_REPEAT} 遍）；
     * 每个算法调完再按 0.01 → 0.1 → 1 三档做一次四舍五入收尾（两率一点没变就用更简单的值）；
     * 单一特征算法的权重固定 1、不参与调整。
     *
     * @param weightsByAlgo 起点权重（界面上的 Y，key = 算法 id，value 与算法特征顺序一一对应；缺省 = 文件里保存的 / 1）
     * @return 是否成功启动
     */
    public synchronized boolean autoStart(Map<String, List<Double>> weightsByAlgo) {
        if (!tunable()) {
            return false;
        }
        List<Algo> algos = algorithms();
        Run r = new Run();
        r.mode = "tune";
        run = r;
        exec.submit(() -> doTune(r, algos, weightsByAlgo == null ? Map.of() : weightsByAlgo));
        return true;
    }

    /**
     * 启动一次匹配正确率验证：改了某个算法的权重就只重算它（其余算法直接沿用上次结果里的行，数值不会变）。
     *
     * @param weightsByAlgo 界面上的权重 Y（key = 算法 id，value 与算法特征顺序一一对应；缺省 = 1，超出 [0, 10] 截断）
     * @param onlyIds 只重算这些算法 id（空 / null = 全部算法；上次结果里没有可复用行的算法照样算）
     * @return 是否成功启动
     */
    public synchronized boolean start(Map<String, List<Double>> weightsByAlgo, Collection<String> onlyIds) {
        if (running()) {
            return false;
        }
        List<Algo> algos = algorithms();
        if (algos.isEmpty()) {
            return false;
        }
        Run r = new Run();
        r.only = onlyIds == null ? List.of() : onlyIds.stream().filter(s -> s != null && !s.isBlank()).toList();
        run = r;
        exec.submit(() -> doRun(r, algos, weightsByAlgo == null ? Map.of() : weightsByAlgo, r.only));
        return true;
    }

    /** 状态 / 结果（供前端 1 秒轮询）：前置验证情况 + 算法及其特征（X）+ 任务进度 + 最近结果。 */
    public Map<String, Object> status() {
        ensureCacheLoaded();
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
        // 结果从缓存文件恢复（本进程）+ 缓存 / 快照文件名：界面据此提示「已加载上次结果 / 权重快照落在哪」
        out.put("cached", cacheRestored);
        out.put("cacheFile", RESULT_FILE);
        out.put("snapshotFile", SNAPSHOT_FILE);
        // 逐图比对结果缓存（与特征验证共用 summary/verify-matrix.json）：界面提示复用规模与省下的重算
        out.putAll(matrix.status());
        out.put("fp", fp);
        // 当前算法组合口径（为空 = 特征验证结果不齐 → 恢复出来的结果一律算过期）
        String live = sigOf(algorithms());

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
            task.put("reuseRows", r.reuseRows);     // 本轮直接复用逐图比对结果的样本张数
            task.put("reuseKinds", r.reuseKinds);   // 本轮整表复用的特征数
            task.put("mode", r.mode);
            task.put("only", r.only);                // 只重算的算法 id（空 = 全部）：界面把进度与「等待刷新」收敛到它上面
            task.put("tuneKind", r.tuneKind);
            task.put("tunePass", r.tunePass);         // 当前权重试到第几遍（0 起）：找到更好的值就再只跑随机轮重试一遍
            task.put("trialRound", r.trialRound);
            task.put("trial", r.trial);
            task.put("trials", r.trials);
            task.put("trialY", r.trialY);
            task.put("baseAcc", r.baseAcc);
            task.put("baseTie", r.baseTie);
            task.put("bestAcc", r.bestAcc);
            task.put("bestTie", r.bestTie);
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
            // 过期 = 指纹变了（已标注 / 汇总分析有变动）或算法组合口径变了（特征验证结果不齐 / 代码改了算法定义）
            rm.put("stale", staleOf(res.fp, res.sig, fp, live));
            rm.put("algos", res.algos);
            out.put("result", rm);
        }
        // 「自动调整参数」是否可点（前端按钮禁用态）与最近一次调整摘要
        out.put("tunable", tunable());
        Tune t = tune;
        if (t != null) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("finished", t.finished);
            tm.put("error", t.error);
            tm.put("costMs", t.costMs);
            tm.put("weights", t.weights);
            tm.put("trials", t.trials);
            tm.put("improved", t.improved);
            tm.put("repeats", t.repeats);             // 找到更好的值后追加的重跑遍数合计（界面统计行按它交代多花的工夫）
            tm.put("simplified", t.simplified);       // 四舍五入收尾里变简单的权重个数（界面统计行按它交代权重被收尾到什么程度）
            tm.put("file", t.file);
            tm.put("stale", staleOf(t.fp, t.sig, fp, live));
            tm.put("algos", t.algos);
            out.put("tune", tm);
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
            fm.put("e", f.e);
            fm.put("why", f.why);
            fs.add(fm);
        }
        m.put("features", fs);
        // 当前生效的权重（权重文件里保存的那份；单一特征算法恒为 1）：界面用它初始化 / 同步权重输入框
        m.put("weights", savedY(a));
        return m;
    }

    // ---------------------------------------------------------------- 组合算法

    private static final String ALGO1_ID = "algo1";
    private static final String ALGO1_NAME = "单一最佳匹配特征";
    private static final String ALGO1_DESC = "取「(生成成功率 A − 无法区分率 E) × 匹配正确率 D」最大的一个特征，"
            + "只用它的判定结果。";
    private static final String ALGO2_ID = "algo2";
    private static final String ALGO2_NAME = "单一最佳+100%正确率特征";
    private static final String ALGO2_DESC = "取「单一最佳匹配特征」+ 全部匹配正确率 100% 的特征；若它们的判定不能"
            + "覆盖所有分类，则对每个缺失分类补入该分类正确率最高的特征，直到每个分类至少有一个特征能判定。";
    private static final String ALGO3_ID = "algo3";
    private static final String ALGO3_NAME = "单一最佳+90+%正确率特征";
    private static final String ALGO3_DESC = "取「单一最佳匹配特征」+ 全部匹配正确率 ≥ 90%（含 100%）的特征；"
            + "补满口径与「单一最佳+100%正确率特征」完全一致：若它们的判定不能覆盖所有分类，则对每个缺失分类"
            + "补入该分类正确率最高的特征，直到每个分类至少有一个特征能判定。";
    private static final String ALGO4_ID = "algo4";
    private static final String ALGO4_NAME = "单一最佳+80+%正确率特征";
    private static final String ALGO4_DESC = "取「单一最佳匹配特征」+ 全部匹配正确率 ≥ 80%（含 100%）的特征；"
            + "补满口径与「单一最佳+100%正确率特征」完全一致：若它们的判定不能覆盖所有分类，则对每个缺失分类"
            + "补入该分类正确率最高的特征，直到每个分类至少有一个特征能判定。";
    private static final String ALGO5_ID = "algo5";
    private static final String ALGO5_NAME = "单一最佳+70+%正确率特征";
    private static final String ALGO5_DESC = "取「单一最佳匹配特征」+ 全部匹配正确率 ≥ 70%（含 100%）的特征；"
            + "补满口径与「单一最佳+100%正确率特征」完全一致：若它们的判定不能覆盖所有分类，则对每个缺失分类"
            + "补入该分类正确率最高的特征，直到每个分类至少有一个特征能判定。";

    /** 「单一最佳匹配特征」的入选依据文案（后四个算法都把同一个最佳特征作为种子带上）。 */
    private static final String BEST_WHY = "单一最佳匹配特征（(生成成功率 A − 无法区分率 E) × 匹配正确率 D 最大）";

    /** 特征验证结果缺失时同样下发全部算法的骨架（特征为空，前端结果显示「未计算」）。 */
    private static List<Algo> placeholders() {
        return List.of(new Algo(ALGO1_ID, ALGO1_NAME, ALGO1_DESC, null, List.of()),
                new Algo(ALGO2_ID, ALGO2_NAME, ALGO2_DESC, null, List.of()),
                new Algo(ALGO3_ID, ALGO3_NAME, ALGO3_DESC, null, List.of()),
                new Algo(ALGO4_ID, ALGO4_NAME, ALGO4_DESC, null, List.of()),
                new Algo(ALGO5_ID, ALGO5_NAME, ALGO5_DESC, null, List.of()));
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
        return new Feat(st.kind, st.a, st.b, st.c, st.e, st.other, catC, catValid);
    }

    /** 组合五种内置算法（都先把「单一最佳匹配特征」算出来，后四个再按正确率档位往里加种子）。 */
    private List<Algo> buildAlgos(List<Feat> feats, List<String> states) {
        Feat best = bestSingle(feats);
        List<Algo> out = new ArrayList<>();
        out.add(buildBestSingle(best));
        out.add(buildBestSingleWith(feats, states, best, ALGO2_ID, ALGO2_NAME, ALGO2_DESC,
                99.999, Double.POSITIVE_INFINITY, "匹配正确率 100%",
                "除「单一最佳匹配特征」外没有匹配正确率 100% 的特征，其判定已改为按各分类正确率最高的特征逐个补满。"));
        out.add(buildBestSingleWith(feats, states, best, ALGO3_ID, ALGO3_NAME, ALGO3_DESC,
                90.0, Double.POSITIVE_INFINITY, "匹配正确率 ≥90%",
                "除「单一最佳匹配特征」外没有匹配正确率 ≥90% 的特征，其判定已改为按各分类正确率最高的特征逐个补满。"));
        out.add(buildBestSingleWith(feats, states, best, ALGO4_ID, ALGO4_NAME, ALGO4_DESC,
                80.0, Double.POSITIVE_INFINITY, "匹配正确率 ≥80%",
                "除「单一最佳匹配特征」外没有匹配正确率 ≥80% 的特征，其判定已改为按各分类正确率最高的特征逐个补满。"));
        out.add(buildBestSingleWith(feats, states, best, ALGO5_ID, ALGO5_NAME, ALGO5_DESC,
                70.0, Double.POSITIVE_INFINITY, "匹配正确率 ≥70%",
                "除「单一最佳匹配特征」外没有匹配正确率 ≥70% 的特征，其判定已改为按各分类正确率最高的特征逐个补满。"));
        return out;
    }

    /** 算法 1：取 (A 生成成功率 − E 无法区分率) × D 匹配正确率 最大的一个特征，只用它判定。 */
    private Algo buildBestSingle(Feat best) {
        if (best == null) {
            return new Algo(ALGO1_ID, ALGO1_NAME, ALGO1_DESC,
                    "没有任何特征能同时给出生成成功率、无法区分率与匹配正确率，无法组合。", List.of());
        }
        List<Feature> fs = List.of(new Feature(best.kind(), best.x(), best.a(), best.other(),
                best.b(), best.c(), best.e(), BEST_WHY));
        return new Algo(ALGO1_ID, ALGO1_NAME, ALGO1_DESC, null, fs);
    }

    /**
     * 取「单一最佳匹配特征」+「匹配正确率 D ∈ [lo, hi)」的全部特征作种子，再按缺失分类补入该分类正确率最高的
     * 特征，直到分类全覆盖。
     *
     * @param best 单一最佳特征（(A − E) × D 最大者），每个算法都作为种子带上；
     * @param seedWhy 档位种子特征的入选依据文案；@param emptySeedNote 该档位一个种子都没有时的提示
     */
    private Algo buildBestSingleWith(List<Feat> feats, List<String> states, Feat best,
                                     String id, String name, String desc,
                                     double lo, double hi, String seedWhy, String emptySeedNote) {
        Set<String> chosen = new LinkedHashSet<>();
        Map<String, String> why = new LinkedHashMap<>();
        if (best != null) {
            chosen.add(best.kind());
            why.put(best.kind(), BEST_WHY);
        }
        int seed = 0;
        for (Feat f : feats) {
            Double c = f.c();
            if (c == null || c < lo || c >= hi) {
                continue;
            }
            chosen.add(f.kind());
            why.putIfAbsent(f.kind(), seedWhy);
            seed++;
        }
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
                fs.add(new Feature(f.kind(), f.x(), f.a(), f.other(), f.b(), f.c(), f.e(),
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

    /** (A 生成成功率 − E 无法区分率) × D 匹配正确率 最大的特征（算法 1 的选品口径）；一个都算不出来时返回 null。 */
    private Feat bestSingle(List<Feat> feats) {
        Feat best = null;
        for (Feat f : feats) {
            if (best == null || better(f, best)) {
                best = f;
            }
        }
        return best == null || !Double.isFinite(best.score()) ? null : best;
    }

    /** 选「单一最佳」：(A − E) × D 更大者优先；并列依次比 X（区分度）、D、B 更大者。 */
    private boolean better(Feat a, Feat b) {
        int cmp = Double.compare(a.score(), b.score());
        if (cmp != 0) {
            return cmp > 0;
        }
        cmp = Double.compare(a.x(), b.x());
        if (cmp != 0) {
            return cmp > 0;
        }
        cmp = Double.compare(a.c() == null ? -1 : a.c(), b.c() == null ? -1 : b.c());
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

    private void doRun(Run r, List<Algo> algos, Map<String, List<Double>> weightsByAlgo, List<String> onlyIds) {
        // 只重算被改动的算法（onlyIds）：其余算法直接沿用上次结果里的行 —— 同一批样本 / 产物、同一份比对矩阵、
        // 权重又没变，重算只会得到一模一样的数值，省下时间的同时界面也不必把它们标成「等待刷新」
        String sig = sigOf(algos);
        Map<String, Map<String, Object>> prevRows = new HashMap<>();
        Result prev = result;
        if (prev != null && prev.finished && prev.error == null && prev.algos != null
                && prev.fp != null && prev.fp.equals(verify.fingerprint()) && sig.equals(prev.sig)) {
            for (Map<String, Object> row : prev.algos) {
                prevRows.put(String.valueOf(row.get("id")), row);
            }
        }
        Set<String> only = new LinkedHashSet<>(onlyIds == null ? List.of() : onlyIds);
        List<Algo> todo = new ArrayList<>();
        for (Algo a : algos) {
            if (only.isEmpty() || only.contains(a.id) || !prevRows.containsKey(a.id)) {
                todo.add(a);   // 没点名只算谁、或就是被改动的那个、或上次结果里没有它的行（没跑过 / 口径变了）
            }
        }
        if (todo.isEmpty()) {
            // 点名的算法全都可直接复用（权重其实没改 / id 已不存在）：不跑评估，上次结果原样生效
            r.stage = "完成";
            r.finished = true;
            r.endedMs = System.currentTimeMillis();
            r.running = false;
            return;
        }
        Env env = prepare(r, todo);
        if (env == null) {
            return;
        }
        List<Ctx> groups = env.groups();
        List<Smp> samples = env.samples();
        int gn = groups.size();
        int sn = samples.size();
        int baseEval = env.kinds().size() * sn;
        r.allTotal = baseEval + todo.size() * sn;
        r.stage = "算法评估";
        r.total = todo.size();
        r.done = 0;
        r.processed = 0;
        Map<String, AlgoEval> evals = new HashMap<>();
        Map<String, Map<String, Object>> fresh = new HashMap<>();
        long t0 = System.currentTimeMillis();
        for (int ai = 0; ai < todo.size(); ai++) {
            Algo a = todo.get(ai);
            r.cur = a.name;
            r.processed = 0;
            fresh.put(a.id, evalOf(evals, a, env.mats(), gn, sn)
                    .eval(weightsByAlgo.get(a.id), groups, samples, r, baseEval + ai * sn));
            r.done = ai + 1;
            r.processed = sn;
            r.allDone = baseEval + (ai + 1) * sn;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Algo a : algos) {
            Map<String, Object> row = fresh.get(a.id);
            rows.add(row != null ? row : prevRows.get(a.id));   // 行序与算法顺序一致（界面按 id 取行）
        }
        Result res = new Result();
        res.finished = true;
        res.costMs = Math.max(0, System.currentTimeMillis() - t0);
        res.samples = sn;
        res.groups = gn;
        res.fp = env.fp();
        res.sig = sig;
        res.algos = rows;
        result = res;
        // 完整缓存 + 特征选择 / 权重快照：下次启动只要「已标注 / 汇总分析 / 特征验证」都没变就直接复用
        saveCache(res, null);
        saveSnapshot(res.fp, res.sig, algos, rows);
        matrix.save(res.fp);   // 逐图比对结果也落盘（特征验证没跑过时，本次算出来的矩阵同样留给下次复用）

        r.stage = "完成";
        r.finished = true;
        r.endedMs = System.currentTimeMillis();
        r.running = false;
    }

    /**
     * 准备分类、样本与全部参与特征的比对矩阵（「验证所有算法」与「自动调整参数」共用）；失败时已把原因写进 run。
     *
     * @return null = 准备失败（run 里已有错误信息）
     */
    private Env prepare(Run r, List<Algo> algos) {
        // 与特征验证同理：矩阵内按需解码、用后即释放，常驻缓存回收交给 JVM 的 GC，不做手动清理
        List<Ctx> groups;
        List<Smp> samples;
        try {
            groups = scanGroups();
            samples = scanSamples(groups);
        } catch (Exception e) {
            fail(r, "准备数据失败：" + e);
            return null;
        }
        if (groups.isEmpty() || samples.isEmpty()) {
            fail(r, "没有可用的分类产物或已标注样本，无法验证算法。");
            return null;
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
            return null;
        }

        int sn = samples.size();
        Map<String, FrameClassifier.FrameWork> works = new ConcurrentHashMap<>();
        Map<String, Matrix> mats = new HashMap<>();
        // 进度按「张数」回报：阶段一每个特征、阶段二每个算法都要跑全部 sn 张样本，
        // 所以外层项数会长时间不跳（一张矩阵几分钟），进度条改用合计张数连续推进
        r.allTotal = kinds.size() * sn;
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
                return null;
            }
            r.done = ki + 1;
            r.processed = sn;
            r.allDone = (ki + 1) * sn;
        }
        return new Env(groups, samples, kinds, mats, fp);
    }

    /**
     * 自动调整参数：逐算法逐权重分三轮各试 {@value #TUNE_TRIALS} 次（精确到 0.001）——第一轮在 0~{@value #Y_MAX} 之间整段随机，
     * 第二轮按 {@value #TUNE_GRID_STEP} ~ {@value #Y_MAX} 递增 {@value #TUNE_GRID_STEP} 的固定网格逐个走一遍（{@value #TUNE_TRIALS} 个值），
     * 第三轮在第二轮之后定下来的最好值附近按 ±0.001 ~ ±0.1（{@value #TUNE_FINE_STEPS} 档）就近微调（5 个特征的算法第一遍即
     * 5 × {@value #TUNE_TRIALS} × {@value #TUNE_ROUNDS} = 1500 次评分，最终留下的就是这里面最好的那个值）；
     * 只要这次改动让该算法的
     * <b>匹配正确率上升</b>或<b>无法区分率下降</b>就采纳并立刻落盘；「一遍」= 上面这三轮，某个权重的这一遍里采纳过更好的值
     * 就再追加<b>只跑随机轮</b>的重试（一遍 {@value #TUNE_RETRY_TRIALS} 个 [0, {@value #Y_MAX}] 的随机值，
     * 最多追加 {@value #TUNE_REPEAT} 遍）；单一特征算法的权重固定 1、不参与调整。
     * 每个算法调完再做一次<b>四舍五入收尾</b>：按 0.01 → 0.1 → 1 三档把整条权重四舍五入后重算一遍，
     * 匹配正确率与无法区分率一点没变就用更简单的值（小数位对结果没影响就别留着）。
     * 全部调完用最终权重重算一遍全部算法：界面直接看到新数值、新权重（矩阵已就绪，只跑评分）。
     */
    private void doTune(Run r, List<Algo> algos, Map<String, List<Double>> weightsByAlgo) {
        Env env = prepare(r, algos);
        if (env == null) {
            return;
        }
        List<Ctx> groups = env.groups();
        List<Smp> samples = env.samples();
        int gn = groups.size();
        int sn = samples.size();
        int baseEval = env.kinds().size() * sn;
        Map<String, AlgoEval> evals = new HashMap<>();

        // 可调权重 = 特征数 ≥ 2 的算法（单一特征算法的 Y 在加权平均里被约掉，固定 1 不调）
        Map<String, double[]> cur = new LinkedHashMap<>();
        int weightsTotal = 0;
        for (Algo a : algos) {
            cur.put(a.id, startY(a, weightsByAlgo.get(a.id)));
            if (a.features.size() >= 2) {
                weightsTotal += a.features.size();
            }
        }
        if (weightsTotal == 0) {
            fail(r, "全部算法都只有一个（或没有）特征，没有可调整的权重。");
            return;
        }

        long t0 = System.currentTimeMillis();
        r.allTotal = baseEval + (weightsTotal * TUNE_TRIALS * TUNE_ROUNDS + algos.size()) * sn;
        r.allDone = baseEval;
        r.trials = TUNE_TRIALS;
        int offset = baseEval;
        int done = 0;
        int improved = 0;
        int repeats = 0;
        int simplified = 0;   // 四舍五入收尾里变简单的权重个数（两率一点没变才收）
        int retryCost = TUNE_RETRY_TRIALS * sn;   // 追加的一遍只跑随机轮：TUNE_RETRY_TRIALS 个随机值 × 样本张数，追加一遍就多这一份
        Map<String, Saved> savedNow = new LinkedHashMap<>(savedWeights());
        List<Map<String, Object>> summary = new ArrayList<>();
        Random rnd = new Random();
        for (Algo a : algos) {
            AlgoEval ev = evalOf(evals, a, env.mats(), gn, sn);
            double[] y = cur.get(a.id);
            double[] y0 = y.clone();
            r.stage = "自动调整参数";
            r.cur = a.name;
            r.tuneKind = null;
            r.trialRound = null;
            r.trial = 0;
            r.trialY = null;
            r.total = weightsTotal;
            r.processed = 0;
            Map<String, Object> base = ev.eval(ys(y0), groups, samples, r, offset);
            offset += sn;
            double acc = accOf(base.get("accuracy"));
            double tie = tieOf(base.get("tieRate"));
            double acc0 = acc;
            double tie0 = tie;
            r.baseAcc = acc < 0 ? null : round(acc);
            r.baseTie = round(tie);
            r.bestAcc = r.baseAcc;
            r.bestTie = r.baseTie;
            for (int i = 0; a.features.size() >= 2 && i < a.features.size(); i++) {
                r.done = ++done;
                r.tuneKind = a.features.get(i).kind;
                double curY = y[i];   // 起点：第一遍 = 原值（之后 = 上一轮采纳后的值）；本轮采纳即成为下一轮用的「当前最好值」
                // 第一遍 = 三轮各试 TUNE_TRIALS 次：①整段随机（0~Y_MAX）②0.1~Y_MAX 递增 0.1 的固定网格逐个走一遍
                // ③在当前最好值附近 ±0.001 ~ ±0.1 就近微调；第一遍只要采纳过（说明找着了更好的 Y）就只跑随机轮重试：
                // 一遍 = TUNE_RETRY_TRIALS 个随机值（网格轮与微调轮不再重复），又有改善就继续追加，最多追加 TUNE_REPEAT 遍
                for (int pass = 0; ; pass++) {
                    r.tunePass = pass;
                    boolean adopted = false;
                    int rounds = pass == 0 ? TUNE_ROUNDS : 1;                       // 追加的遍只跑随机轮
                    for (int k = 0; k < rounds; k++) {
                        int round = pass == 0 ? k : 0;                              // 追加的遍按随机轮处理
                        int trials = pass == 0 ? TUNE_TRIALS : TUNE_RETRY_TRIALS;   // 追加的遍换成 TUNE_RETRY_TRIALS 个随机值
                        r.trials = trials;
                        r.trialRound = round == 0 ? "随机" : (round == 1 ? "网格" : "微调");
                        r.trial = 0;
                        r.trialY = null;
                        double pickY = Double.NaN;
                        double pickAcc = 0;
                        double pickTie = 0;
                        for (int t = 1; t <= trials; t++) {
                            double cand;
                            if (round == 0) {
                                cand = rnd.nextInt((int) (Y_MAX * Y_SCALE) + 1) / (double) Y_SCALE;   // [0, 10] 整段随机，精确到 0.001
                            } else if (round == 1) {
                                cand = roundY(TUNE_GRID_STEP * t);                     // 0.1 ~ 10 递增 0.1（固定网格，一个一个走完）
                            } else {
                                int step = 1 + rnd.nextInt(TUNE_FINE_STEPS);          // 1~100 档
                                double delta = step / (double) Y_SCALE * (rnd.nextBoolean() ? 1 : -1);
                                cand = roundY(clampY(curY + delta));                   // 在当前最好值附近 ±0.001 ~ ±0.1，精确到 0.001
                            }
                            y[i] = cand;
                            r.trial = t;
                            r.trialY = cand;
                            r.processed = 0;
                            Map<String, Object> rr = ev.eval(ys(y), groups, samples, r, offset);
                            offset += sn;
                            double ca = accOf(rr.get("accuracy"));
                            double ct = tieOf(rr.get("tieRate"));
                            // 采纳口径（用户指定）：正确率上升、或无法区分率下降；本轮候选里取「正确率更高、再比无法区分率更低」的那个
                            if (ca <= acc + 1e-9 && ct >= tie - 1e-9) {
                                continue;
                            }
                            if (Double.isNaN(pickY) || ca > pickAcc + 1e-9
                                    || (Math.abs(ca - pickAcc) <= 1e-9 && ct < pickTie)) {
                                pickY = cand;
                                pickAcc = ca;
                                pickTie = ct;
                            }
                        }
                        if (Double.isNaN(pickY)) {
                            y[i] = curY;   // 本轮没能改善：保持本轮起点
                        } else {
                            y[i] = pickY;
                            curY = pickY;   // 本轮定下来的「当前最好值」：第一遍的下一轮就在它附近微调，追加的随机轮也只跟它比
                            acc = pickAcc;
                            tie = pickTie;
                            adopted = true;
                            improved++;
                            savedNow.put(a.id, new Saved(kindsOf(a), ys(y)));
                            saveWeights(savedNow);   // 采纳一次落一次盘：中途被杀也只是丢掉最后这一次调整
                            r.bestAcc = acc < 0 ? null : round(acc);
                            r.bestTie = round(tie);
                        }
                    }
                    if (!adopted || pass >= TUNE_REPEAT) {
                        break;   // 这一遍没能再改善（或已追加到上限）：这个权重到此为止
                    }
                    repeats++;
                    r.allTotal += retryCost;   // 追加的这一遍（只跑随机轮）也计入合计张数：进度条按真实工作量推进，不会提前走满
                }
            }
            // 四舍五入收尾：把整条权重依次按 0.01 → 0.1 → 1 四舍五入后重算一遍——只要匹配正确率与无法区分率
            // 一点没变，就说明多出来的小数位对结果没有任何影响，直接用四舍五入后的值（最终权重更简单直白）
            if (a.features.size() >= 2) {
                double[] raw = y.clone();
                r.tuneKind = null;
                r.tunePass = 0;
                r.trialRound = "四舍五入";
                r.trials = TUNE_SIMPLIFY.length;
                r.trial = 0;
                r.trialY = null;
                for (int k = 0; k < TUNE_SIMPLIFY.length; k++) {
                    double step = TUNE_SIMPLIFY[k];
                    double[] cand = new double[y.length];
                    double first = Double.NaN;
                    boolean simpler = false;
                    for (int i = 0; i < y.length; i++) {
                        cand[i] = roundTo(y[i], step);
                        if (Math.abs(cand[i] - y[i]) > EPS) {
                            simpler = true;
                            if (Double.isNaN(first)) {
                                first = cand[i];   // 进度行显示这一档第一个被改的值
                            }
                        }
                    }
                    if (!simpler) {
                        continue;   // 值本来就是这一档（或更粗）：不用试
                    }
                    r.trial = k + 1;
                    r.trialY = first;
                    r.processed = 0;
                    Map<String, Object> rr = ev.eval(ys(cand), groups, samples, r, offset);
                    offset += sn;
                    r.allTotal += sn;   // 收尾也真跑了一遍评分：计入合计张数，进度条不会提前走满
                    // 两率（含「全部无法区分」时的正确率 −1）一模一样才算没影响：这一档照收，否则保留原值
                    if (Math.abs(accOf(rr.get("accuracy")) - acc) <= EPS
                            && Math.abs(tieOf(rr.get("tieRate")) - tie) <= EPS) {
                        System.arraycopy(cand, 0, y, 0, y.length);   // y 就是 cur 里那份，最终评估与落盘都按它
                    }
                }
                r.trialRound = null;
                r.trial = 0;
                r.trialY = null;
                for (int i = 0; i < y.length; i++) {
                    if (Math.abs(y[i] - raw[i]) > EPS) {
                        simplified++;
                    }
                }
            }
            // 无论有没有改善都记下最终权重（文件即「当前生效的权重」；单一特征算法不进文件）
            if (a.features.size() >= 2) {
                savedNow.put(a.id, new Saved(kindsOf(a), ys(y)));
            } else {
                savedNow.remove(a.id);
            }
            summary.add(tuneRow(a, y0, y, acc0, tie0, acc, tie));
        }
        saveWeights(savedNow);

        r.stage = "算法评估";
        r.total = algos.size();
        r.done = 0;
        r.tuneKind = null;
        r.tunePass = 0;
        r.trialRound = null;
        r.trial = 0;
        r.trialY = null;
        r.baseAcc = null;
        r.baseTie = null;
        r.bestAcc = null;
        r.bestTie = null;
        r.processed = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int ai = 0; ai < algos.size(); ai++) {
            Algo a = algos.get(ai);
            r.cur = a.name;
            r.processed = 0;
            rows.add(evalOf(evals, a, env.mats(), gn, sn)
                    .eval(ys(cur.get(a.id)), groups, samples, r, offset));
            offset += sn;
            r.done = ai + 1;
            r.processed = sn;
            r.allDone = Math.min(r.allTotal, offset);
        }
        Result res = new Result();
        res.finished = true;
        res.costMs = Math.max(0, System.currentTimeMillis() - t0);
        res.samples = sn;
        res.groups = gn;
        res.fp = env.fp();
        res.sig = sigOf(algos);
        res.algos = rows;
        result = res;

        Tune t = new Tune();
        t.finished = true;
        t.costMs = res.costMs;
        t.weights = weightsTotal;
        t.trials = TUNE_TRIALS;
        t.improved = improved;
        t.repeats = repeats;
        t.simplified = simplified;
        t.file = weightsFile().toString();
        t.fp = env.fp();
        t.sig = res.sig;
        t.algos = summary;
        tune = t;
        // 完整缓存（结果 + 本次调整摘要）+ 特征选择 / 权重快照
        saveCache(res, t);
        saveSnapshot(res.fp, res.sig, algos, rows);
        matrix.save(res.fp);   // 逐图比对结果也落盘（特征验证没跑过时，本次算出来的矩阵同样留给下次复用）

        r.stage = "完成";
        r.finished = true;
        r.endedMs = System.currentTimeMillis();
        r.running = false;
        log.info("自动调整参数完成：试探 {} 个权重 × {} 轮（随机 / 网格 / 微调）× {} 次（找到更好值后追加 {} 遍只跑随机的重试、每遍 {} 个随机值），采纳 {} 个，四舍五入收尾简化 {} 个，权重写入 {}",
                weightsTotal, TUNE_ROUNDS, TUNE_TRIALS, repeats, TUNE_RETRY_TRIALS, improved, simplified, t.file);
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
        // 列键 = 各分类该 kind 的产物 + info.json 的大小/修改时间（+ 尺寸与裁剪框心）：全对得上说明产物像素
        // 与裁剪都没变，整表（含「该分类有没有有效产物」）直接复用、一个 PNG 都不解码；对不上才整表重算
        List<VerifyMatrixCache.ColKey> cols = new ArrayList<>(gn);
        for (int j = 0; j < gn; j++) {
            Ctx c = groups.get(j);
            Path dir = Path.of(c.dir());
            cols.add(VerifyMatrixCache.colKey(c.state(), c.w(), c.h(), c.attnLeft(), c.attnTop(),
                    c.actLeft(), c.actTop(), dir.resolve(file), dir.resolve(ArtifactKind.FILE_INFO)));
        }
        VerifyMatrixCache.Table cachedTab = matrix.table(kind, cols);
        List<FrameClassifier.CachedPx> arts = new ArrayList<>(gn);
        boolean[] valid = new boolean[gn];
        final VerifyMatrixCache.Table tab;   // 列对得上 = 整表照用，对不上 = 现算一张新表
        if (cachedTab != null) {
            tab = cachedTab;
            for (int j = 0; j < gn; j++) {
                arts.add(null);
                valid[j] = tab.valid(j);   // 与特征验证同一份缓存：产物没变，有效性也照旧
            }
            if (r != null) {
                r.reuseKinds++;
            }
        } else {
            for (int j = 0; j < gn; j++) {
                Path f = Path.of(groups.get(j).dir(), file);
                FrameClassifier.CachedPx art = Files.isRegularFile(f) ? classifier.verifyArtifact(f, kind) : null;
                arts.add(art);
                // 与特征验证 Cand.valid 同一口径：产物存在且至少一个不透明像素才算「该分类生成了有效产物」
                valid[j] = VerifyService.hasPixels(art);
            }
            boolean[] dec = new boolean[gn];
            for (int j = 0; j < gn; j++) {
                dec[j] = arts.get(j) != null;
            }
            tab = matrix.begin(kind, cols, dec, valid);
        }
        double[][] m = new double[sn][gn];
        java.util.concurrent.atomic.AtomicInteger step = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger reuse = new java.util.concurrent.atomic.AtomicInteger();
        // 整表复用路径的产物按需解码槽（列号 → 产物图；解不开的不缓存，下次再看）：
        // 只有「没命中缓存行」的样本才会真的比对，故多数情况下一张产物都不解
        java.util.concurrent.ConcurrentHashMap<Integer, FrameClassifier.CachedPx> lazyArts = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.stream.IntStream.range(0, sn).parallel().forEach(i -> {
            Smp smp = samples.get(i);
            r.sample = smp.png().getFileName().toString();   // 精确到张：界面显示正在比对哪一张
            int n = step.incrementAndGet();
            r.processed = n;                                 // 并行流按「已开始处理」计，收尾正好到 sn
            r.allDone = base + n;
            Ctx own = smp.own();
            String name = smp.png().getFileName().toString();
            // 逐图比对结果缓存：这张原图（文件名 + 大小 + 修改时间 + 所属分类尺寸）算过的整行直接取用
            double[] cached = tab.row(name, smp.mtime(), smp.size(), own.w(), own.h());
            if (cached != null && cached.length == gn) {
                m[i] = cached;   // 连原图都不解码
                reuse.incrementAndGet();
                return;
            }
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
            double[] row = new double[gn];
            for (int j = 0; j < gn; j++) {
                // 整表复用路径没预先解码产物：上次记着「没有产物 / 解不开」的列算 100，
                // 其余按需解码一次（同一列解出来的图全行共用，只有真没命中缓存的行才会走到）
                FrameClassifier.CachedPx art = null;
                if (tab.decoded(j)) {
                    art = arts.get(j) != null ? arts.get(j) : lazyArts.computeIfAbsent(j, k -> {
                        Path fa = Path.of(groups.get(k).dir(), file);
                        return Files.isRegularFile(fa) ? classifier.verifyArtifact(fa, kind) : null;
                    });
                }
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
                row[j] = s;
            }
            m[i] = row;
            tab.fill(name, smp.mtime(), smp.size(), own.w(), own.h(), row);
        });
        if (r != null) {
            r.reuseRows += reuse.get();
        }
        return new Matrix(m, valid);
    }

    /** 懒创建一个算法的评估器（同一算法会被反复评估：与权重无关的预计算必须复用）。 */
    private AlgoEval evalOf(Map<String, AlgoEval> cache, Algo a, Map<String, Matrix> mats, int gn, int sn) {
        AlgoEval ev = cache.get(a.id);
        if (ev == null) {
            ev = new AlgoEval(a, mats, gn, sn);
            cache.put(a.id, ev);
        }
        return ev;
    }

    /**
     * 单个算法的评估器：按某算法 + 权重 Y 逐样本判定——匹配度 = Σ「(100 − 不匹配占比) × X × Y」÷ Σ「X × Y」，
     * 最高的分类即判定结果。与该算法权重无关的部分（矩阵引用 + 每张样本各特征「分不开」的标记）只在构造时算一次，
     * 换一组权重评估时只跑加权评分（「自动调整参数」会对同一算法评估几十上百次）。
     *
     * <p>打平处理：逐特征先剔除「这张样本上分不开」的特征（最高匹配值被 ≥2 个分类并列），再用剩余特征加权；
     * 结果仍并列（或无可用特征）则给「无法区分」结论，既不算命中也不算判错。
     *
     * <p>统计口径与特征验证的 D / E 一致：匹配正确率 = 命中 /（可判定样本 − 无法区分），无法区分不进分子
     * 也不进分母；无法区分率 = 无法区分 / 可判定样本（分母含无法区分）。
     */
    private final class AlgoEval {

        private final Algo algo;
        private final int nf;               // 特征个数
        private final int gn;               // 分类个数
        private final int sn;               // 样本张数
        private final double[][][] mm;      // [特征][样本][分类] 不匹配占比（<0 = 该分类没有此特征的有效产物 / 比不了）
        private final boolean[][] vv;       // [特征][分类] 该分类有没有此特征的有效产物
        private final boolean[][] use;      // [样本][特征] 该特征在这张样本上是否可用（最高匹配值未被 ≥2 个分类并列）

        AlgoEval(Algo algo, Map<String, Matrix> mats, int gn, int sn) {
            this.algo = algo;
            this.gn = gn;
            this.sn = sn;
            this.nf = algo.features.size();
            this.mm = new double[nf][][];
            this.vv = new boolean[nf][];
            for (int f = 0; f < nf; f++) {
                Matrix mx = mats.get(algo.features.get(f).kind);
                mm[f] = mx.m();
                vv[f] = mx.valid();
            }
            this.use = new boolean[sn][nf];
            for (int i = 0; i < sn; i++) {
                for (int f = 0; f < nf; f++) {
                    // 逐特征查「这张样本上该特征是否分不开」：最高匹配值被 ≥2 个分类并列 = 该特征有问题，
                    // 本样本的加权平均里整张放弃它（对该样本全部分类一致移除：只对一边移除等于改成两套口径）
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
                    use[i][f] = at < 2;   // 并列 ≥2 → 该特征分不开这几类，本样本放弃使用它
                }
            }
        }

        /**
         * 按权重评估一次。
         *
         * @param yIn 界面 / 自动调整给出的权重 Y；@param base 本次评估在「合计张数」里的起始偏移
         */
        Map<String, Object> eval(List<Double> yIn, List<Ctx> groups, List<Smp> samples, Run r, int base) {
            long t0 = System.currentTimeMillis();
            double[] w = new double[nf];
            List<Double> yOut = new ArrayList<>(nf);
            for (int f = 0; f < nf; f++) {
                double y = 1.0;
                // 单一特征算法：权重固定 1（只有一个特征时 Y 在 Σ「X × Y」里被约掉，改它对加权平均没有任何影响）
                if (nf > 1 && yIn != null && f < yIn.size() && yIn.get(f) != null) {
                    y = clampY(yIn.get(f));
                }
                yOut.add(roundY(y));
                w[f] = algo.features.get(f).x * y;
            }
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
                // （不算命中也不算判错），否则会凭空拉低匹配正确率
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
                double ownScore = Double.NEGATIVE_INFINITY;
                double bestScore = Double.NEGATIVE_INFINITY;
                int bestCnt = 0;    // 与最高匹配度并列的分类个数
                for (int j = 0; j < gn; j++) {
                    double sumW = 0;       // Σ「X × Y」
                    double sumWV = 0;      // Σ「匹配值 × X × Y」
                    double sumP = 0;       // Σ匹配值（仅 X × Y 全 0 时兜底）
                    int valid = 0;
                    for (int f = 0; f < nf; f++) {
                        if (!use[i][f]) {
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
                // 正确率分母 = 能给出结果的样本（命中 + 判错），无法区分不算错 → 与特征验证的 D 同口径
                int decided = cnt[j] - tie[j];
                row.put("decided", decided);
                row.put("acc", decided > 0 ? round(100.0 * hit[j] / decided) : null);
                row.put("tieRate", cnt[j] > 0 ? round(100.0 * tie[j] / cnt[j]) : null);
                rows.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", algo.id);
            out.put("name", algo.name);
            out.put("weights", yOut);
            out.put("samples", totalCnt);
            out.put("hit", totalHit);
            out.put("tie", totalTie);
            out.put("miss", totalCnt - totalHit - totalTie);
            out.put("skipped", skipped);
            // 正确率分母 = 可判定样本 − 无法区分（无法区分不算错，与特征验证的 D 同口径）；无法区分率的分母仍含它
            int totalDecided = totalCnt - totalTie;
            out.put("decided", totalDecided);
            out.put("accuracy", totalDecided > 0 ? round(100.0 * totalHit / totalDecided) : null);
            out.put("tieRate", totalCnt > 0 ? round(100.0 * totalTie / totalCnt) : null);
            out.put("costMs", Math.max(0, System.currentTimeMillis() - t0));
            out.put("features", algo.features.stream().map(f -> f.kind).toList());
            out.put("rows", rows);
            return out;
        }
    }

    // ---------------------------------------------------------------- 权重文件（自动调整参数）

    /** 权重文件 = classify/opt-weights.json（与 dedup-cache.json 同目录：可手删、随 *.json 一并忽略）。 */
    private Path weightsFile() {
        return storage.classify().resolve(WEIGHTS_FILE);
    }

    /** 文件最后修改时间（不存在 = −1）：用作「权重文件没变就复用内存里那份」的判据。 */
    private static long mtime(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /** 已保存的权重（按文件 mtime 复用内存里的解析结果，手改文件也立刻生效）。 */
    private Map<String, Saved> savedWeights() {
        Path f = weightsFile();
        long mt = mtime(f);
        Map<String, Saved> s = saved;
        if (s != null && mt == savedMtime) {
            return s;
        }
        s = readWeights(f);
        saved = s;
        savedMtime = mt;
        return s;
    }

    /** 读权重文件：读不了 / 结构不对 / 版本不认识就当没有（回到默认全 1），只是不沿用上次的调整结果。 */
    private Map<String, Saved> readWeights(Path f) {
        Map<String, Saved> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            JsonNode root = JSON.readTree(f.toFile());
            if (root == null || root.path("version").asInt() != WEIGHTS_VERSION) {
                log.warn("权重文件 {} 版本不认识（期望 v{}），本次按全 1 处理", f.getFileName(), WEIGHTS_VERSION);
                return out;
            }
            JsonNode algos = root.path("algos");
            for (Iterator<Map.Entry<String, JsonNode>> it = algos.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                List<String> ks = new ArrayList<>();
                for (JsonNode k : e.getValue().path("kinds")) {
                    ks.add(k.asText());
                }
                List<Double> yv = new ArrayList<>();
                for (JsonNode v : e.getValue().path("y")) {
                    yv.add(roundY(clampY(v.asDouble(1.0))));
                }
                if (!ks.isEmpty() && ks.size() == yv.size()) {
                    out.put(e.getKey(), new Saved(ks, yv));
                } else {
                    log.warn("权重文件 {} 里 {} 的 kinds / y 对不上，忽略这一条", f.getFileName(), e.getKey());
                }
            }
        } catch (Exception e) {
            log.warn("权重文件 {} 读取失败，本次按全 1 处理：{}", f, e.toString());
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** 原子落盘（先写 .tmp 再改名：中途被杀不会留下半截文件）；写失败只记日志，不打断任务。 */
    private void saveWeights(Map<String, Saved> m) {
        Path f = weightsFile();
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", WEIGHTS_VERSION);
        Map<String, Object> algos = new LinkedHashMap<>();
        for (Map.Entry<String, Saved> e : m.entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("kinds", e.getValue().kinds());
            one.put("y", e.getValue().y());
            algos.put(e.getKey(), one);
        }
        root.put("algos", algos);
        try {
            Files.createDirectories(f.getParent());   // 还没标注过任何图时 classify/ 可能还不存在
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            saved = m;
            savedMtime = mtime(f);
        } catch (IOException e) {
            log.warn("权重文件写入失败 {}：{}", f, e.toString());
        }
    }

    /** 该算法当前生效的权重（文件里保存的那份；特征集合对不上 / 没记录 = 全 1；单一特征算法恒为 1）。 */
    private List<Double> savedY(Algo a) {
        List<Double> ones = new ArrayList<>(a.features.size());
        for (int i = 0; i < a.features.size(); i++) {
            ones.add(1.0);
        }
        if (a.features.size() <= 1) {
            return ones;
        }
        Saved s = savedWeights().get(a.id);
        if (s == null || !s.kinds().equals(kindsOf(a))) {
            return ones;
        }
        List<Double> out = new ArrayList<>(s.y().size());
        for (Double v : s.y()) {
            out.add(roundY(v == null ? 1.0 : clampY(v)));
        }
        return out;
    }

    /** 自动调整的起点权重：界面传入 > 文件里保存的 > 1；单一特征算法固定 1（不参与调整）。 */
    private double[] startY(Algo a, List<Double> yIn) {
        int nf = a.features.size();
        List<Double> base = yIn;
        if (base == null || base.isEmpty()) {
            Saved s = savedWeights().get(a.id);
            base = (s != null && s.kinds().equals(kindsOf(a))) ? s.y() : null;
        }
        double[] y = new double[nf];
        for (int i = 0; i < nf; i++) {
            Double v = (nf > 1 && base != null && i < base.size()) ? base.get(i) : null;
            y[i] = (v == null) ? 1.0 : roundY(clampY(v));
        }
        return y;
    }

    /** 算法的特征 kind 顺序（权重一一对应；用于判断文件里那一条还能不能用）。 */
    private static List<String> kindsOf(Algo a) {
        return a.features.stream().map(f -> f.kind).toList();
    }

    /** 权重数组 → 列表（收齐到 0.001 精度：Y 的步进就是 0.001）。 */
    private static List<Double> ys(double[] y) {
        List<Double> out = new ArrayList<>(y.length);
        for (double v : y) {
            out.add(roundY(v));
        }
        return out;
    }

    /** 正确率：没算出来（全部样本都无法区分）= −1，便于和候选值比大小。 */
    private static double accOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : -1;
    }

    /** 无法区分率：没有样本时 = 0（无意义，不参与比较）。 */
    private static double tieOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** 一个算法的自动调整摘要（前后权重 + 前后正确率 / 无法区分率；正确率 < 0 表示无从计算，输出 null）。 */
    private Map<String, Object> tuneRow(Algo a, double[] before, double[] after,
                                        double acc0, double tie0, double acc, double tie) {
        List<Double> b = ys(before);
        List<Double> at = ys(after);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("name", a.name);
        m.put("tunable", a.features.size() >= 2);
        m.put("kinds", kindsOf(a));
        m.put("before", b);
        m.put("after", at);
        m.put("changed", !b.equals(at));
        m.put("accBefore", acc0 < 0 ? null : round(acc0));
        m.put("accAfter", acc < 0 ? null : round(acc));
        m.put("tieBefore", round(tie0));
        m.put("tieAfter", round(tie));
        return m;
    }

    // ---------------------------------------------------------------- 落盘缓存与快照（summary/）

    /** 完整结果缓存：summary/opt-result.json（结果 + 逐分类明细 + 最近一次「自动调整参数」摘要）。 */
    public Path resultFile() {
        return storage.summary().resolve(RESULT_FILE);
    }

    /** 特征选择 + 权重数值快照：summary/opt-weights.json（每次跑完覆写，供后续功能 / 开发验证读取）。 */
    public Path snapshotFile() {
        return storage.summary().resolve(SNAPSHOT_FILE);
    }

    /** 算法结构签名（算法 id + 特征 kind 顺序）：判断缓存里的结果、摘要、权重还是不是「同一套算法」。 */
    private static String sigOf(List<Algo> algos) {
        StringBuilder sb = new StringBuilder();
        for (Algo a : algos) {
            sb.append(a.id).append(':').append(String.join(",", kindsOf(a))).append('|');
        }
        return sb.toString();
    }

    /** 结果 / 摘要是否过期：记录时的指纹对不上（已标注 / 汇总分析有变动），或算法组合口径对不上
     *  （特征验证结果不齐 → live 为空；或代码里改了算法定义）。 */
    private static boolean staleOf(String fp, String sig, String curFp, String live) {
        return fp == null || !fp.equals(curFp) || !live.equals(sig == null ? "" : sig);
    }

    /** 首次访问时从 summary/opt-result.json 完整恢复上次结果与「自动调整参数」摘要（幂等，失败只记日志）。
     *  恢复的内容带自己那次计算的指纹 {@code fp} 与算法签名 {@code sig}，与当前不符即判为过期（status().result.stale）。 */
    private synchronized void ensureCacheLoaded() {
        if (cacheTried) {
            return;
        }
        cacheTried = true;
        Path f = resultFile();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(f.toFile());
            if (root == null || root.path("version").asInt() != CACHE_VERSION) {
                log.warn("算法调优缓存 {} 版本不认识（期望 v{}），本次忽略", f.getFileName(), CACHE_VERSION);
                return;
            }
            String sig = root.path("sig").asText("");
            JsonNode rn = root.get("result");
            if (rn != null && rn.isObject()) {
                Result res = new Result();
                res.finished = rn.path("finished").asBoolean(true);
                res.error = textOf(rn, "error");
                res.costMs = rn.path("costMs").asLong(0);
                res.samples = rn.path("samples").asInt(0);
                res.groups = rn.path("groups").asInt(0);
                res.fp = textOf(rn, "fp");
                res.sig = sig;
                res.algos = rowsOf(rn.get("algos"));
                if (!res.algos.isEmpty()) {
                    result = res;
                    cacheRestored = true;
                }
            }
            JsonNode tn = root.get("tune");
            if (tn != null && tn.isObject()) {
                Tune t = new Tune();
                t.finished = tn.path("finished").asBoolean(true);
                t.error = textOf(tn, "error");
                t.costMs = tn.path("costMs").asLong(0);
                t.weights = tn.path("weights").asInt(0);
                t.trials = tn.path("trials").asInt(0);   // 旧缓存没这个字段：0 = 未知，界面按「若干次」描述、不写死数字
                t.improved = tn.path("improved").asInt(0);
                t.repeats = tn.path("repeats").asInt(0);   // 旧缓存没这个字段：0 = 没有追加过重跑
                t.simplified = tn.path("simplified").asInt(0);   // 旧缓存没这个字段：0 = 没做过四舍五入收尾
                t.file = textOf(tn, "file");
                t.fp = textOf(tn, "fp");
                t.sig = sig;
                t.algos = rowsOf(tn.get("algos"));
                tune = t;
            }
            log.info("算法调优结果已从缓存恢复（{}）：{} 个算法，缓存指纹 {}；与当前指纹 / 算法签名一致才显示「已计算」",
                    f, result == null ? 0 : result.algos.size(), root.path("fp").asText(""));
        } catch (Exception e) {
            log.warn("算法调优缓存读取失败 {}：{}", f, e.toString());
        }
    }

    /** 把结果（含逐分类明细）与「自动调整参数」摘要完整落盘（每次跑完调用，先写 .tmp 再原子改名）。 */
    private synchronized void saveCache(Result res, Tune t) {
        if (res == null || res.algos.isEmpty()) {
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", CACHE_VERSION);
        root.put("fp", res.fp);                    // 计算时的特征验证指纹：与当前不一致 = 需重算
        root.put("sig", res.sig);                  // 计算时的算法组合口径
        root.put("savedMs", System.currentTimeMillis());
        root.put("samples", res.samples);
        root.put("groups", res.groups);
        Map<String, Object> rm = new LinkedHashMap<>();
        rm.put("finished", res.finished);
        rm.put("costMs", res.costMs);
        rm.put("error", res.error);
        rm.put("samples", res.samples);
        rm.put("groups", res.groups);
        rm.put("fp", res.fp);
        rm.put("algos", res.algos);
        root.put("result", rm);
        if (t != null) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("finished", t.finished);
            tm.put("error", t.error);
            tm.put("costMs", t.costMs);
            tm.put("weights", t.weights);
            tm.put("trials", t.trials);
            tm.put("improved", t.improved);
            tm.put("repeats", t.repeats);
            tm.put("simplified", t.simplified);
            tm.put("file", t.file);
            tm.put("fp", t.fp);
            tm.put("algos", t.algos);
            root.put("tune", tm);
        }
        writeJson(resultFile(), root, "结果缓存");
    }

    /**
     * 特征选择 + 权重数值快照（每次跑完覆写一份最新的）：每个算法给出
     * <b>特征选择</b>（{@code features[].kind} + 基础分 X / 五种指标 / 入选依据）与
     * <b>权重数值</b>（{@code weights}，与 features 一一对应）以及当时的指标，
     * 供后续功能（例如执行模式）与开发验证直接读取，不必去解析界面状态。
     */
    private synchronized void saveSnapshot(String fp, String sig, List<Algo> algos, List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> one = asMap(r);
            if (one != null) {
                byId.put(String.valueOf(one.get("id")), one);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Algo a : algos) {
            Map<String, Object> r = byId.get(a.id);
            if (r == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id);
            m.put("name", a.name);
            m.put("tunable", a.features.size() >= 2);   // 单一特征算法权重固定 1、不参与自动调整
            m.put("note", a.note);
            List<Map<String, Object>> fs = new ArrayList<>();
            for (Feature f : a.features) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("kind", f.kind);
                fm.put("x", round(f.x));
                fm.put("a", f.a);
                fm.put("b", f.b);
                fm.put("c", f.c);
                fm.put("e", f.e);
                fm.put("other", f.other);
                fm.put("why", f.why);
                fs.add(fm);
            }
            m.put("features", fs);
            m.put("weights", r.get("weights"));
            m.put("accuracy", r.get("accuracy"));
            m.put("tieRate", r.get("tieRate"));
            m.put("samples", r.get("samples"));
            m.put("decided", r.get("decided"));
            m.put("hit", r.get("hit"));
            m.put("tie", r.get("tie"));
            out.add(m);
        }
        if (out.isEmpty()) {
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", CACHE_VERSION);
        root.put("savedMs", System.currentTimeMillis());
        root.put("fp", fp);
        root.put("sig", sig);
        root.put("note", "特征选择 features[].kind 与权重数值 weights 一一对应；每次「验证所有算法 / 自动调整参数」"
                + "跑完由 OptimizeService 覆写，供后续功能直接读取（界面上可调的 Y 另存 classify/opt-weights.json）");
        root.put("algos", out);
        writeJson(snapshotFile(), root, "特征选择与权重快照");
    }

    /** 原子落盘（先写 .tmp 再改名）：写失败只记日志，不打断任务。 */
    private void writeJson(Path f, Map<String, Object> root, String what) {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            Files.createDirectories(f.getParent());
            JSON.writeValue(tmp.toFile(), root);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            log.info("算法调优{}已写入：{}", what, f);
        } catch (IOException e) {
            log.warn("算法调优{}写入失败 {}：{}", what, f, e.toString());
        }
    }

    /** JSON 数组 → List&lt;Map&gt;（逐分类明细等嵌套结构原样取回）。 */
    private static List<Map<String, Object>> rowsOf(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode one : arr) {
                out.add(JSON.convertValue(one, new TypeReference<LinkedHashMap<String, Object>>() { }));
            }
        }
        return out;
    }

    /** JSON 里的可空文本（缺失 / null → null）。 */
    private static String textOf(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
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

    /** 权重 Y 夹到 [0, {@value #Y_MAX}]：界面传入、手输、权重文件回读、自动调整的取值统一走它。 */
    private static double clampY(double v) {
        if (v < 0) {
            return 0;
        }
        if (v > Y_MAX) {
            return Y_MAX;
        }
        return v;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 权重 Y 收齐到 {@value #Y_SCALE} 分之一（= 0.001）：界面步进、自动调整的随机值、落盘与回读统一口径。 */
    private static double roundY(double v) {
        return Math.round(v * Y_SCALE) / (double) Y_SCALE;
    }

    /** 按给定档位四舍五入（四舍五入收尾用的 0.01 / 0.1 / 1）：再收齐到 0.001 并夹在 [0, {@value #Y_MAX}] 内。 */
    private static double roundTo(double v, double step) {
        return roundY(clampY(Math.round(v / step) * step));
    }
}
