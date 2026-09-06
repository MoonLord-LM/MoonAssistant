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
 * 会生成 14 张对照图——7 张基础合成图（same 交集 / max 多数 / avg 均值 /
 * major8·avg8·major32·avg32 块降采样）加每张基础图对应的 -unique 独有区图
 * （same-unique / max-unique / avg-unique / major8-unique·avg8-unique·major32-unique·avg32-unique），
 * 它们把同一分类的多张样本合成成一张张“该状态的代表画面”。每张 -unique 独有区图在该基础图基础上
 * 剔除了「其它分类同 kind 基础图同位同色」的像素——那些区域对区分本分类没有贡献，只在独有区上统计
 * 差异相当于专门考察“该状态独有的画面区域”，能进一步拉开相近分类的差距；基础图覆盖整幅画面、
 * 独有区图只盯着本分类独占的区域，两者互补。产物必须 14 张齐全该分类才参与识别
 * （-unique 是正式维度，不再是可选项）。
 *
 * <p>匹配口径：14 张图按「代表色的来源」分两套逐点判据——
 * <ul>
 * <li><b>交集/多数类</b>（same 交集、max 多数、major8/major32 多数块图及各自的 -unique 独有区图，
 * 共 8 张）：对照颜色来自样本中<b>真实出现过的像素</b>（交集 = 多数样本一致的原始色，多数 = 出现最多的原始色）。
 * 工程靠 resize 让窗口截图与标注逐像素对齐，同一状态重截的画面应当<b>逐像素完全重现</b>该画面——
 * 因此要求两像素 R、G、B 三通道差值<b>全部为 0</b>（差值 > 0 即判「不匹配」）；</li>
 * <li><b>均值类</b>（avg 均值、avg8/avg32 均值块图及各自的 -unique 独有区图，共 6 张）：
 * 对照颜色是样本的<b>逐通道平均值</b>，真实画面几乎不可能恰好等于平均色，逐像素完全一致没有意义——
 * 因此采用逐通道容差：三通道差值都不超过 {@code execute.rgb-dist-threshold}（默认 255/3 = 85）才判「匹配」，
 * 任一通道差 > 阈值即「不匹配」。</li>
 * </ul>
 * 14 张图分别与当前画面（须与产物同分辨率：靠 resize 对齐，比对不做图片缩放）按同一口径逐点判定
 * 并统计各自的「不匹配点占比」（0~100，
 * 透明像素不参与统计：基础图的非公共区、独有区图的非独有区都被剔除）。把各图的占比当作
 * 该分类在对应比对维度上的分值，按加权平均聚合为差异度
 * = (独有交集图占比×50 + 交集图占比×30 + 其余 12 张图占比的平均×20) / 100
 * （交集图与独有交集图锁定样本的公共稳定区 / 独占核心区，权重最高；
 * 其余 12 张作为整体参考、合计只占 20%，避免个别维明显差异盖过核心区域的强匹配；
 * 个别图产物缺失或无法有效读出时，该维按 0 计——没有可判“不一致”的像素就不增加分歧；
 * 独有区图没有任何独有像素时同样按 0 计（有 ≥1 个独有点就按实测占比计）；
 * 只有全部 14 张都不可比时才剔除该目录）。
 * 不同分类按各自的差异度比较，最小者即为最近似分类。
 * 执行以最近似分类为准、不设识别阈值门槛：差异度仅作参考展示（越小表示画面越接近该分类的样本）。
 *
 * <p>坐标可靠性：整个工程靠 resize 把窗口/截图尺寸强制对齐到与标注样本一致，summary 产物
 * 与当前画面天然等尺寸、像素一一对应，因此命中分类记录在 info.json 里的点击坐标可直接用于执行动作。
 *
 * <p>性能：当前画面每轮只预计算一次抽样/块压缩序列（全幅 {@link #FULL_SAMPLE_STEP} 抽样 +
 * 8·32 多数/均值块压缩），全部分类共用同一份结果，不再对同一画面重复做整帧压缩；产物像素缓存带 mtime/size 失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameClassifier {

    /** 全幅对照图（same/max/avg 及其 -unique 版）比对时的抽样步长：横、纵都每隔 4 像素取 1 点（约 1/16）。 */
    private static final int FULL_SAMPLE_STEP = 4;

    /** 交集/多数类维度用的「完全一致」判据阈值：distThr=0 ⇔ 仅当 R/G/B 三通道差都为 0 才算匹配
     *  （匹配口径统一为「三通道差都 ≤ distThr」）。 */
    private static final int EXACT_MATCH_DIST = 0;

    /** 逐像素「完全一致」判据的维度（交集/多数类，颜色来自样本真实像素）：same / max(major) /
     *  major8 / major32 及各自的 -unique 独有区图，共 8 张；其余 6 张均值类（avg/avg8/avg32 及各自
     *  -unique，颜色是样本平均色）走逐通道容差 {@code execute.rgb-dist-threshold}。 */
    private static final Set<String> EXACT_KINDS = Set.of(
            "same", "same-unique",
            "max", "max-unique",
            "major8", "major8-unique",
            "major32", "major32-unique");

    /** 各产物维度参与比对时的压缩口径：{块边长, 1=块内多数 / 0=块内均值}；全幅图不压缩。
     *  -unique 独有区图与各自基础图同口径。 */
    private static final Map<String, int[]> KIND_DOWN = Map.ofEntries(
            Map.entry("same", new int[]{1, 0}),
            Map.entry("same-unique", new int[]{1, 0}),
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
            Map.entry("avg32-unique", new int[]{32, 0}));

    /** 全部对照图（7 基础 + 7 -unique 独有区）的文件名（与 ThinkService 产物保持一致）。 */
    private static final Map<String, String> KIND_FILE = Map.ofEntries(
            Map.entry("same", "same.png"),
            Map.entry("same-unique", "same-unique.png"),
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
            Map.entry("avg32-unique", "avg32-unique.png"));

    /** -unique 独有区图维度：无任何独有像素时按差异度 0 计（不存在可判“不匹配”的独有点）。 */
    private static final Set<String> UNIQUE_KINDS = Set.of(
            "same-unique", "max-unique", "avg-unique",
            "major8-unique", "avg8-unique", "major32-unique", "avg32-unique");

    /** 14 张对照图的固定展示/比较顺序（每张基础图紧跟其 -unique 独有区图：交集 → 多数 → 均值 → 8 块 → 32 块）。 */
    private static final List<String> KIND_ORDER = List.of(
            "same", "same-unique",
            "max", "max-unique",
            "avg", "avg-unique",
            "major8", "major8-unique",
            "avg8", "avg8-unique",
            "major32", "major32-unique",
            "avg32", "avg32-unique");

    /** 参与比对必需的全部 14 张产物对照图：任一缺失 = 该分类产物未齐，整目录跳过不参与识别
     *  （差异度按交集/独有交集/其余平均加权聚合，缺图无法保证口径）。 */
    private static final Set<String> MATCH_CORE_FILES = Set.of(
            "same.png", "same-unique.png",
            "major.png", "major-unique.png",
            "avg.png", "avg-unique.png",
            "major8.png", "major8-unique.png",
            "avg8.png", "avg8-unique.png",
            "major32.png", "major32-unique.png",
            "avg32.png", "avg32-unique.png");

    /** 产物像素缓存的 LRU 容量上限。每轮识别会顺序扫描 summary/ 下全部产物（当前 48 个分类 × 14 张 ≈ 672 张），
     *  容量小于产物总数时 LRU 会把本轮扫过的图挤出，导致下一轮重新 ImageIO.read 解码数百张 PNG（一次约数十~百毫秒/张），
     *  识别被拖到十几秒。容量需明显大于典型产物总量才无需每轮重解码；2000 张 ≈ 当前产物数（约 672）的 3 倍余量，
     *  按全幅抽样 + 块图降采样后估算全部常驻约数百 MB 堆，注意与 JVM -Xmx 匹配。 */
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
        final boolean sampled; // true = px 已是按 FULL_SAMPLE_STEP 抽样的像素序列
        final int[] px;

        CachedPx(long lastModified, long size, int w, int h, boolean sampled, int[] px) {
            this.lastModified = lastModified;
            this.size = size;
            this.w = w;
            this.h = h;
            this.sampled = sampled;
            this.px = px;
        }
    }

    /** 当前画面的一次性预计算产物：本帧各分类共用的画面侧抽样/块压缩序列。
     *  画面固定，而同一帧原先被每个分类目录各自重复压缩（6 次全幅抽样 + 8 次整帧块压缩，
     *  多数块图每块还带 HashMap 装箱统计）——是识别耗时的最大来源；现在每轮只算一次、全部目录复用。 */
    private static final class FrameWork {
        final int fw, fh;
        final int[] sampled;    // 全幅 FULL_SAMPLE_STEP 抽样（same/max/avg 及各自 -unique 六张共用）
        final int[] b8M, b8A;   // 8×8 块多数/均值（major8·avg8 及各自 -unique 共用）
        final int[] b32M, b32A; // 32×32 块多数/均值（major32·avg32 及各自 -unique 共用）
        final int wb8, hb8, wb32, hb32;

        FrameWork(int[] full, int w, int h) {
            fw = w;
            fh = h;
            sampled = sampleStep(full, w, h, FULL_SAMPLE_STEP);
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
        /** 最近似分类的「14 图不匹配占比加权平均」百分比（0~100，越低越像）。 */
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
        FrameWork work = null;                                          // 画面侧抽样/块压缩每轮只算一次、全部分类复用（惰性：扫到首个可比目录才建）

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
            if (!artifactsComplete(dir)) {
                continue;   // 14 张对照图不齐（未做汇总分析 / -unique 独有区图尚未补齐）无法比较
            }
            Number wObj = info.get("width") instanceof Number n ? n : null;
            Number hObj = info.get("height") instanceof Number n ? n : null;
            if (wObj == null || hObj == null || wObj.intValue() <= 0 || hObj.intValue() <= 0) {
                continue;
            }
            int w = wObj.intValue();
            int h = hObj.intValue();

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
                        compareKind(work, ref, kind));
                scores.put(kind, ks);
                if (ks.score() >= 0) {
                    kindCount++;
                }
            }
            if (kindCount == 0) {
                continue;   // 14 张对照图全部无法有效读出 → 该目录不参与（正常产物不会出现）
            }
            scanned++;
            // 差异度 = (独有交集图×50 + 交集图×30 + 其余 12 张平均×20) / 100：核心区权重最高，个别维高差异被弱化；
            // 无法有效读出的个别图按 0 计（无可用判别像素 = 不产生分歧）
            double diff = weightedDiff(scores);
            GroupBest g = perState.computeIfAbsent(state, s -> new GroupBest());
            g.state = state;
            if (diff < g.diff) {
                g.diff = diff;
                g.dir = dir.getFileName().toString();
                g.kindScores = scores;   // 只保留该分类“最优产物目录”那一轮的 14 图分值
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
     *  (same-unique×50 + same×30 + 其余 12 张平均×20) / 100；无法有效读出的图（-1）按 0 计。 */
    private static double weightedDiff(Map<String, KindScore> scores) {
        double same = 0, uniq = 0, rest = 0;
        int restN = 0;
        for (KindScore ks : scores.values()) {
            double d = ks.score() < 0 ? 0 : ks.score();
            switch (ks.kind()) {
                case "same":
                    same = d;
                    break;
                case "same-unique":
                    uniq = d;
                    break;
                default:
                    rest += d;
                    restN++;
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
     * 把画面预计算序列（{@link FrameWork}：全幅抽样 / 8·32 多数·均值块压缩，全部分类目录共用同一份）与
     * 某张对照图产物（ref 缓存里已是同口径的抽样/压缩序列）逐点比对，返回该图「不匹配点占比」百分比（0~100）。
     * 判据按维度类别分两套：交集/多数类（same/max/major8/major32 及各自的 -unique）对照颜色是样本真实像素，
     * 同一状态画面应能逐像素重现 → <b>逐像素完全一致</b>（R/G/B 三通道差都须为 0）才算匹配；
     * 均值类（avg/avg8/avg32 及各自的 -unique）对照颜色是样本平均值 → 走逐通道容差
     * {@code execute.rgb-dist-threshold}（三通道差都 ≤ 阈值才算匹配）。
     * 产物与画面分辨率不一致（resize 对齐被破坏）直接抛异常；产物口径与该 kind 网格对不上时返回 -1（该图不参与聚合、按 0 计）。
     */
    private double compareKind(FrameWork work, CachedPx ref, String kind) {
        int[] down = KIND_DOWN.get(kind);
        if (down == null || down.length != 2) {
            return -1;
        }
        int block = down[0];
        boolean majority = down[1] == 1;
        int distThr = EXACT_KINDS.contains(kind) ? EXACT_MATCH_DIST
                : executeProperties.getRgbDistThreshold();

        if (block == 1) {
            // 全幅对照图：产物须与画面同分辨率（resize 对齐），双方同用预计算的抽样序列比较
            if (!ref.sampled) {
                return -1;
            }
            if (ref.w != work.fw || ref.h != work.fh) {
                throw new IllegalArgumentException(
                        "画面分辨率 " + work.fw + "x" + work.fh + " 与对照产物 " + ref.w + "x" + ref.h
                                + " 不一致：比对不做图片缩放，请先让窗口/截图对齐到目标分辨率");
            }
            return mismatchPercent(work.sampled, ref.px, distThr, UNIQUE_KINDS.contains(kind));
        }
        // 1/8、1/32 块图：画面按块压缩到与产物同尺度（整块对齐、右侧/底部不足整块忽略；产物本身同一 floor 网格生成）
        if (ref.sampled) {
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

    /* ---------------- 像素抽样 / 块压缩 / 差异判定 ---------------- */

    /** 对像素数组做 step×step 网格抽样（步进取样，与 full 产物缓存同口径）。 */
    private static int[] sampleStep(int[] px, int w, int h, int step) {
        int cw = (w + step - 1) / step;
        int ch = (h + step - 1) / step;
        int[] out = new int[cw * ch];
        int idx = 0;
        for (int y = 0; y < h; y += step) {
            int base = y * w;
            for (int x = 0; x < w; x += step) {
                out[idx++] = px[base + x];
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
     * <p>基础图（same/max/avg/块图，zeroIfEmpty=false）：无有效像素视为不可比返回 -1，
     * 调用侧把 -1 按 0 计（无可用判别像素 = 不产生分歧）。
     * <p>判定采用逐通道口径：三个通道独立比较、必须全部达标才算一致——单一通道的明显色偏
     * 不会被另外两个通道的接近“平均稀释”掉，例如画面整体亮度偏移会让三通道同时越界而被检出。</p>
     */
    private static double mismatchPercent(int[] a, int[] b, int distThr, boolean zeroIfEmpty) {
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }
        // b（参考/产物）透明像素不参与统计；画面 a 恒不透明
        long bad = 0;
        long n = 0;
        if (distThr == 0) {
            // 交集/多数类（完全一致）判据：三通道差都为 0 ⇔ 两像素低 24 位完全相同——不透明点直接整值比较，
            // 免去每点三通道拆位/取差运算（14 张 kind 里 8 张走这条，是最热路径）
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

    /** 读取一张产物图并缓存（全幅图缓存抽样序列；块图缓存整幅小图），解码失败返回 null。 */
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
        boolean isFull = down != null && down.length == 2 && down[0] == 1;   // 全幅 kind（same/max/avg 及各自的 -unique 版）缓存抽样序列
        int[] px;
        if (isFull) {
            int[] all = im.getRGB(0, 0, w, h, null, 0, w);
            px = sampleStep(all, w, h, FULL_SAMPLE_STEP);
        } else {
            px = im.getRGB(0, 0, w, h, null, 0, w);
        }
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

    /** 参与比对的必需全部对照图（14 张：7 基础 + 7 -unique）是否齐全；任一缺失该目录整体跳过。 */
    private static boolean artifactsComplete(Path gdir) {
        for (String f : MATCH_CORE_FILES) {
            if (!Files.isRegularFile(gdir.resolve(f))) {
                return false;
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
