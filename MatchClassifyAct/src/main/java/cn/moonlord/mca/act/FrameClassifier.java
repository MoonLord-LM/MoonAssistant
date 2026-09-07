package cn.moonlord.mca.act;

import cn.moonlord.mca.config.ExecuteProperties;
import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 画面识别器（执行模式）：把「当前最新截图」与 summary/ 下每个已汇总分析的分类做像素比对。
 *
 * <p>匹配源是汇总分析产物，而不是 classify/ 的散装样本：每个分类标注（state）经「汇总分析」
 * 会生成基础合成图——14 张基础图：交集五档 same90/80/70/60/50（覆盖 >90%/80%/70%/60%/50%，
 * 代表色是样本中达到该档一致门槛的真实像素）与多数 max / 均值 avg / 去重均值 dedup-avg /
 * major8·avg8·dedup-avg8·major32·avg32·dedup-avg32 块降采样，
 * 每张基础图各带一张 -unique 独有区图（共 14 张，全部参与比对）；
 * 匹配动作为鼠标点击且坐标有效的分类还会额外生成 10 张<b>点击区交集图</b>
 * click8/32-same90/80/70/60/50——以该分类统一点击坐标为中心、宽高分别取整幅 1/8 与 1/32 的
 * 方框小图（1280×720 → 160×90 与 40×22），交集判定口径与同档交集图一致，聚焦“要点击的位置”。
 * 方框中心固定、不向画幅内收敛：越出画幅边缘的部分按透明像素处理（产物与画面裁口同公式）。
 * 这些图把同一分类的多张样本合成成一张张“该状态的代表画面”。每张 -unique 独有区图在该基础图基础上
 * 剔除了「其它分类同 kind 基础图同位同色」的像素——那些区域对区分本分类没有贡献，只在独有区上统计
 * 差异相当于专门考察“该状态独有的画面区域”，能进一步拉开相近分类的差距；基础图覆盖整幅画面、
 * 独有区图只盯着本分类独占的区域，两者互补。产物必须按该分类适用的维度全部齐全才参与识别
 * （交集低档与 -unique 均为正式比对维度，无展示档之分）：无点击坐标分类需 14 基础 + 14 -unique = 28 张，
 * 点击动作分类另需 10 张点击区交集图 = 38 张。
 *
 * <p>匹配口径：对照图按「代表色的来源」分三套逐点判据——
 * <ul>
 * <li><b>交集/多数类</b>（交集五档 same90/80/70/60/50、max 多数、major8/major32 多数块图
 * 及各自的 -unique 独有区图，共 16 张）：对照颜色来自样本中<b>真实出现过的像素</b>
 * （交集 = 达到该档一致门槛的原始色，多数 = 出现最多的原始色）。
 * 工程靠 resize 让窗口截图与标注逐像素对齐，同一状态重截的画面应当<b>逐像素完全重现</b>该画面——
 * 因此要求两像素 R、G、B 三通道差值<b>全部为 0</b>（差值 > 0 即判「不匹配」）；</li>
 * <li><b>均值类</b>（avg 均值、dedup-avg 去重均值、avg8/avg32 与 dedup-avg8/dedup-avg32
 * 块图及各自的 -unique 独有区图，共 12 张）：对照颜色是样本的<b>逐通道平均色</b>
 * （去重均值 = 每个像素 / 块内先把样本里出现过的颜色去重，再对去重后的颜色等权平均，
 * 消除重复采样对平均的加权），真实画面几乎不可能恰好等于平均色，逐像素完全一致没有意义——
 * 因此采用逐通道容差：三通道差值都不超过 {@code execute.rgb-dist-threshold}（默认 255/3 = 85）才判「匹配」，
 * 任一通道差 > 阈值即「不匹配」。</li>
 * <li><b>点击区交集图</b>（click8/32-same90/80/70/60/50，10 张，仅点击动作分类）：
 * 对照颜色同样是样本真实像素（各档交集口径），判据同交集/多数类（逐像素完全一致）；
 * 产物是点击坐标附近的方框小图，比对时在画面同一坐标位置裁出方框再逐点比较；
 * 框可越出画幅边缘，越界部分在产物与画面两侧都填透明、不参与统计。</li>
 * </ul>
 * 各图分别与当前画面（须与产物同分辨率：靠 resize 对齐，比对不做图片缩放）按同一口径逐点判定
 * 并统计各自的「不匹配点占比」（0~100，
 * 透明像素不参与统计：基础图的非公共区、独有区图的非独有区、点击区图内不一致的像素、
 * 以及点击区框越出画幅的出界点（画面与产物同处都填透明对齐）都被剔除）。
 * 把各图的占比当作该分类在对应比对维度上的分值，所有能判出分值的适用图一律等权、
 * 直接算术平均即为分类差异度（含全部交集档及其独有区图，点击动作分类另含 10 张点击区交集图；
 * 独有区图没有任何独有像素时该维判不可比、不参与平均；
 * 缺失 / 无法有效读出的个别图同样不参与平均——没有可判“不一致”的像素就不增加分歧；
 * 只有全部适用的对照图都不可比时才剔除该目录）。
 * 不同分类按各自的差异度比较，最小者即为最近似分类。
 * 执行以最近似分类为准、不设识别阈值门槛：差异度仅作参考展示（越小表示画面越接近该分类的样本）。
 *
 * <p>坐标可靠性：整个工程靠 resize 把窗口/截图尺寸强制对齐到与标注样本一致，summary 产物
 * 与当前画面天然等尺寸、像素一一对应，因此命中分类记录在 info.json 里的点击坐标可直接用于执行动作
 * （点击区交集图的方框也以同一坐标定位，画面与产物裁剪位置天然一致）。
 *
 * <p>性能：画面每轮只预计算一次 8·32 多数/均值块压缩序列，全部分类共用同一份结果；全幅类图
 * 直接与画面整幅像素逐点比较，不再对同一画面重复压缩；产物像素缓存带 mtime/size 失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameClassifier {

    /** 交集/多数类维度用的「完全一致」判据阈值：distThr=0 ⇔ 仅当 R/G/B 三通道差都为 0 才算匹配
     *  （匹配口径统一为「三通道差都 ≤ distThr」）。 */
    private static final int EXACT_MATCH_DIST = 0;

    /** 逐像素「完全一致」判据的维度（交集/多数类，颜色来自样本真实像素）：交集五档 same90/80/70/60/50
     *  max(major) / major8 / major32 及各自的 -unique 独有区图，加点击区交集图 click8/32-same90/80/70/60/50，
     * 共 26 张；其余 12 张均值类（avg/avg8/avg32、dedup-avg/dedup-avg8/dedup-avg32 及各自 -unique，
     * 颜色是样本平均色 / 去重平均色）走逐通道容差 {@code execute.rgb-dist-threshold}。 */
    private static final Set<String> EXACT_KINDS = Set.of(
            "same90", "same90-unique",
            "same80", "same80-unique",
            "same70", "same70-unique",
            "same60", "same60-unique",
            "same50", "same50-unique",
            "max", "max-unique",
            "major8", "major8-unique",
            "major32", "major32-unique",
            "click8-same90", "click8-same80", "click8-same70", "click8-same60", "click8-same50",
            "click32-same90", "click32-same80", "click32-same70", "click32-same60", "click32-same50");

    /** 各产物维度参与比对时的压缩口径：{块边长, 压缩模式}，模式 1=块内多数 / 0=块内均值 / 2=块内去重均值；
     *  全幅图块边长为 1 不压缩（点击区交集图 click8/32-same 走独立裁剪分支）。 */
    private static final Map<String, int[]> KIND_DOWN = Map.ofEntries(
            Map.entry("same90", new int[]{1, 0}),
            Map.entry("same90-unique", new int[]{1, 0}),
            Map.entry("same80", new int[]{1, 0}),
            Map.entry("same80-unique", new int[]{1, 0}),
            Map.entry("same70", new int[]{1, 0}),
            Map.entry("same70-unique", new int[]{1, 0}),
            Map.entry("same60", new int[]{1, 0}),
            Map.entry("same60-unique", new int[]{1, 0}),
            Map.entry("same50", new int[]{1, 0}),
            Map.entry("same50-unique", new int[]{1, 0}),
            Map.entry("max", new int[]{1, 0}),
            Map.entry("max-unique", new int[]{1, 0}),
            Map.entry("avg", new int[]{1, 0}),
            Map.entry("avg-unique", new int[]{1, 0}),
            Map.entry("dedup-avg", new int[]{1, 2}),
            Map.entry("dedup-avg-unique", new int[]{1, 2}),
            Map.entry("major8", new int[]{8, 1}),
            Map.entry("major8-unique", new int[]{8, 1}),
            Map.entry("avg8", new int[]{8, 0}),
            Map.entry("avg8-unique", new int[]{8, 0}),
            Map.entry("dedup-avg8", new int[]{8, 2}),
            Map.entry("dedup-avg8-unique", new int[]{8, 2}),
            Map.entry("major32", new int[]{32, 1}),
            Map.entry("major32-unique", new int[]{32, 1}),
            Map.entry("avg32", new int[]{32, 0}),
            Map.entry("avg32-unique", new int[]{32, 0}),
            Map.entry("dedup-avg32", new int[]{32, 2}),
            Map.entry("dedup-avg32-unique", new int[]{32, 2}),
            Map.entry("click8-same90", new int[]{1, 0}),
            Map.entry("click8-same80", new int[]{1, 0}),
            Map.entry("click8-same70", new int[]{1, 0}),
            Map.entry("click8-same60", new int[]{1, 0}),
            Map.entry("click8-same50", new int[]{1, 0}),
            Map.entry("click32-same90", new int[]{1, 0}),
            Map.entry("click32-same80", new int[]{1, 0}),
            Map.entry("click32-same70", new int[]{1, 0}),
            Map.entry("click32-same60", new int[]{1, 0}),
            Map.entry("click32-same50", new int[]{1, 0}));

    /** 点击区交集图维度：产物是以该分类统一点击坐标为心的 1/8、1/32 方框小图 × 交集五档，
     *  不参与 -unique 独有区互比、也不存在独有区版本。比对时按同一坐标在画面上裁出方框再逐点比较。 */
    private static final Set<String> CLICK_CROP_KINDS = Set.of(
            "click8-same90", "click8-same80", "click8-same70", "click8-same60", "click8-same50",
            "click32-same90", "click32-same80", "click32-same70", "click32-same60", "click32-same50");

    /** 识别端使用的对照图（14 基础 + 14 -unique 独有区 + 10 点击区交集图，与 ThinkService 产物保持一致）
     *  的文件名。 */
    private static final Map<String, String> KIND_FILE = Map.ofEntries(
            Map.entry("same90", "same90.png"),
            Map.entry("same90-unique", "same90-unique.png"),
            Map.entry("same80", "same80.png"),
            Map.entry("same80-unique", "same80-unique.png"),
            Map.entry("same70", "same70.png"),
            Map.entry("same70-unique", "same70-unique.png"),
            Map.entry("same60", "same60.png"),
            Map.entry("same60-unique", "same60-unique.png"),
            Map.entry("same50", "same50.png"),
            Map.entry("same50-unique", "same50-unique.png"),
            Map.entry("max", "major.png"),
            Map.entry("max-unique", "major-unique.png"),
            Map.entry("avg", "avg.png"),
            Map.entry("avg-unique", "avg-unique.png"),
            Map.entry("dedup-avg", "dedup-avg.png"),
            Map.entry("dedup-avg-unique", "dedup-avg-unique.png"),
            Map.entry("major8", "major8.png"),
            Map.entry("major8-unique", "major8-unique.png"),
            Map.entry("avg8", "avg8.png"),
            Map.entry("avg8-unique", "avg8-unique.png"),
            Map.entry("dedup-avg8", "dedup-avg8.png"),
            Map.entry("dedup-avg8-unique", "dedup-avg8-unique.png"),
            Map.entry("major32", "major32.png"),
            Map.entry("major32-unique", "major32-unique.png"),
            Map.entry("avg32", "avg32.png"),
            Map.entry("avg32-unique", "avg32-unique.png"),
            Map.entry("dedup-avg32", "dedup-avg32.png"),
            Map.entry("dedup-avg32-unique", "dedup-avg32-unique.png"),
            Map.entry("click8-same90", "click8-same90.png"),
            Map.entry("click8-same80", "click8-same80.png"),
            Map.entry("click8-same70", "click8-same70.png"),
            Map.entry("click8-same60", "click8-same60.png"),
            Map.entry("click8-same50", "click8-same50.png"),
            Map.entry("click32-same90", "click32-same90.png"),
            Map.entry("click32-same80", "click32-same80.png"),
            Map.entry("click32-same70", "click32-same70.png"),
            Map.entry("click32-same60", "click32-same60.png"),
            Map.entry("click32-same50", "click32-same50.png"));

    /** 对照图的固定展示/比较顺序（每张基础图紧跟其 -unique 独有区图：交集五档 → 多数 → 均值 →
     *  去重均值 → 8 块 → 32 块 → 点击区交集图（1/8 → 1/32，各五档）：每个分类按其适用维度参与，
     *  无点击坐标的分类自然跳过最后 10 个）。 */
    private static final List<String> KIND_ORDER = List.of(
            "same90", "same90-unique",
            "same80", "same80-unique",
            "same70", "same70-unique",
            "same60", "same60-unique",
            "same50", "same50-unique",
            "max", "max-unique",
            "avg", "avg-unique",
            "dedup-avg", "dedup-avg-unique",
            "major8", "major8-unique",
            "avg8", "avg8-unique",
            "dedup-avg8", "dedup-avg8-unique",
            "major32", "major32-unique",
            "avg32", "avg32-unique",
            "dedup-avg32", "dedup-avg32-unique",
            "click8-same90", "click8-same80", "click8-same70", "click8-same60", "click8-same50",
            "click32-same90", "click32-same80", "click32-same70", "click32-same60", "click32-same50");

    /** 每个分类都必须齐全的 28 张产物对照图（14 基础 + 14 -unique，含交集五档及全部独有区图）：
     *  任一缺失 = 该分类产物未齐，整目录跳过不参与识别（差异度按各适用对照图等权平均聚合，
     *  缺图无法保证口径；旧目录未重算补齐前不参与，后台重算后恢复）。 */
    private static final Set<String> MATCH_CORE_FILES = Set.of(
            "same90.png", "same90-unique.png",
            "same80.png", "same80-unique.png",
            "same70.png", "same70-unique.png",
            "same60.png", "same60-unique.png",
            "same50.png", "same50-unique.png",
            "major.png", "major-unique.png",
            "avg.png", "avg-unique.png",
            "dedup-avg.png", "dedup-avg-unique.png",
            "major8.png", "major8-unique.png",
            "avg8.png", "avg8-unique.png",
            "dedup-avg8.png", "dedup-avg8-unique.png",
            "major32.png", "major32-unique.png",
            "avg32.png", "avg32-unique.png",
            "dedup-avg32.png", "dedup-avg32-unique.png");

    /** 点击动作分类额外必需的 10 张点击区交集图（1/8、1/32 方框 × 交集五档，与 ThinkService 生成配套）。
     *  任一缺失该点击分类整目录跳过（旧产物未按新规则重算，后台重算后恢复）。 */
    private static final Set<String> MATCH_CLICK_FILES = Set.of(
            "click8-same90.png", "click8-same80.png", "click8-same70.png", "click8-same60.png", "click8-same50.png",
            "click32-same90.png", "click32-same80.png", "click32-same70.png", "click32-same60.png", "click32-same50.png");

    /** 产物像素缓存 LRU 上限，按「缓存全常驻、免每轮重解码」设计：全幅对照图约 3.7MB/张（1280×720 int[]）。
     *  每组现含 26 张全幅图（交集五档及 max/avg/dedup-avg 共 8 基础 + 8 -unique + 点击区交集图 10）
     *  ≈96MB + 12 张 8/32 块图（多数/均值/去重均值及各自 -unique）≈0.1GB——全幅图翻倍后内存随分组
     *  线性上升（50 组全常驻 ≈5GB），已超 restart.cmd 的 -Xmx4g；LRU 上限 2000 只防失控，分组多时
     *  会按需淘汰少量重解码。真溢出请上调 -Xmx 或调低本上限。 */
    private static final int MAX_CACHE = 2000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StoragePaths storage;
    private final ExecuteProperties executeProperties;

    /** 产物像素缓存：key = 产物文件绝对路径。 */
    private final Map<String, CachedPx> cache = new LinkedHashMap<>(64, 0.75f, true);

    private static final class CachedPx {
        final long lastModified;
        final long size;
        final int w;          // 原图宽（全幅 = 产物宽；块图 = 块降采样宽）
        final int h;          // 原图高
        final boolean isFull; // true = 全幅类产物（交集五档及 max/avg/dedup-avg 及各自 -unique、点击区方框图）的整幅像素
        final int[] px;

        CachedPx(long lastModified, long size, int w, int h, boolean isFull, int[] px) {
            this.lastModified = lastModified;
            this.size = size;
            this.w = w;
            this.h = h;
            this.isFull = isFull;
            this.px = px;
        }
    }

    /** 当前画面的一次性预计算产物：本帧各分类共用。同一帧原先被每个分类目录各自重复做整帧块压缩
     *  （多数块图每块还带 HashMap 装箱统计）——是识别耗时的最大来源；现在每轮只算一次、全部目录复用，
     *  全幅类比对直接用下方 full 整幅像素（交集五档及 max/avg/dedup-avg 及各自 -unique、点击区方框共用同一份）。 */
    private static final class FrameWork {
        final int fw, fh;
        final int[] full;       // 画面全幅像素（全幅类逐点比对 + 点击区交集图按坐标裁方框共用同一份）
        final int[] b8M, b8A, b8DA;   // 8×8 块多数/均值/去重均值（major8·avg8·dedup-avg8 及各自 -unique 共用）
        final int[] b32M, b32A, b32DA; // 32×32 块多数/均值/去重均值（major32·avg32·dedup-avg32 及各自 -unique 共用）
        final int wb8, hb8, wb32, hb32;

        FrameWork(int[] full, int w, int h) {
            this.full = full;
            fw = w;
            fh = h;
            wb8 = Math.max(1, w / 8);
            hb8 = Math.max(1, h / 8);
            wb32 = Math.max(1, w / 32);
            hb32 = Math.max(1, h / 32);
            b8M = blockDown(full, w, h, 8, wb8, hb8, true);
            b8A = blockDown(full, w, h, 8, wb8, hb8, false);
            b8DA = blockDedupMean(full, w, h, 8, wb8, hb8);
            b32M = blockDown(full, w, h, 32, wb32, hb32, true);
            b32A = blockDown(full, w, h, 32, wb32, hb32, false);
            b32DA = blockDedupMean(full, w, h, 32, wb32, hb32);
        }
    }

    /** 识别输出（内部使用，ExecutionService 负责把它转成对外 Snapshot）。 */
    public static final class Outcome {
        /** 是否已有可参考的最近似分类（有分类参与比对并得出最近似结果即为 true；已不再用差异度阈值区分已识别/未识别）。 */
        public boolean recognized;
        /** 最近似分类标注（无任何可对比目录时为空）。 */
        public String bestState;
        /** 最近似分类的「各适用对照图不匹配占比等权平均」百分比（0~100，越低越像）。 */
        public double bestDiffPercent = Double.NaN;
        /** 最近似分类的汇总产物目录名（summary/<dir>），无最近似分类时 null。 */
        public String bestFile;
        /** 命中分类定义的动作（click / none / other），来自 info.json。 */
        public String action;
        /** 命中分类定义的点击坐标（无点击动作时 null）。 */
        public Integer clickLeft;
        /** 命中分类定义的点击坐标（无点击动作时 null）。 */
        public Integer clickTop;
        /** 实际参与比较（核心对照图齐全且算出有效差异度）的分类数。 */
        public int scannedSamples;
        /** summary/ 下存在有效分类标注的产物目录数（含核心对照图不全被跳过的）。 */
        public int totalSamples;
        /** 各分类的最近似结果，按差异度（等权平均）升序排列（前几个即“候选分类”）。 */
        public List<Candidate> candidates = new ArrayList<>();
        /** 识别耗时（毫秒）。 */
        public long elapsedMs;
    }

    /** 一张对照图（某个 kind 产物）与该次识别画面的比对明细。score < 0 表示该图未参与差异度聚合（缺失或公共区为空）。 */
    public record KindScore(String kind, String file, int w, int h, double score) {
    }

    /** 一个候选：某分类各对照图占比的等权平均差异度 + 各图各自的分值明细（matchedFile 目录下的产物）。 */
    public record Candidate(String state, double diffPercent, String matchedFile, List<KindScore> kinds) {
    }

    private static final class GroupBest {
        String state;
        double diff = Double.POSITIVE_INFINITY;
        String dir;
        String action;
        Integer clickLeft;
        Integer clickTop;
        /** 成为该分类最优产物目录那一轮的各图分值明细（按 KIND_ORDER 顺序）。 */
        Map<String, KindScore> kindScores = new LinkedHashMap<>();
    }

    /**
     * 对一张截图做状态识别。
     *
     * @param frame 最新一张画面截图（须与产物同分辨率：窗口先经 resize 对齐，不一致时比对直接抛异常）
     * @return 识别输出（无任何可对比分类时 recognized=false、bestState 为空）
     */
    public synchronized Outcome classify(BufferedImage frame) {
        long t0 = System.currentTimeMillis();
        Outcome out = new Outcome();
        if (frame == null) {
            return out;
        }
        Path sum = storage.summary();
        if (!Files.isDirectory(sum)) {
            return out;
        }

        int fw = frame.getWidth();
        int fh = frame.getHeight();
        int[] framePxFull = frame.getRGB(0, 0, fw, fh, null, 0, fw);   // 与产物同分辨率才可逐像素比对（resize 负责对齐）
        FrameWork work = null;                                          // 画面侧块压缩序列每轮只算一次、全部分类复用（惰性：扫到首个可比目录才建）

        Map<String, GroupBest> perState = new LinkedHashMap<>();
        int total = 0;
        int scanned = 0;

        List<Path> dirs;
        try (Stream<Path> ds = Files.list(sum)) {
            dirs = ds.filter(Files::isDirectory)
                     .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                     .toList();
        } catch (IOException e) {
            log.debug("枚举 summary/ 失败：{}", e.toString());
            return out;
        }

        for (Path dir : dirs) {
            Map<String, Object> info = readInfo(dir);
            String state = info.get("state") == null ? null : String.valueOf(info.get("state")).trim();
            if (state == null || state.isEmpty()) {
                continue;   // 还没有有效分类标注的目录不参与识别
            }
            total++;
            if (!artifactsComplete(dir, info)) {
                continue;   // 该分类适用的对照图不齐（未做汇总分析 / -unique 独有区图或点击区交集图尚未补齐）无法比较
            }
            Number wObj = info.get("width") instanceof Number n ? n : null;
            Number hObj = info.get("height") instanceof Number n ? n : null;
            if (wObj == null || hObj == null || wObj.intValue() <= 0 || hObj.intValue() <= 0) {
                continue;
            }
            int w = wObj.intValue();
            int h = hObj.intValue();
            // 点击动作分类才有点击区交集图维度（产物齐全性已按 info 校验过）：点击坐标用于在画面上
            // 定位同一方框裁剪比对；无坐标的分类点击区图文件不存在，自然按缺失处理
            boolean clickAct = "click".equals(String.valueOf(info.get("action")));
            Integer cl = intOf(info.get("clickLeft"));
            Integer ct = intOf(info.get("clickTop"));
            int ccx = clickAct && cl != null ? cl : -1;
            int ccy = clickAct && ct != null ? ct : -1;

            if (work == null) {
                work = new FrameWork(framePxFull, fw, fh);
            }
            int kindCount = 0;
            Map<String, KindScore> scores = new LinkedHashMap<>();   // 该目录各对照图的分值（缺失/不可比 = -1）
            for (String kind : KIND_ORDER) {
                String file = KIND_FILE.get(kind);
                CachedPx ref = loadCached(dir.resolve(file), kind);
                KindScore ks = (ref == null) ? new KindScore(kind, file, 0, 0, -1)
                        : new KindScore(kind, file, ref.w, ref.h,
                        compareKind(work, ref, kind, ccx, ccy));
                scores.put(kind, ks);
                if (ks.score() >= 0) {
                    kindCount++;
                }
            }
            if (kindCount == 0) {
                continue;   // 该分类适用的对照图全部无法有效读出 → 该目录不参与（正常产物不会出现）
            }
            scanned++;
            // 差异度 = 所有能判出分值（score ≥ 0）的适用对照图「不匹配点占比」的等权算术平均；
            // 缺失/无法有效读出的个别图不参与平均（无可用判别像素 = 不产生分歧）
            double diff = avgDiff(scores);
            GroupBest g = perState.computeIfAbsent(state, s -> new GroupBest());
            g.state = state;
            if (diff < g.diff) {
                g.diff = diff;
                g.dir = dir.getFileName().toString();
                g.kindScores = scores;   // 只保留该分类“最优产物目录”那一轮的各图分值
                g.action = info.get("action") == null ? null : String.valueOf(info.get("action"));
                g.clickLeft = intOf(info.get("clickLeft"));
                g.clickTop = intOf(info.get("clickTop"));
            }
        }

        List<GroupBest> sorted = new ArrayList<>(perState.values());
        sorted.sort((a, b) -> Double.compare(a.diff, b.diff));
        List<GroupBest> topFew = sorted.size() > 6 ? new ArrayList<>(sorted.subList(0, 6)) : sorted;
        out.candidates = candidatesOf(topFew);

        out.scannedSamples = scanned;
        out.totalSamples = total;
        if (!sorted.isEmpty()) {
            GroupBest top = sorted.get(0);
            out.bestState = top.state;
            out.bestDiffPercent = top.diff;
            out.bestFile = top.dir;
            out.action = top.action;
            out.clickLeft = top.clickLeft;
            out.clickTop = top.clickTop;
            // 已去掉识别阈值门槛：只要有可比的最近似分类即视为可用（差异度仅作展示，不再判已识别/未识别）
            out.recognized = true;
        }
        out.elapsedMs = System.currentTimeMillis() - t0;
        if (log.isDebugEnabled()) {
            log.debug("画面识别完成：recognized={}, best={} ({}) diff={}%, compared={}/{}",
                    out.recognized, out.bestState, out.bestFile, String.format("%.2f", out.bestDiffPercent),
                    out.scannedSamples, out.totalSamples);
        }
        return out;
    }

    /** 各图「不匹配点占比」的等权算术平均即分类差异度（0~100，越小越像）：所有能判出分值
     *  （score ≥ 0）的适用对照图一律参与（含全部交集档及其独有区图、max/avg/块图族与其 -unique，
     *  点击动作分类另含 10 张点击区交集图），无点击坐标的分类天然少点击区十图。缺失 /
     *  无法有效读出的图（-1），以及没有任何有效像素的图（基础图公共区全空 / 独有区图无独有点），
     *  一律不参与平均——没有可判“不一致”的像素就不增加分歧，避免分母被缺维稀释。 */
    private static double avgDiff(Map<String, KindScore> scores) {
        double sum = 0;
        int n = 0;
        for (KindScore ks : scores.values()) {
            if (ks.score() >= 0) {
                sum += ks.score();
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    private static List<Candidate> candidatesOf(List<GroupBest> sorted) {
        List<Candidate> out = new ArrayList<>(sorted.size());
        for (GroupBest g : sorted) {
            List<KindScore> kinds = new ArrayList<>(KIND_ORDER.size());
            for (String kind : KIND_ORDER) {
                KindScore ks = g.kindScores.get(kind);
                if (ks != null) {
                    kinds.add(ks);
                }
            }
            out.add(new Candidate(g.state, g.diff, g.dir, kinds));
        }
        return out;
    }

    /**
     * 把画面预计算序列（{@link FrameWork}：整幅像素 / 8·32 多数·均值块压缩，全部分类目录共用同一份）与
     * 某张对照图产物（ref 缓存里已是全像素序列）逐点比对，返回该图「不匹配点占比」百分比（0~100）。
     * 判据按维度类别分两套：交集/多数类（交集五档 same90/80/70/60/50、max/major8/major32
     * 及各自的 -unique、以及全部 10 张点击区交集图 click8/32-same90/80/70/60/50）对照颜色是
     * 样本真实像素，同一状态画面应能逐像素重现 → <b>逐像素完全一致</b>
     * （R/G/B 三通道差都须为 0）才算匹配；均值类（avg/avg8/avg32、dedup-avg/dedup-avg8/dedup-avg32
     * 及各自的 -unique）对照颜色是样本平均 / 去重平均色 → 走逐通道容差
     * {@code execute.rgb-dist-threshold}（三通道差都 ≤ 阈值才算匹配）。
     * 点击区交集图另按分类点击坐标（cx,cy）在画面上裁同一方框比对：
     * 框可越出画幅，出界点填透明、与产物同坐标的透明对齐（方框几何与生成端 clickBox 同公式）；
     * cx/cy < 0（无坐标，正常不会出现）返回 -1。产物与画面分辨率不一致（resize 对齐被破坏）直接抛异常；
     * 产物口径与该 kind 网格对不上时返回 -1（该图不参与聚合）。
     */
    private double compareKind(FrameWork work, CachedPx ref, String kind, int cx, int cy) {
        int[] down = KIND_DOWN.get(kind);
        if (down == null || down.length != 2) {
            return -1;
        }
        int block = down[0];
        int mode = down[1];   // 1=块内多数 / 0=块内均值 / 2=块内去重均值
        int distThr = EXACT_KINDS.contains(kind) ? EXACT_MATCH_DIST
                : executeProperties.getRgbDistThreshold();

        if (CLICK_CROP_KINDS.contains(kind)) {
            // 点击区交集图：产物是点击坐标附近整幅 1/8 或 1/32 的方框小图（缓存全像素）。画面按同一
            // 坐标裁出同尺寸方框逐点比较；框中心不收敛、可越出画幅，出界点画面填透明与产物同坐标的
            // 透明对齐（产物不足 90% 一致的像素也透明），透明点都不参与统计
            if (!ref.isFull || cx < 0 || cy < 0) {
                return -1;
            }
            int bw = ref.w, bh = ref.h;
            if (bw <= 0 || bh <= 0 || bw > work.fw || bh > work.fh) {
                return -1;
            }
            int x0 = cx - bw / 2;
            int y0 = cy - bh / 2;
            int[] a = cropPixels(work.full, work.fw, work.fh, x0, y0, bw, bh);
            return mismatchPercent(a, ref.px, distThr);
        }
        if (block == 1) {
            // 全幅对照图：产物须与画面同分辨率（resize 对齐），双方直接用整幅像素逐点比较
            if (!ref.isFull) {
                return -1;
            }
            if (ref.w != work.fw || ref.h != work.fh) {
                throw new IllegalArgumentException(
                        "画面分辨率 " + work.fw + "x" + work.fh + " 与对照产物 " + ref.w + "x" + ref.h
                                + " 不一致：比对不做图片缩放，请先让窗口/截图对齐到目标分辨率");
            }
            return mismatchPercent(work.full, ref.px, distThr);
        }
        // 1/8、1/32 块图：画面按块压缩到与产物同尺度（整块对齐、右侧/底部不足整块忽略；产物本身同一 floor 网格生成）
        if (ref.isFull) {
            return -1;
        }
        int[] a;
        if (block == 8) {
            if (ref.w != work.wb8 || ref.h != work.hb8) {
                return -1;
            }
            a = mode == 1 ? work.b8M : mode == 2 ? work.b8DA : work.b8A;
        } else if (block == 32) {
            if (ref.w != work.wb32 || ref.h != work.hb32) {
                return -1;
            }
            a = mode == 1 ? work.b32M : mode == 2 ? work.b32DA : work.b32A;
        } else {
            return -1;
        }
        // 独有区图（含块图）口径：无独有点与基础图公共区全空一样判不可比（-1）、不参与聚合；有独有点（即使很少）按实测占比计
        return mismatchPercent(a, ref.px, distThr);
    }

    /* ---------------- 像素裁剪 / 块压缩 / 差异判定 ---------------- */

    /** 从全幅画面像素中裁出以 (x0,y0) 为左上角、bw×bh 的方框（点击区交集图比对用：逐像素、不抽样）。
     *  x0/y0 可为负、框右/下缘也可越出画面：越界点填透明 0x00000000，与产物端越界透明对齐。 */
    private static int[] cropPixels(int[] px, int w, int h, int x0, int y0, int bw, int bh) {
        int[] out = new int[bw * bh];
        int idx = 0;
        for (int y = 0; y < bh; y++) {
            int sy = y0 + y;
            boolean rowIn = sy >= 0 && sy < h;
            int base = rowIn ? sy * w : 0;
            for (int x = 0; x < bw; x++) {
                int sx = x0 + x;
                out[idx++] = (rowIn && sx >= 0 && sx < w) ? px[base + sx] : 0x00000000;
            }
        }
        return out;
    }

    /** 对像素数组做 block×block 块压缩（多数=块内覆盖最多的颜色 / 均值=块内 R/G/B 平均）。 */
    private static int[] blockDown(int[] px, int w, int h, int block, int wb, int hb, boolean majority) {
        int[] out = new int[wb * hb];
        if (majority) {
            for (int oy = 0; oy < hb; oy++) {
                int rowY = oy * block;
                for (int ox = 0; ox < wb; ox++) {
                    int colX = ox * block;
                    java.util.HashMap<Integer, Integer> freq = new java.util.HashMap<>();
                    int bestCnt = 0, bestColor = 0;
                    for (int dy = 0; dy < block; dy++) {
                        int base = (rowY + dy) * w + colX;
                        for (int dx = 0; dx < block; dx++) {
                            int c = px[base + dx];
                            int cnt = freq.merge(c, 1, Integer::sum);
                            if (cnt > bestCnt) {
                                bestCnt = cnt;
                                bestColor = c;
                            }
                        }
                    }
                    out[oy * wb + ox] = bestColor | 0xff000000;
                }
            }
        } else {
            int total = block * block;
            for (int oy = 0; oy < hb; oy++) {
                int rowY = oy * block;
                for (int ox = 0; ox < wb; ox++) {
                    int colX = ox * block;
                    long sr = 0, sg = 0, sb = 0;
                    for (int dy = 0; dy < block; dy++) {
                        int base = (rowY + dy) * w + colX;
                        for (int dx = 0; dx < block; dx++) {
                            int c = px[base + dx];
                            sr += (c >> 16) & 0xff;
                            sg += (c >> 8) & 0xff;
                            sb += c & 0xff;
                        }
                    }
                    out[oy * wb + ox] = 0xff000000
                            | (int) ((sr + total / 2) / total) << 16
                            | (int) ((sg + total / 2) / total) << 8
                            | (int) ((sb + total / 2) / total);
                }
            }
        }
        return out;
    }

    /** 对像素数组做 block×block 块压缩的「去重均值」：块内出现过的不同颜色（忽略 alpha、低 24 位相同视为同色）
     *  先整块收集去重，再对去重后的颜色集合逐通道等权平均——与产物端 dedup-avg 系列生成口径一致
     *  （dedup-avg8 / dedup-avg32 及各自 -unique 的画面侧压缩）。 */
    private static int[] blockDedupMean(int[] px, int w, int h, int block, int wb, int hb) {
        int[] out = new int[wb * hb];
        for (int oy = 0; oy < hb; oy++) {
            int rowY = oy * block;
            for (int ox = 0; ox < wb; ox++) {
                int colX = ox * block;
                java.util.HashSet<Integer> seen = new java.util.HashSet<>();
                for (int dy = 0; dy < block; dy++) {
                    int base = (rowY + dy) * w + colX;
                    for (int dx = 0; dx < block; dx++) {
                        seen.add(px[base + dx] & 0xffffff);
                    }
                }
                if (seen.isEmpty()) {
                    continue;
                }
                long sr = 0, sg = 0, sb = 0;
                for (int c : seen) {
                    sr += (c >> 16) & 0xff;
                    sg += (c >> 8) & 0xff;
                    sb += c & 0xff;
                }
                int cn = seen.size();
                out[oy * wb + ox] = 0xff000000
                        | (int) ((sr + cn / 2) / cn) << 16
                        | (int) ((sg + cn / 2) / cn) << 8
                        | (int) ((sb + cn / 2) / cn);
            }
        }
        return out;
    }

    /**
     * 两段已对齐像素序列的「不匹配点占比」（0~100）：对每个对应像素分别取 R、G、B 三通道差值的
     * 绝对值，任一通道差 > distThr 判为不匹配点；三通道差都 ≤ distThr 才视为匹配点
     * （distThr = {@link #EXACT_MATCH_DIST} = 0，即「逐像素完全一致」：|Δ| ≤ 0 等价于三通道全部相等；
     * 均值类传 execute.rgb-dist-threshold）。
     * 结果为不匹配点数 / 有效点数 × 100。参考序列（b，即产物）的透明像素
     * （交集图非公共区域 / 独有区图非独有区域）不参与统计；只要读到 ≥1 个有效像素就按实测
     * 占比计分，判别区再小也照常计分显示。长度不一致或没有任何有效像素
     * （基础图公共区全空 / 独有区图无独有点）时返回 -1——调用侧视该维不可比、跳过不参与聚合。
     * <p>判定采用逐通道口径：三个通道独立比较、必须全部达标才算一致——单一通道的明显色偏
     * 不会被另外两个通道的接近“平均稀释”掉，例如画面整体亮度偏移会让三通道同时越界而被检出。</p>
     */
    private static double mismatchPercent(int[] a, int[] b, int distThr) {
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }
        // b（参考/产物）透明像素不参与统计；a 仅在点击区框越出画幅处填透明，与 b 同处透明对齐
        long bad = 0;
        long n = 0;
        if (distThr == 0) {
            // 交集/多数类（完全一致）判据：三通道差都为 0 ⇔ 两像素低 24 位完全相同——不透明点直接整值比较，
            // 免去每点三通道拆位/取差运算（38 张 kind 里 26 张 exact 走这条，是最热路径）
            for (int i = 0; i < a.length; i++) {
                int cb = b[i];
                if (((cb >>> 24) & 0xff) == 0) {
                    continue;
                }
                if ((a[i] & 0xffffff) != (cb & 0xffffff)) {
                    bad++;
                }
                n++;
            }
        } else {
            // 均值类判据：R/G/B 三通道分别算差值，任一通道差绝对值 > distThr 即判「不匹配」；
            // 三通道差都 ≤ distThr 才算匹配（无平方/开方开销）
            for (int i = 0; i < a.length; i++) {
                int cb = b[i];
                if (((cb >>> 24) & 0xff) == 0) {
                    continue;
                }
                int ca = a[i];
                long dr = ((ca >> 16) & 0xff) - ((cb >> 16) & 0xff);
                long dg = ((ca >> 8) & 0xff) - ((cb >> 8) & 0xff);
                long db = (ca & 0xff) - (cb & 0xff);
                if (Math.abs(dr) > distThr || Math.abs(dg) > distThr || Math.abs(db) > distThr) {
                    bad++;
                }
                n++;
            }
        }
        if (n == 0) {
            // 没有任何可判“不匹配”的有效像素（基础图公共区全空 / 独有区图无独有点）：判不可比，不参与聚合
            return -1;
        }
        return bad * 100.0 / n;
    }

    /* ---------------- 产物读取与缓存 ---------------- */

    /** 读取一张产物图并缓存解码后的全像素序列（全幅图/点击区方框图与块图一致，均不抽样），解码失败返回 null。 */
    private CachedPx loadCached(Path png, String kind) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(png, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
        String key = png.toAbsolutePath().toString();
        CachedPx hit = cache.get(key);
        if (hit != null && hit.lastModified == attrs.lastModifiedTime().toMillis() && hit.size == attrs.size()) {
            return hit;
        }
        BufferedImage im;
        try {
            im = ImageIO.read(png.toFile());
        } catch (IOException e) {
            cache.remove(key);
            return null;
        }
        if (im == null) {
            cache.remove(key);
            return null;
        }
        int w = im.getWidth();
        int h = im.getHeight();
        int[] down = KIND_DOWN.get(kind);
        boolean isFull = down != null && down.length == 2 && down[0] == 1;   // 全幅 kind（交集五档及 max/avg/dedup-avg 及各自 -unique、点击区方框图）
        int[] px = im.getRGB(0, 0, w, h, null, 0, w);
        CachedPx sp = new CachedPx(attrs.lastModifiedTime().toMillis(), attrs.size(),
                w, h, isFull, px);
        cache.put(key, sp);
        while (cache.size() > MAX_CACHE) {
            Iterator<CachedPx> it = cache.values().iterator();
            it.next();
            it.remove();
        }
        return sp;
    }

    /** 参与比对必需的对照图是否齐全：28 张（14 基础 + 14 -unique，含交集五档）任何分类都必须有；
     *  匹配动作是鼠标点击且坐标有效的分类另需 10 张点击区交集图（与 ThinkService 的生成条件配套）。
     *  任一缺失该目录整体跳过（低档图/点击区图文件缺失 = 旧产物未按新规则重算，后台重算后恢复）。 */
    private static boolean artifactsComplete(Path gdir, Map<String, Object> info) {
        for (String f : MATCH_CORE_FILES) {
            if (!Files.isRegularFile(gdir.resolve(f))) {
                return false;
            }
        }
        boolean clickAct = "click".equals(String.valueOf(info.get("action")));
        Integer cl = intOf(info.get("clickLeft"));
        Integer ct = intOf(info.get("clickTop"));
        if (clickAct && cl != null && ct != null) {
            for (String f : MATCH_CLICK_FILES) {
                if (!Files.isRegularFile(gdir.resolve(f))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 读取产物目录下的 info.json；不存在/损坏返回空 map。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readInfo(Path gdir) {
        Path info = gdir.resolve("info.json");
        if (!Files.isRegularFile(info)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(info.toFile(), LinkedHashMap.class);
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private static Integer intOf(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

}
