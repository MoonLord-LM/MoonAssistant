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
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 画面识别器（执行模式）：把「当前最新截图」与 summary/ 下每个已汇总分析的分类做像素比对。
 *
 * <p>匹配源是汇总分析产物，而不是 classify/ 的散装样本：每个分类标注（state）经「汇总分析」
 * 会生成基础合成图——15 张基础图：交集六档 same100/90/80/70/60/50（100% = 全部样本像素一致，
 * 其余覆盖 >90%/80%/70%/60%/50%，代表色是样本中达到该档一致门槛的真实像素）与多数 max / 均值 avg /
 * 去重均值 dedup-avg / major8·avg8·dedup-avg8·major32·avg32·dedup-avg32 块降采样，
 * 每张基础图各带一张 -unique 独有区图（共 15 张，全部参与比对）；
 * 每个分类定义带两个互相独立的点（注意点：未设 = 屏幕中心；点击点：仅 click 分类有）。在此之上各生成
 * 一套 12 张<b>方框交集图</b>（宽高分别取整幅 1/8 与 1/32 的方框小图，1280×720 → 160×90 与 40×22，
 * 交集判定口径与同档交集图一致，聚焦“要点击的位置 / 要关注的位置”）：
 * <b>注意区交集图</b> attn8/32-same100/90/80/70/60/50 以<b>注意点</b>为框心，<b>每个分类都有</b>；
 * <b>点击区交集图</b> click8/32-same100/90/80/70/60/50 以<b>鼠标点击点</b>为框心，仅 click 分类有。
 * 两套图的裁剪模板/出界透明规则完全一致，只是框心不同，可分别考察“点哪儿”与“看哪儿”。
 * 方框中心固定、不向画幅内收敛：越出画幅边缘的部分按透明像素处理（产物与画面裁口同公式）。
 * 这些图把同一分类的多张样本合成成一张张“该状态的代表画面”。每张 -unique 独有区图在该基础图基础上
 * 剔除了「其它分类同 kind 基础图同位同色」的像素——那些区域对区分本分类没有贡献，只在独有区上统计
 * 差异相当于专门考察“该状态独有的画面区域”，能进一步拉开相近分类的差距；基础图覆盖整幅画面、
 * 独有区图只盯着本分类独占的区域，两者互补。产物必须全部齐全才参与识别
 * （交集低档、-unique 与两套方框交集图均为正式比对维度，无展示档之分）：每个分类 = 15 基础 + 15 -unique
 * + 12 注意区交集图 = 42 张；click 分类另有 12 张点击区交集图 = 54 张
 * （历史旧目录只有 30/42 张，后台重算后自动补齐）。
 *
 * <p>匹配口径：对照图按「代表色的来源」分三套逐点判据——
 * <ul>
 * <li><b>交集/多数类</b>（交集六档 same100/90/80/70/60/50、max 多数、major8/major32 多数块图
 * 及各自的 -unique 独有区图，共 18 张）：对照颜色来自样本中<b>真实出现过的像素</b>
 * （交集 = 达到该档一致门槛的原始色，多数 = 出现最多的原始色）。
 * 工程靠 resize 让窗口截图与标注逐像素对齐，同一状态重截的画面应当<b>逐像素完全重现</b>该画面——
 * 因此要求两像素 R、G、B 三通道差值<b>全部为 0</b>（差值 > 0 即判「不匹配」）；</li>
 * <li><b>均值类</b>（avg 均值、dedup-avg 去重均值、avg8/avg32 与 dedup-avg8/dedup-avg32
 * 块图及各自的 -unique 独有区图，共 12 张）：对照颜色是样本的<b>逐通道平均色</b>
 * （去重均值 = 每个像素 / 块内先把样本里出现过的颜色去重，再对去重后的颜色等权平均，
 * 消除重复采样对平均的加权），真实画面几乎不可能恰好等于平均色，逐像素完全一致没有意义——
 * 因此采用逐通道容差：三通道差值都不超过 {@code execute.rgb-dist-threshold}（默认 255/3 = 85）才判「匹配」，
 * 任一通道差 > 阈值即「不匹配」。</li>
 * <li><b>注意区交集图</b>（attn8/32-same100/90/80/70/60/50，12 张，每个分类都按注意点生成）与
 * <b>点击区交集图</b>（click8/32-same100/90/80/70/60/50，12 张，仅 click 分类按点击点生成）：
 * 对照颜色同样是样本真实像素（各档交集口径），判据同交集/多数类（逐像素完全一致）；
 * 产物是框心坐标附近的方框小图，比对时在画面同一坐标位置（点击区用点击点、注意区用注意点）裁出
 * 方框再逐点比较；框可越出画幅边缘，越界部分在产物与画面两侧都填透明、不参与统计。</li>
 * </ul>
 * 各图分别与当前画面（须与产物同分辨率：靠 resize 对齐，比对不做图片缩放）按同一口径逐点判定
 * 并统计各自的「不匹配点占比」（0~100，
 * 透明像素不参与统计：基础图的非公共区、独有区图的非独有区、方框图内不一致的像素、
 * 以及方框越出画幅的出界点（画面与产物同处都填透明对齐）都被剔除）。
 * 各图的占比先按 A~E 五族做族内等权平均（A 全图交集 12 张权 50 → B 多数族 6 张权 15 →
 * C 均值族 6 张权 10 → D 去重均值族 6 张权 10 → E 方框交集区 24 张权 15），
 * 再按 (50A+15B+10C+10D+15E)/W 加权为分类差异度：W = 适用族的权重之和
 * （参与识别的分类五族产物齐全、W 恒为 100；旧产物缺方框图的分组整体跳过、后台重算后恢复）。
 * 没有任何有效像素的图（空图：基础图公共区全空 / 独有区图没有任何独有像素等）没有可判别的点、
 * 无法据此做区分：判完全不匹配、按不匹配占比满值计入并照常参与所在族均值；-1 只出现在产物缺失 /
 * 比对口径不符的异常防御路径（产物齐全时不会出现），该图才跳过。
 * 不同分类按各自的差异度比较，最小者即为最近似分类。
 * 执行以最近似分类为准、不设识别阈值门槛：差异度仅作参考展示（越小表示画面越接近该分类的样本）。
 *
 * <p>坐标可靠性：整个工程靠 resize 把窗口/截图尺寸强制对齐到与标注样本一致，summary 产物
 * 与当前画面天然等尺寸、像素一一对应，因此命中分类记录在 info.json 里的点击坐标可直接用于执行动作
 * （点击点/注意点两套方框图也分别以同一坐标定位，画面与产物裁剪位置天然一致）。
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

    /* 全部产物维度（kind / 磁盘文件名 / 块压缩口径 / 是否走「完全一致」判据 / 方框形态 / 权重族 /
     * 展示顺序）的唯一权威定义见 {@link ArtifactKind}，本类不再各自维护一份 kind 清单。 */

    /** 差异度五族权重与其包含的 kind（覆盖全部适用图）：A 全图交集 12（same100→50 各带 -unique）
     *  权 50 → B 多数族 6（max/major8/major32 各带 -unique）权 15 → C 均值族 6（avg/avg8/avg32
     *  各带 -unique）权 10 → D 去重均值族 6（dedup-avg/8/32 各带 -unique）权 10 → E 方框交集区 24
     *  （点击区 click8→32 × 各档 12 张 + 注意区 attn8→32 × 各档 12 张，均无 -unique；click 分类 24 张、
     *  无动作分类只有注意区 12 张）权 15。每族先对族内各图「不匹配点占比」等权平均得族均值，
     *  差异度 = (50A+15B+10C+10D+15E)/W：W = 适用族的权重之和，
     *  产物齐全的参与分类五族俱备、恒为 100；空图（产物无任何有效像素、无法做区分）按完全不匹配满值计、照常参与。 */
    /** 默认五族权重（A 全图交集 50 / B 多数 15 / C 均值 10 / D 去重均值 10 / E 方框交集区 15），固定不可调。 */
    private static final int[] DEFAULT_FAMILY_WEIGHTS = {50, 15, 10, 10, 15};
    private final int[] familyWeights = DEFAULT_FAMILY_WEIGHTS.clone();

    /** 全幅对照图约 3.7MB/张：当前规模 82 组 3226 张产物约 5.5GB、classify 原图数百张，由 restart.cmd 的 -Xmx24g
     *  提供充足堆空间让软引用缓存全部驻留；内存吃紧时缓存条目由 JVM 的 GC 自动回收，不需要任何手动清理。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StoragePaths storage;
    private final ExecuteProperties executeProperties;

    /** 产物像素缓存：key = 产物文件绝对路径。值用 SoftReference 包装：堆内存充裕时全部常驻、识别零重解码；
     *  内存吃紧时 JVM 会在 OOM 前自动回收（回收时机由 GC 软引用策略决定），被回收的条目等同缓存 miss、
     *  loadCached 自动重解码补回——不再需要按产物规模手动上调 -Xmx。并发读写安全，供识别与特征验证共用。 */
    private final Map<String, SoftReference<CachedPx>> cache = new ConcurrentHashMap<>();

    /** classify/ 已标注原始图的全幅像素缓存（供「原图直比」候选行每帧与画面逐像素比对，免每帧重解码）：
     *  key = 原图绝对路径，带 mtime/size 失效；同产物缓存一样用软引用，内存吃紧时 JVM 自动回收、miss 自动重解码。
     *  每张约 3.7MB（1280×720）。 */
    private final Map<String, SoftReference<CachedPx>> rawPxCache = new ConcurrentHashMap<>();

    /** 跳过告警日志节流：同一份「跳过名单 + 原因」在 60 秒内只打一次（识别循环周期轮询，直接每次打会刷屏）。 */
    private static volatile String lastSkippedSig = "";
    private static volatile long lastSkippedLogMs;

    static final class CachedPx {
        final long lastModified;
        final long size;
        final int w;          // 原图宽（全幅 = 产物宽；块图 = 块降采样宽）
        final int h;          // 原图高
        final boolean isFull; // true = 全幅类产物（交集六档及 max/avg/dedup-avg 及各自 -unique、方框交集图）的整幅像素
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
     *  全幅类比对直接用下方 full 整幅像素（交集六档及 max/avg/dedup-avg 及各自 -unique、方框交集图共用同一份）。 */
    static final class FrameWork {
        final int fw, fh;
        final int[] full;       // 画面全幅像素（全幅类逐点比对 + 方框交集图按坐标裁方框共用同一份）
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
        /** 最近似分类的「五族加权」差异度百分比（0~100，越低越像）。 */
        public double bestDiffPercent = Double.NaN;
        /** 最近似分类的汇总产物目录名（summary/<dir>），无最近似分类时 null。 */
        public String bestFile;
        /** 命中分类定义的动作（click / none / other），来自 info.json。 */
        public String action;
        /** 命中分类的<b>鼠标点击坐标</b>（仅 click 分类执行层使用；来源于 info 的 actLeft/actTop，
         *  旧产物缺 act 键时回退裁剪中心 —— 两者在旧数据中同点）。 */
        public Integer clickLeft;
        /** 命中分类的<b>鼠标点击坐标</b>（仅 click 分类执行层使用；来源于 info 的 actLeft/actTop，
         *  旧产物缺 act 键时回退裁剪中心 —— 两者在旧数据中同点）。 */
        public Integer clickTop;
        /** 实际参与比较（核心对照图齐全且算出有效差异度）的分类数。 */
        public int scannedSamples;
        /** summary/ 下存在有效分类标注的产物目录数（含核心对照图不全被跳过的）。 */
        public int totalSamples;
        /** 未参与比对（被跳过）的产物目录名 → 跳过原因（totalSamples > scannedSamples 时才非空，供界面提示与日志排查）。 */
        public final Map<String, String> skipped = new LinkedHashMap<>();
        /** 各分类的最近似结果，按差异度（五族加权）升序排列（前几个即“候选分类”）。 */
        public List<Candidate> candidates = new ArrayList<>();
        /** 「按已分类原图匹配」直比命中：classify/ 全部已标注原始截图里与画面逐像素完全一致比对后
         *  不匹配点占比最低的一张（只含文件名与分值，归属分类/动作由 ExecutionService 按样本标注解析）。
         *  对照图是分类的合成概括图，个别画面可能被它误配，直比原始截图最直接可信；
         *  无任何同尺寸已标注样本可比时为 null。 */
        public RawHit rawHit;
        /** 识别耗时（毫秒）。 */
        public long elapsedMs;
    }

    /** 一张对照图（某个 kind 产物）与该次识别画面的比对明细。score < 0 表示该图未参与差异度聚合（缺失或公共区为空）。 */
    public record KindScore(String kind, String file, int w, int h, double score) {
    }

    /** 一个候选：某分类的五族加权差异度 + 各图各自的分值明细（matchedFile 目录下的产物）。 */
    public record Candidate(String state, double diffPercent, String matchedFile, List<KindScore> kinds, boolean raw) {
    }

    /** 原图直比命中：命中的 classify/ 原图文件名 + 不匹配点占比（0~100，0.00% = 与画面逐像素完全相同）。 */
    public record RawHit(String file, double diffPercent) {
    }

    /** 某分类的两个框心坐标：actX/actY = 鼠标点击点（点击区图框心，仅 click 分类有效）、
     *  attnX/attnY = 注意点（注意区图框心，每个分类都有）；缺失的坐标以 -1 表示（该方框图比对直接返回 -1）。
     *  用独立 record 传参，避免原先 (acx, acy, ccx, ccy) 四个 int 位置传错而编译器无感。 */
    public record Centers(int actX, int actY, int attnX, int attnY) {
    }

    private static final class GroupBest {
        String state;
        double diff = Double.POSITIVE_INFINITY;
        String dir;
        String action;
        /** 注意点（= info 的 attnLeft/attnTop，旧产物回退历史键 clickLeft/clickTop；注意区图比对框心，非真实点击坐标）。 */
        Integer cropLeft;
        Integer cropTop;
        /** 鼠标点击点（= info actLeft/actTop，仅 click 分类有；点击区图比对框心，也是执行模式真实点击的位置）。 */
        Integer actLeft;
        Integer actTop;
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
            String miss = missingArtifactNames(dir, info);
            if (miss != null) {
                out.skipped.put(dir.getFileName().toString(), "对照图不齐，缺：" + miss);
                continue;   // 该分类适用的对照图不齐（未做汇总分析 / -unique 独有区图或方框交集图尚未补齐）无法比较
            }
            Number wObj = info.get("width") instanceof Number n ? n : null;
            Number hObj = info.get("height") instanceof Number n ? n : null;
            if (wObj == null || hObj == null || wObj.intValue() <= 0 || hObj.intValue() <= 0) {
                out.skipped.put(dir.getFileName().toString(), "info.json 缺少有效宽高，无法比对");
                continue;
            }
            int w = wObj.intValue();
            int h = hObj.intValue();
            // 两个框心：注意点（attnLeft/attnTop = 裁剪中心权威键，未设 = 屏幕中心；旧产物缺该键时回退
            // 历史键 clickLeft/clickTop —— 旧数据里它就是注意点）用于裁注意区交集图；
            // 点击点（actLeft/actTop，仅 click 分类有）用于裁点击区交集图；
            // 产物齐全性已按 info 校验（缺注意区图 / click 分类缺点击区图的目录已跳过）
            Integer cl = pickInt(info, "attnLeft", "clickLeft");
            Integer ct = pickInt(info, "attnTop", "clickTop");
            int ccx = cl != null ? cl : -1;
            int ccy = ct != null ? ct : -1;
            Integer al = intOf(info.get("actLeft"));
            Integer at = intOf(info.get("actTop"));
            int acx = al != null ? al : ccx;   // 旧产物缺 act 键时回退注意点（旧数据两者同点，行为不变）
            int acy = at != null ? at : ccy;
            Centers centers = new Centers(acx, acy, ccx, ccy);

            if (work == null) {
                work = new FrameWork(framePxFull, fw, fh);
            }
            int kindCount = 0;
            Map<String, KindScore> scores = new LinkedHashMap<>();   // 该目录各对照图的分值（空图按完全不匹配满值 100；-1 仅异常防御）
            for (String kind : ArtifactKind.kinds()) {
                String file = ArtifactKind.file(kind);
                CachedPx ref = loadCached(dir.resolve(file), kind);
                KindScore ks = (ref == null) ? new KindScore(kind, file, 0, 0, -1)
                        : new KindScore(kind, file, ref.w, ref.h,
                        compareKind(work, ref, kind, centers));
                scores.put(kind, ks);
                if (ks.score() >= 0) {
                    kindCount++;
                }
            }
            if (kindCount == 0) {
                out.skipped.put(dir.getFileName().toString(), "对照图文件存在但全部无法解码读出");
                continue;   // 该分类适用的对照图全部无法有效读出 → 该目录不参与（正常产物不会出现）
            }
            scanned++;
            // 差异度 = 五族加权平均：(50A+15B+10C+10D+15E)/W（A~E = 各族「不匹配点占比」的族内等权均值，
            // 权重 A50/B15/C10/D10/E15；W = 适用族的权重之和（产物齐全的参与分类五族俱备、恒为 100）；
            // 空图（无有效像素、无法做区分）按完全不匹配满值参与族均值；-1 仅产物缺失/口径不符的异常路径、该图跳过）
            double diff = aggregateDiff(scores);
            GroupBest g = perState.computeIfAbsent(state, s -> new GroupBest());
            g.state = state;
            if (diff < g.diff) {
                g.diff = diff;
                g.dir = dir.getFileName().toString();
                g.kindScores = scores;   // 只保留该分类“最优产物目录”那一轮的各图分值
                g.action = info.get("action") == null ? null : String.valueOf(info.get("action"));
                g.cropLeft = pickInt(info, "attnLeft", "clickLeft");
                g.cropTop = pickInt(info, "attnTop", "clickTop");
                g.actLeft = intOf(info.get("actLeft"));
                g.actTop = intOf(info.get("actTop"));
            }
        }

        List<GroupBest> sorted = new ArrayList<>(perState.values());
        sorted.sort((a, b) -> Double.compare(a.diff, b.diff));
        List<GroupBest> topFew = sorted.size() > 7 ? new ArrayList<>(sorted.subList(0, 7)) : sorted;
        out.candidates = candidatesOf(topFew);

        out.scannedSamples = scanned;
        out.totalSamples = total;
        if (!sorted.isEmpty()) {
            GroupBest top = sorted.get(0);
            out.bestState = top.state;
            out.bestDiffPercent = top.diff;
            out.bestFile = top.dir;
            out.action = top.action;
            // 点击区裁剪中心（clickLeft/clickTop）与真实鼠标点击点（actLeft/actTop）分离后：
            // 暴露给执行层的坐标 = 真实点击点（仅 click 分类有）；旧产物无 act 键时回退裁剪中心
            // （旧数据两者同点，行为不变）
            out.clickLeft = top.actLeft != null ? top.actLeft : top.cropLeft;
            out.clickTop = top.actTop != null ? top.actTop : top.cropTop;
            // 已去掉识别阈值门槛：只要有可比的最近似分类即视为可用（差异度仅作展示，不再判已识别/未识别）
            out.recognized = true;
        }
        // 「按已分类原图匹配」候选：遍历 classify/ 全部已标注原始截图，与画面逐像素完全一致直比，
        // 取不匹配点占比最低的一张（归属分类 / 动作由 ExecutionService 按样本标注解析后并入候选列表）
        out.rawHit = rawOriginalHit(framePxFull, fw, fh);
        out.elapsedMs = System.currentTimeMillis() - t0;
        if (log.isDebugEnabled()) {
            log.debug("画面识别完成：recognized={}, best={} ({}) diff={}%, compared={}/{}",
                    out.recognized, out.bestState, out.bestFile, String.format("%.2f", out.bestDiffPercent),
                    out.scannedSamples, out.totalSamples);
        }
        logSkippedGroups(out);   // 有分类未参与比对时打一条含目录与原因的告警（节流，防识别循环刷屏）
        return out;
    }

    /** 分类差异度 = 五族加权平均 (50A+15B+10C+10D+15E)/W（0~100，越小越像）：各族对族内各图的
     *  「不匹配点占比」等权平均（A 全图交集 12 张 / B 多数 6 / C 均值 6 /
     *  D 去重均值 6 / E 方框交集区 24（点击区 12 + 注意区 12，click 分类 24 / 无动作 12），
     *  权重 50/15/10/10/15）；W = 适用族的权重之和——
     *  参与识别的分类五族产物齐全、恒为 100（旧产物缺方框图整体跳过，后台重算后补齐）。
     *  空图（产物无任何有效像素：基础图公共区全空 / 独有区图无独有点）在比对侧已判完全不匹配、
     *  按不匹配占比满值计入并照常参与族均值；-1 仅产物缺失 / 比对口径不符的异常防御路径返回
     *  （产物齐全时不会出现），该图跳过不参与族均值。 */
    private double aggregateDiff(Map<String, KindScore> scores) {
        int[] w = familyWeights;   // 固定默认权重
        double sum = 0;
        int wsum = 0;
        for (int i = 0; i < ArtifactKind.familyCount(); i++) {
            double fsum = 0;
            int n = 0;
            for (String kind : ArtifactKind.familyKinds(i)) {
                KindScore ks = scores.get(kind);
                if (ks != null && ks.score() >= 0) {
                    fsum += ks.score();
                    n++;
                }
            }
            if (n == 0) {
                continue;   // 整族全为异常 -1 → 权重不进 W（正常产物齐全不会出现）
            }
            sum += w[i] * fsum / n;
            wsum += w[i];
        }
        return wsum == 0 ? 0.0 : sum / wsum;
    }

    private static List<Candidate> candidatesOf(List<GroupBest> sorted) {
        List<Candidate> out = new ArrayList<>(sorted.size());
        for (GroupBest g : sorted) {
            List<KindScore> kinds = new ArrayList<>(ArtifactKind.kinds().size());
            for (String kind : ArtifactKind.kinds()) {
                KindScore ks = g.kindScores.get(kind);
                if (ks != null) {
                    kinds.add(ks);
                }
            }
            out.add(new Candidate(g.state, g.diff, g.dir, kinds, false));
        }
        return out;
    }

    /* ---------------- 已分类原始图直比（「按已分类原图匹配」候选） ---------------- */

    /**
     * 遍历 classify/ 下全部已标注原始截图（img_*.png），与画面逐像素完全一致比对
     * （RGB 低 24 位全等、忽略 alpha；仅同分辨率可直比，尺寸不同直接跳过），
     * 返回不匹配点占比最低的一张；无任何同尺寸样本时为 null。
     * 判定复用 {@link #mismatchPercent} 的完全一致口径（与产物交集/多数类同判据），
     * 与对照图候选的差异分值可直接比大小。
     */
    private RawHit rawOriginalHit(int[] full, int fw, int fh) {
        Path dir = storage.classify();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<Path> pngs;
        try (Stream<Path> s = Files.list(dir)) {
            pngs = s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return Files.isRegularFile(p) && n.startsWith("img_") && n.endsWith(".png");
            }).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        } catch (IOException e) {
            return null;
        }
        RawHit best = null;
        double bestDiff = Double.POSITIVE_INFINITY;
        for (Path p : pngs) {
            CachedPx ref = loadRawPx(p, fw, fh);
            if (ref == null) {
                continue;
            }
            double d = mismatchPercent(full, ref.px, EXACT_MATCH_DIST);
            if (d < bestDiff) {
                bestDiff = d;
                best = new RawHit(p.getFileName().toString(), d);
                if (d == 0.0) {
                    break;   // 与某张原图逐像素完全相同 = 全局最低，无需再比
                }
            }
        }
        return best;
    }

    /** 读一张 classify/ 原图的全幅像素（缓存：mtime/size 失效；软引用被 JVM 回收等同 miss、自动重解码；
     *  尺寸与画面不符不缓存、直接跳过）。 */
    private CachedPx loadRawPx(Path p, int fw, int fh) {
        BasicFileAttributes att;
        try {
            att = Files.readAttributes(p, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
        String key = p.toString();
        CachedPx hit = cacheValue(rawPxCache, key);
        if (hit != null && hit.size == att.size() && hit.lastModified == att.lastModifiedTime().toMillis()) {
            return hit;
        }
        CachedPx c = decodeRawPng(p, fw, fh);
        if (c != null) {
            rawPxCache.put(key, new SoftReference<>(c));
        } else {
            rawPxCache.remove(key);
        }
        return c;
    }

    /** 解码 classify/ 原图全幅像素但不读写缓存（特征验证用：每张样本整轮只在首个 kind 解码一次、像素由 works
     *  持有、验证结束随任务释放，避免把全部原图永久灌进常驻缓存）；尺寸与 fw×fh 不符返回 null。 */
    private CachedPx decodeRawPng(Path p, int fw, int fh) {
        BasicFileAttributes att;
        try {
            att = Files.readAttributes(p, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
        int[] wh = pngSize(p);
        if (wh == null || wh[0] != fw || wh[1] != fh) {
            return null;   // 仅同分辨率可直接逐点直比（不缩放）
        }
        BufferedImage bi;
        try {
            bi = ImageIO.read(p.toFile());
        } catch (IOException e) {
            return null;
        }
        if (bi == null || bi.getWidth() != fw || bi.getHeight() != fh) {
            return null;
        }
        return new CachedPx(att.lastModifiedTime().toMillis(), att.size(), fw, fh, true,
                bi.getRGB(0, 0, fw, fh, null, 0, fw));
    }

    /** 只读 PNG 文件头（前 24 字节）取宽高，避免为尺寸不符的图做整图解码；非 PNG / 读取失败返回 null。 */
    private static int[] pngSize(Path p) {
        byte[] head = new byte[24];
        int off = 0;
        try (InputStream in = Files.newInputStream(p)) {
            while (off < head.length) {
                int r = in.read(head, off, head.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
        } catch (IOException e) {
            return null;
        }
        if (off < 24 || head[0] != (byte) 0x89 || head[1] != 'P' || head[2] != 'N' || head[3] != 'G') {
            return null;
        }
        int w = ((head[16] & 0xff) << 24) | ((head[17] & 0xff) << 16) | ((head[18] & 0xff) << 8) | (head[19] & 0xff);
        int h = ((head[20] & 0xff) << 24) | ((head[21] & 0xff) << 16) | ((head[22] & 0xff) << 8) | (head[23] & 0xff);
        return new int[]{w, h};
    }

    /**
     * 把画面预计算序列（{@link FrameWork}：整幅像素 / 8·32 多数·均值块压缩，全部分类目录共用同一份）与
     * 某张对照图产物（ref 缓存里已是全像素序列）逐点比对，返回该图「不匹配点占比」百分比（0~100）。
     * 判据按维度类别分两套：交集/多数类（交集六档 same100/90/80/70/60/50、max/major8/major32
     * 及各自的 -unique、以及全部 24 张方框交集图 click8/32-same100/90/80/70/60/50 与
     * attn8/32-same100/90/80/70/60/50）对照颜色是
     * 样本真实像素，同一状态画面应能逐像素重现 → <b>逐像素完全一致</b>
     * （R/G/B 三通道差都须为 0）才算匹配；均值类（avg/avg8/avg32、dedup-avg/dedup-avg8/dedup-avg32
     * 及各自的 -unique）对照颜色是样本平均 / 去重平均色 → 走逐通道容差
     * {@code execute.rgb-dist-threshold}（三通道差都 ≤ 阈值才算匹配）。
     * 方框交集图另在画面上裁同一方框比对：点击区图用鼠标点击点 (acx,acy)、注意区图用注意点 (ccx,ccy)；
     * 框可越出画幅，出界点填透明、与产物同坐标的透明对齐（方框几何与生成端 clickBox 同公式）；
     * 框心 < 0（无坐标，正常不会出现）返回 -1。产物与画面分辨率不一致（resize 对齐被破坏）直接抛异常；
     * 产物口径与该 kind 网格对不上时返回 -1（该图不参与聚合）。
     */
    private double compareKind(FrameWork work, CachedPx ref, String kind, Centers c) {
        ArtifactKind.Def def = ArtifactKind.of(kind);
        if (def == null) {
            return -1;
        }
        int block = def.block();
        int mode = def.mode();   // 1=块内多数 / 0=块内均值 / 2=块内去重均值
        int distThr = def.exact() ? EXACT_MATCH_DIST : executeProperties.getRgbDistThreshold();

        if (def.crop() != ArtifactKind.Crop.NONE) {
            // 方框交集图（点击区 / 注意区）：产物是框心附近整幅 1/8 或 1/32 的方框小图（缓存全像素）。
            // 点击区图按鼠标点击点、注意区图按注意点在画面上裁出同尺寸方框逐点比较；
            // 框中心不收敛、可越出画幅，出界点画面填透明与产物同坐标的透明对齐，透明点都不参与统计
            int cx = def.crop() == ArtifactKind.Crop.CLICK ? c.actX() : c.attnX();
            int cy = def.crop() == ArtifactKind.Crop.CLICK ? c.actY() : c.attnY();
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
        // 独有区图（含块图）与基础图公共区全空的空图：没有可判别的点＝无法做区分，比对侧按完全不匹配满值计、照常参与族均值
        return mismatchPercent(a, ref.px, distThr);
    }

    /* ---------------- 像素裁剪 / 块压缩 / 差异判定 ---------------- */

    /** 从全幅画面像素中裁出以 (x0,y0) 为左上角、bw×bh 的方框（点击区/注意区交集图比对用：逐像素、不抽样）。
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
     * （交集图非公共区域 / 独有区图非独有区域）不参与统计；判别区再小也按实测占比计分。
     * 产物（b）没有任何有效像素（空图：基础图公共区全空 / 独有区图无独有点）＝没有可判别的点、
     * 无法据此区分画面：判完全不匹配、按不匹配占比满值 100 计，照常参与聚合；
     * -1 只在长度不一致等异常防御路径返回。
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
            // 免去每点三通道拆位/取差运算（42 张 kind 里 30 张 exact 走这条，是最热路径）
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
            // 产物没有任何有效像素（空图：基础图公共区全空 / 独有区图无独有点）＝没有可判别的点、无法做区分：
            // 判完全不匹配、按不匹配占比满值计，照常参与聚合/验证（与识别端同一口径）
            return 100.0;
        }
        return bad * 100.0 / n;
    }

    /* ---------------- 产物读取与缓存 ---------------- */

    /** 取软引用缓存值并清掉已被 GC 回收的空壳条目（referent 为 null 视作缓存 miss，由调用方重解码补回）。 */
    private static CachedPx cacheValue(Map<String, SoftReference<CachedPx>> m, String key) {
        SoftReference<CachedPx> sr = m.get(key);
        if (sr == null) {
            return null;
        }
        CachedPx v = sr.get();
        if (v == null) {
            m.remove(key, sr);
        }
        return v;
    }

    /** 读取一张产物图并缓存解码后的全像素序列（值软引用：JVM 内存吃紧回收后 miss 自动重解码补齐；
     *  全幅图/点击区方框图与块图一致，均不抽样），解码失败返回 null。 */
    private CachedPx loadCached(Path png, String kind) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(png, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
        String key = png.toAbsolutePath().toString();
        CachedPx hit = cacheValue(cache, key);
        if (hit != null && hit.lastModified == attrs.lastModifiedTime().toMillis() && hit.size == attrs.size()) {
            return hit;
        }
        CachedPx sp = decodeArtifactPng(png, kind);
        if (sp == null) {
            cache.remove(key);
            return null;
        }
        cache.put(key, new SoftReference<>(sp));
        return sp;
    }

    /** 解码一张产物 PNG 的全像素但不读写缓存（特征验证用：42 种 kind 各自解码、比对完即释放，不写缓存就不会
     *  把所有分类的全部产物像素永久滞留堆里）；网格口径与 loadCached 一致，解码失败返回 null。 */
    private CachedPx decodeArtifactPng(Path png, String kind) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(png, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
        BufferedImage im;
        try {
            im = ImageIO.read(png.toFile());
        } catch (IOException e) {
            return null;
        }
        if (im == null) {
            return null;
        }
        int w = im.getWidth();
        int h = im.getHeight();
        ArtifactKind.Def def = ArtifactKind.of(kind);
        boolean isFull = def != null && def.block() == 1;   // 全幅 kind（交集六档及 max/avg/dedup-avg 及各自 -unique、两套方框交集图）
        return new CachedPx(attrs.lastModifiedTime().toMillis(), attrs.size(), w, h, isFull,
                im.getRGB(0, 0, w, h, null, 0, w));
    }

    /** 参与比对必需的对照图是否齐全：30 张（15 基础 + 15 -unique，含交集六档）+ 12 张注意区交集图
     *  （注意点未设 = 屏幕中心，故每个分类都必须有）；info 记录了点击点（actLeft/actTop）的 click 分类
     *  再需 12 张点击区交集图（= 54 张）。
     *  任一缺失该目录整体跳过（旧产物未按新规则重算，后台重算后恢复）。 */
    private static boolean artifactsComplete(Path gdir, Map<String, Object> info) {
        return missingArtifactNames(gdir, info) == null;
    }

    /** 列出参与比对必需的缺失对照图文件名（逗号分隔的清单；齐全返回 null）。与 artifactsComplete 同口径，
     *  供「该分类被跳过」的原因提示使用（能直接看到缺哪几张，方便定位是未分析还是产物不齐）。 */
    private static String missingArtifactNames(Path gdir, Map<String, Object> info) {
        List<String> miss = new ArrayList<>();
        for (String f : filesOf(ArtifactKind.coreKinds())) {
            if (!Files.isRegularFile(gdir.resolve(f))) {
                miss.add(f);
            }
        }
        for (String f : filesOf(ArtifactKind.attnKinds())) {   // 注意区图每个分类都要求（注意点默认屏幕中心）
            if (!Files.isRegularFile(gdir.resolve(f))) {
                miss.add(f);
            }
        }
        Integer al = intOf(info.get("actLeft"));
        Integer at = intOf(info.get("actTop"));
        if (al != null && at != null) {       // 只有 click 分类有真实点击点，相应要求点击区图
            for (String f : filesOf(ArtifactKind.clickKinds())) {
                if (!Files.isRegularFile(gdir.resolve(f))) {
                    miss.add(f);
                }
            }
        }
        return miss.isEmpty() ? null : String.join(", ", miss);
    }

    /** kind 清单 → 产物文件名清单（顺序保持，供齐全性校验与原因提示） */
    private static List<String> filesOf(List<String> kinds) {
        return kinds.stream().map(ArtifactKind::file).toList();
    }

    /** 汇总输出本轮未参与比对的分类与原因（名单变化或距上次超过 60 秒才打一次，避免识别循环每轮刷屏）。 */
    private static void logSkippedGroups(Outcome out) {
        if (out.skipped.isEmpty()) {
            return;
        }
        String sig = out.skipped.toString();
        long now = System.currentTimeMillis();
        if (sig.equals(lastSkippedSig) && now - lastSkippedLogMs < 60_000L) {
            return;
        }
        lastSkippedSig = sig;
        lastSkippedLogMs = now;
        StringBuilder sb = new StringBuilder();
        out.skipped.forEach((d, r) -> sb.append("\n    - ").append(d).append("：").append(r));
        log.warn("画面识别有 {} 个分类未参与比对（已比对 {}/{}）：{}", out.skipped.size(),
                out.scannedSamples, out.totalSamples, sb);
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

    /** 依次取 info 里的两个键（前者优先）：attnLeft/attnTop 为裁剪中心的权威键，
     *  clickLeft/clickTop 为历史键（旧产物只有它，旧数据里它就是注意点）。 */
    private static Integer pickInt(Map<String, Object> info, String primary, String legacy) {
        Integer v = intOf(info.get(primary));
        return v != null ? v : intOf(info.get(legacy));
    }

    // ---------- 特征验证桥接（供同包 VerifyService 在独立任务中复用识别同口径比对） ----------

    /** 验证维度清单 = 识别参与比对的全部 kind（顺序与识别一致）。 */
    static List<String> verifyOrder() {
        return ArtifactKind.kinds();
    }

    /** kind → 产物文件名（与识别比对同源）。 */
    static String verifyFile(String kind) {
        return ArtifactKind.file(kind);
    }

    /** 读一张产物全幅像素但不读写缓存（解码失败返回 null）：验证每次运行都重新解码、比对完随 kind 释放，
     *  避免把全部 kind × 全部分类的产物像素永久写进常驻缓存。 */
    CachedPx verifyArtifact(Path png, String kind) {
        return decodeArtifactPng(png, kind);
    }

    /** 读 classify/ 原图全幅像素但不读写缓存（仅与 fw×fh 同分辨率，不符返回 null）：像素由验证的 works 持有、
     *  整轮只解码一次，任务结束随 GC 释放。 */
    CachedPx verifySample(Path png, int fw, int fh) {
        return decodeRawPng(png, fw, fh);
    }

    /** 单张样本（work，已按样本全幅预计算）与单张产物单 kind 逐点比对（识别同口径，0~100）；
     *  acx/acy = 该分类点击点（点击区图框心）、ccx/ccy = 该分类注意点（注意区图框心）；
     *  产物与画面分辨率不符等不适用情形返回 -1，不抛异常。 */
    double verifyKindScore(FrameWork work, CachedPx ref, String kind, Centers c) {
        try {
            return compareKind(work, ref, kind, c);
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** 读产物目录 info.json（非私有版，供验证枚举分组）。 */
    static Map<String, Object> verifyInfo(Path gdir) {
        return readInfo(gdir);
    }

}
