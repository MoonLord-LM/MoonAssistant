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
 * 会生成基础合成图——7 张核心基础图（same90 交集 / max 多数 / avg 均值 /
 * major8·avg8·major32·avg32 块降采样）加每张核心基础图对应的 -unique 独有区图
 * （same90-unique / max-unique / avg-unique / major8-unique·avg8-unique·major32-unique·avg32-unique；
 * 交集图另有 same80/same70/same60 三张展示档，仅目检、不参与识别）；
 * 匹配动作为鼠标点击且坐标有效的分类还会额外生成 2 张<b>点击区交集图</b>
 * click8-same / click32-same——以该分类统一点击坐标为中心、宽高分别取整幅 1/8 与 1/32 的
 * 方框小图（1280×720 → 160×90 与 40×22），交集判定口径与 same90 交集主档一致，聚焦“要点击的位置”。
 * 方框中心固定、不向画幅内收敛：越出画幅边缘的部分按透明像素处理（产物与画面裁口同公式）。
 * 这些图把同一分类的多张样本合成成一张张“该状态的代表画面”。每张 -unique 独有区图在该基础图基础上
 * 剔除了「其它分类同 kind 基础图同位同色」的像素——那些区域对区分本分类没有贡献，只在独有区上统计
 * 差异相当于专门考察“该状态独有的画面区域”，能进一步拉开相近分类的差距；基础图覆盖整幅画面、
 * 独有区图只盯着本分类独占的区域，两者互补。产物必须按该分类适用的维度全部齐全才参与识别
 * （-unique 是正式维度，不再是可选项）：点击动作分类需 14 + 2 = 16 张，无点击坐标的分类为 14 张。
 *
 * <p>匹配口径：对照图按「代表色的来源」分三套逐点判据——
 * <ul>
 * <li><b>交集/多数类</b>（same90 交集、max 多数、major8/major32 多数块图及各自的 -unique 独有区图，
 * 共 8 张）：对照颜色来自样本中<b>真实出现过的像素</b>（交集 = 多数样本一致的原始色，多数 = 出现最多的原始色）。
 * 工程靠 resize 让窗口截图与标注逐像素对齐，同一状态重截的画面应当<b>逐像素完全重现</b>该画面——
 * 因此要求两像素 R、G、B 三通道差值<b>全部为 0</b>（差值 > 0 即判「不匹配」）；</li>
 * <li><b>均值类</b>（avg 均值、avg8/avg32 均值块图及各自的 -unique 独有区图，共 6 张）：
 * 对照颜色是样本的<b>逐通道平均值</b>，真实画面几乎不可能恰好等于平均色，逐像素完全一致没有意义——
 * 因此采用逐通道容差：三通道差值都不超过 {@code execute.rgb-dist-threshold}（默认 255/3 = 85）才判「匹配」，
 * 任一通道差 > 阈值即「不匹配」。</li>
 * <li><b>点击区交集图</b>（click8-same、click32-same，2 张，仅点击动作分类）：
 * 对照颜色同样是样本真实像素（交集口径），判据同交集/多数类（逐像素完全一致）；
 * 产物是点击坐标附近的方框小图，比对时在画面同一坐标位置裁出方框再逐点比较；
 * 框可越出画幅边缘，越界部分在产物与画面两侧都填透明、不参与统计。</li>
 * </ul>
 * 各图分别与当前画面（须与产物同分辨率：靠 resize 对齐，比对不做图片缩放）按同一口径逐点判定
 * 并统计各自的「不匹配点占比」（0~100，
 * 透明像素不参与统计：基础图的非公共区、独有区图的非独有区、点击区图内不一致的像素、
 * 以及点击区框越出画幅的出界点（画面与产物同处都填透明对齐）都被剔除）。
 * 把各图的占比当作该分类在对应比对维度上的分值，按加权平均聚合为差异度
 * = (独有交集图占比×50 + 交集图占比×30 + 其余可判图占比的平均×20) / 100
 * （交集图与独有交集图锁定样本的公共稳定区 / 独占核心区，权重最高；
 * 「其余图」合计只占 20%，作为整体参考，避免个别维明显差异盖过核心区域的强匹配；
 * 该组点击区交集图也计入其余图成员：有点击坐标的分类是 14 张、无点击坐标的分类是 12 张，
 * 分母按“实际能判出占比”的图计数（产物缺失/无法有效读出的个别图不参与平均——没有可判
 * “不一致”的像素就不增加分歧；独有区图没有任何独有像素时按 0 计，有 ≥1 个独有点就按实测占比计）；
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

    /** 逐像素「完全一致」判据的维度（交集/多数类，颜色来自样本真实像素）：same90 / max(major) /
     *  major8 / major32 及各自的 -unique 独有区图，加点击区交集图 click8-same / click32-same，
     * 共 10 张；其余 6 张均值类（avg/avg8/avg32 及各自 -unique，颜色是样本平均色）
     * 走逐通道容差 {@code execute.rgb-dist-threshold}。 */
    private static final Set<String> EXACT_KINDS = Set.of(
            "same90", "same90-unique",
            "max", "max-unique",
            "major8", "major8-unique",
            "major32", "major32-unique",
            "click8-same", "click32-same");

    /** 各产物维度参与比对时的压缩口径：{块边长, 1=块内多数 / 0=块内均值}；全幅图不压缩
     *  （点击区交集图 click8/32-same 走独立裁剪分支，{1,0} 仅表示“方框内全像素、无块压缩”）。 */
    private static final Map<String, int[]> KIND_DOWN = Map.ofEntries(
            Map.entry("same90", new int[]{1, 0}),
            Map.entry("same90-unique", new int[]{1, 0}),
            Map.entry("max", new int[]{1, 0}),
            Map.entry("max-unique", new int[]{1, 0}),
            Map.entry("avg", new int[]{1, 0}),
            Map.entry("avg-unique", new int[]{1, 0}),
            Map.entry("major8", new int[]{8, 1}),
            Map.entry("major8-unique", new int[]{8, 1}),
            Map.entry("avg8", new int[]{8, 0}),
            Map.entry("avg8-unique", new int[]{8, 0}),
            Map.entry("major32", new int[]{32, 1}),
            Map.entry("major32-unique", new int[]{32, 1}),
            Map.entry("avg32", new int[]{32, 0}),
            Map.entry("avg32-unique", new int[]{32, 0}),
            Map.entry("click8-same", new int[]{1, 0}),
            Map.entry("click32-same", new int[]{1, 0}));

    /** 点击区交集图维度：产物是以该分类统一点击坐标为心的 1/8、1/32 方框小图，不参与 -unique
     *  独有区互比、也不存在独有区版本。比对时按同一坐标在画面上裁出方框再逐点比较。 */
    private static final Set<String> CLICK_CROP_KINDS = Set.of("click8-same", "click32-same");

    /** 识别端使用的对照图（7 核心基础 + 7 -unique 独有区 + 2 点击区交集图，与 ThinkService 产物保持一致；
     *  交集 80/70/60 展示档不参与识别，不在本表）的文件名。 */
    private static final Map<String, String> KIND_FILE = Map.ofEntries(
            Map.entry("same90", "same90.png"),
            Map.entry("same90-unique", "same90-unique.png"),
            Map.entry("max", "major.png"),
            Map.entry("max-unique", "major-unique.png"),
            Map.entry("avg", "avg.png"),
            Map.entry("avg-unique", "avg-unique.png"),
            Map.entry("major8", "major8.png"),
            Map.entry("major8-unique", "major8-unique.png"),
            Map.entry("avg8", "avg8.png"),
            Map.entry("avg8-unique", "avg8-unique.png"),
            Map.entry("major32", "major32.png"),
            Map.entry("major32-unique", "major32-unique.png"),
            Map.entry("avg32", "avg32.png"),
            Map.entry("avg32-unique", "avg32-unique.png"),
            Map.entry("click8-same", "click8-same.png"),
            Map.entry("click32-same", "click32-same.png"));

    /** -unique 独有区图维度：无任何独有像素时按差异度 0 计（不存在可判“不匹配”的独有点）。 */
    private static final Set<String> UNIQUE_KINDS = Set.of(
            "same90-unique", "max-unique", "avg-unique",
            "major8-unique", "avg8-unique", "major32-unique", "avg32-unique");

    /** 对照图的固定展示/比较顺序（每张核心基础图紧跟其 -unique 独有区图：交集主档 → 多数 → 均值 → 8 块 → 32 块
     *  → 点击区交集图：每个分类按其适用维度参与，无点击坐标的分类自然跳过最后 2 个）。 */
    private static final List<String> KIND_ORDER = List.of(
            "same90", "same90-unique",
            "max", "max-unique",
            "avg", "avg-unique",
            "major8", "major8-unique",
            "avg8", "avg8-unique",
            "major32", "major32-unique",
            "avg32", "avg32-unique",
            "click8-same", "click32-same");

    /** 每个分类都必须齐全的 14 张产物对照图：任一缺失 = 该分类产物未齐，整目录跳过不参与识别
     *  （差异度按交集/独有交集/其余平均加权聚合，缺图无法保证口径）。 */
    private static final Set<String> MATCH_CORE_FILES = Set.of(
            "same90.png", "same90-unique.png",
            "major.png", "major-unique.png",
            "avg.png", "avg-unique.png",
            "major8.png", "major8-unique.png",
            "avg8.png", "avg8-unique.png",
            "major32.png", "major32-unique.png",
            "avg32.png", "avg32-unique.png");

    /** 点击动作分类额外必需的 2 张点击区交集图（与 ThinkService 只在有有效坐标时才生成配套）。 */
    private static final Set<String> MATCH_CLICK_FILES = Set.of(
            "click8-same.png", "click32-same.png");

    /** 产物像素缓存 LRU 上限，按「缓存全常驻、免每轮重解码」设计：全幅对照图约 3.7MB/张（1280×720 int[]）、
     *  一组 6 张全幅 ≈22MB，当前 48 组全常驻 ≈1.1GB；上限 2000 ≈ 产物总数（约 700）的 3 倍余量，成组增长
     *  不触发淘汰。缓存随分组数线性吃堆（约 100 组 ≈2.3GB、140 组 ≈3.3GB），与 restart.cmd 的 -Xmx4g 匹配。 */
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
        final boolean isFull; // true = 全幅类产物（same90/max/avg 及各自 -unique、点击区方框图）的整幅像素
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
     *  全幅类比对直接用下方 full 整幅像素（same90/max/avg 及各自 -unique、点击区方框共用同一份）。 */
    private static final class FrameWork {
        final int fw, fh;
        final int[] full;       // 画面全幅像素（全幅类逐点比对 + 点击区交集图按坐标裁方框共用同一份）
        final int[] b8M, b8A;   // 8×8 块多数/均值（major8·avg8 及各自 -unique 共用）
        final int[] b32M, b32A; // 32×32 块多数/均值（major32·avg32 及各自 -unique 共用）
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
            b32M = blockDown(full, w, h, 32, wb32, hb32, true);
            b32A = blockDown(full, w, h, 32, wb32, hb32, false);
        }
    }

    /** 识别输出（内部使用，ExecutionService 负责把它转成对外 Snapshot）。 */
    public static final class Outcome {
        /** 是否已有可参考的最近似分类（有分类参与比对并得出最近似结果即为 true；已不再用差异度阈值区分已识别/未识别）。 */
        public boolean recognized;
        /** 最近似分类标注（无任何可对比目录时为空）。 */
        public String bestState;
        /** 最近似分类的「各适用对照图不匹配占比加权平均」百分比（0~100，越低越像）。 */
        public double bestDiffPercent = Double.NaN;
        /** 最近似分类的汇总产物目录名（summary/<dir>），无最近似分类时 null。 */
        public String bestFile;
        /** 命中分类定义的动作（click / none / other），来自 info.json。 */
        public String action;
        /** 命中分类定义的点击坐标（无点击动作时 null）。 */
        public Integer clickLeft;
        /** 命中分类定义的点击坐标（无点击动作时 null）。 */
        public Integer clickTop;
        /** 实际参与比较（核心对照图齐全且算出有效加权平均差异度）的分类数。 */
        public int scannedSamples;
        /** summary/ 下存在有效分类标注的产物目录数（含核心对照图不全被跳过的）。 */
        public int totalSamples;
        /** 各分类的最近似结果，按差异度（加权平均）升序排列（前几个即“候选分类”）。 */
        public List<Candidate> candidates = new ArrayList<>();
        /** 识别耗时（毫秒）。 */
        public long elapsedMs;
    }

    /** 一张对照图（某个 kind 产物）与该次识别画面的比对明细。score < 0 表示该图未参与差异度聚合（缺失或公共区为空）。 */
    public record KindScore(String kind, String file, int w, int h, double score) {
    }

    /** 一个候选：某分类各对照图占比的加权平均差异度 + 各图各自的分值明细（matchedFile 目录下的产物）。 */
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
            // 差异度 = (独有交集图×50 + 交集图×30 + 其余可判成员平均×20) / 100：核心区权重最高，个别维高差异被弱化；
            // 其余图成员 = 块图 + 独有区图（点击动作分类含两张点击区交集图）；
            // 缺失/无法有效读出的个别图不参与平均（无可用判别像素 = 不产生分歧）
            double diff = weightedDiff(scores);
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

    /** 各图「不匹配点占比」按加权平均聚合为分类差异度（0~100，越小越像）：
     *  (same90-unique×50 + same90×30 + 其余可判成员平均×20) / 100。
     *  「其余图」成员随分类适用维度而定：无点击坐标的分类为 12 张（块图与独有区图），
     *  点击动作分类再加 click8-same / click32-same 两张点击区交集图，共 14 张。
     *  缺失 / 无法有效读出的图（-1）不参与「其余图」平均——没有可判“不一致”的像素就不增加分歧，
     *  避免分母被缺维稀释；same90 / same90-unique 是固定核心维度（产物齐全性已保证存在），仍按 0 计。 */
    private static double weightedDiff(Map<String, KindScore> scores) {
        double same = 0, uniq = 0, rest = 0;
        int restN = 0;
        for (KindScore ks : scores.values()) {
            switch (ks.kind()) {
                case "same90":
                    same = Math.max(0, ks.score());
                    break;
                case "same90-unique":
                    uniq = Math.max(0, ks.score());
                    break;
                default:
                    if (ks.score() >= 0) {
                        rest += ks.score();
                        restN++;
                    }
            }
        }
        return (uniq * 50 + same * 30 + (restN > 0 ? rest / restN : 0) * 20) / 100.0;
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
     * 判据按维度类别分两套：交集/多数类（same90/max/major8/major32/click8-same/click32-same 及各自的
     * -unique）对照颜色是样本真实像素，同一状态画面应能逐像素重现 → <b>逐像素完全一致</b>
     * （R/G/B 三通道差都须为 0）才算匹配；均值类（avg/avg8/avg32 及各自的 -unique）对照颜色是样本平均值
     * → 走逐通道容差 {@code execute.rgb-dist-threshold}（三通道差都 ≤ 阈值才算匹配）。
     * 点击区交集图（click8-same/click32-same）另按分类点击坐标（cx,cy）在画面上裁同一方框比对：
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
        boolean majority = down[1] == 1;
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
            return mismatchPercent(a, ref.px, distThr, false);
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
            return mismatchPercent(work.full, ref.px, distThr, UNIQUE_KINDS.contains(kind));
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
            a = majority ? work.b8M : work.b8A;
        } else if (block == 32) {
            if (ref.w != work.wb32 || ref.h != work.hb32) {
                return -1;
            }
            a = majority ? work.b32M : work.b32A;
        } else {
            return -1;
        }
        // -unique 独有区图（含块图）口径：无独有点该维按 0 计；有独有点（即使很少）也按实测占比计
        return mismatchPercent(a, ref.px, distThr, UNIQUE_KINDS.contains(kind));
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

    /**
     * 两段已对齐像素序列的「不匹配点占比」（0~100）：对每个对应像素分别取 R、G、B 三通道差值的
     * 绝对值，任一通道差 > distThr 判为不匹配点；三通道差都 ≤ distThr 才视为匹配点
     * （distThr = {@link #EXACT_MATCH_DIST} = 0，即「逐像素完全一致」：|Δ| ≤ 0 等价于三通道全部相等；
     * 均值类传 execute.rgb-dist-threshold）。
     * 结果为不匹配点数 / 有效点数 × 100。参考序列（b，即产物）的透明像素
     * （交集图非公共区域 / 独有区图非独有区域）不参与统计。长度不一致时返回 -1。
     * <p>zeroIfEmpty（-unique 独有区图口径）：该图没有任何有效（独有）像素时，不存在可判
     * “不匹配”的点，该维度差异按 0 计（无独有判别区 = 与画面无分歧）；只要读到 ≥1 个有效像素，
     * 就按这些点的实测不匹配占比计，独有区再小也照常计分显示。
     * <p>基础图（same90/max/avg/块图，zeroIfEmpty=false）：无有效像素视为不可比返回 -1，
     * 调用侧把 -1 按 0 计（无可用判别像素 = 不产生分歧）。
     * <p>判定采用逐通道口径：三个通道独立比较、必须全部达标才算一致——单一通道的明显色偏
     * 不会被另外两个通道的接近“平均稀释”掉，例如画面整体亮度偏移会让三通道同时越界而被检出。</p>
     */
    private static double mismatchPercent(int[] a, int[] b, int distThr, boolean zeroIfEmpty) {
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }
        // b（参考/产物）透明像素不参与统计；a 仅在点击区框越出画幅处填透明，与 b 同处透明对齐
        long bad = 0;
        long n = 0;
        if (distThr == 0) {
            // 交集/多数类（完全一致）判据：三通道差都为 0 ⇔ 两像素低 24 位完全相同——不透明点直接整值比较，
            // 免去每点三通道拆位/取差运算（16 张 kind 里 10 张走这条，是最热路径）
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
            // 没有任何可判“不匹配”的有效像素：独有区图为空 → 差异度按 0 计；基础图（公共区全空）→ 不可比
            return zeroIfEmpty ? 0.0 : -1;
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
        boolean isFull = down != null && down.length == 2 && down[0] == 1;   // 全幅 kind（same90/max/avg 及各自 -unique、点击区方框图）
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

    /** 参与比对必需的对照图是否齐全：基础 14 张（7 基础 + 7 -unique）任何分类都必须有；
     *  匹配动作是鼠标点击且坐标有效的分类另需 2 张点击区交集图（与 ThinkService 的生成条件配套）。
     *  任一缺失该目录整体跳过（点击区图文件缺失 = 旧产物未按新规则重算）。 */
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
