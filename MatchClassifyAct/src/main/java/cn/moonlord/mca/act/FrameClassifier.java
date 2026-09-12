package cn.moonlord.mca.act;

import cn.moonlord.mca.config.ExecuteProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 画面识别器（执行模式与「未标注」里的单图智能推荐）：把「当前最新截图」与<b>运行时算法</b>
 * （{@code resource/runtime/}，算法调优跑完落地的「综合最佳算法」）做像素比对。
 *
 * <p><b>匹配源</b>不是 resource/summary/ 的整批对照图，而是算法调优选出的那一个算法：算法由若干<b>特征</b>组成，
 * 每个特征 = 一种产物 kind（见 {@link ArtifactKind}）+ 基础分 X（越大越能把这个分类与别人分开）+ 权重 Y；
 * 该算法的<b>生效特征</b>（Y &gt; 0，见 {@link RuntimeAlgorithm#effective}）的各分类对照图产物已由算法调优
 * 搬到 {@code resource/runtime/<分类>/}，算法定义与各分类动作 / 点击坐标写在 {@code resource/runtime/algorithm.json}
 * （见 {@link RuntimeAlgorithm}）。于是运行时与标注 / 汇总分析过程解耦：调优跑过一次即可长期执行。</p>
 *
 * <p><b>判分口径</b>（{@link #classifyByAlgorithm}，与算法调优的评价完全一致，共四步）：
 * ① 逐特征算匹配值 = 100 − 该分类此特征产物的不匹配点占比；
 * ② <b>打平剔除</b>：某特征的最高匹配值被 ≥2 个分类并列时，本画面整张放弃该特征
 * （并列只在有有效产物的分类之间数：无有效像素的空图恒 0 分、不参与这步，但仍按满值不匹配进加权平均）；
 * ③ <b>加权匹配度</b> = Σ(匹配值 × X × Y) ÷ Σ(X × Y)，只累加该分类可用的生效特征；
 * ④ <b>结论</b>：匹配度唯一最高的分类即结果，仍并列（或可用特征被全部放弃）则「无法区分」、
 * 执行层不动作；不设识别阈值门槛，差异度 = 100 − 匹配度仅供展示参考。</p>
 *
 * <p>产物 kind 清单、块压缩口径、判据族、展示顺序的唯一权威定义见 {@link ArtifactKind}；
 * 逐图判据（完全一致 / 容差、方框越界透明、空图口径）见 {@link #compareKind} 与 {@link #mismatchPercent}。</p>
 *
 * <p>坐标可靠性：整个工程靠 resize 把窗口 / 截图尺寸强制对齐到与标注样本一致，runtime 产物与当前画面
 * 天然等尺寸、像素一一对应，因此命中分类记录的点击坐标可直接用于执行动作。</p>
 *
 * <p>性能：画面每轮只预计算一次 8·32 块压缩序列，全部分类共用（见 {@link FrameWork}）；
 * 全幅类图直接与画面整幅像素逐点比较；产物像素缓存带 mtime/size 失效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameClassifier {

    /** 交集 / 多数类维度用的「完全一致」判据阈值：distThr=0 ⇔ 仅当三通道差都为 0 才算匹配 */
    private static final int EXACT_MATCH_DIST = 0;

    /** 并列判定用的浮点容差（最高匹配度之间差 ≤ 该值即视为并列：该特征打平被放弃 / 分类无法区分）。
     *  与算法调优（{@code OptimizeService.EPS}）同值同写法：两边读的是同一份产物、算出的分值应完全一致。 */
    private static final double TIE_EPS = 1e-9;

    /* 全部产物维度（kind / 磁盘文件名 / 块压缩口径 / 是否走「完全一致」判据 / 方框形态 / 权重族 /
     * 展示顺序）的唯一权威定义见 {@link ArtifactKind}，本类不再各自维护一份 kind 清单。 */

    /** 产物 info.json 解析用 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExecuteProperties executeProperties;

    /** 产物像素缓存：key = 产物文件绝对路径（特征验证读 resource/summary/、识别读 resource/runtime/，同一张只解一次）。
     *  值用 SoftReference 包装：堆内存充裕时全部常驻、识别零重解码；内存吃紧时 JVM 会在 OOM 前自动回收，
     *  被回收的条目等同缓存 miss、由 loadCached 自动重解码补回（因此不必按产物规模手动上调 -Xmx）。
     *  并发读写安全，供识别与特征验证共用。 */
    private final Map<String, SoftReference<CachedPx>> cache = new ConcurrentHashMap<>();

    /** 跳过告警日志节流：同一份「跳过名单 + 原因」在 60 秒内只打一次（识别循环周期轮询，直接每次打会刷屏）。 */
    private static volatile String lastSkippedSig = "";
    private static volatile long lastSkippedLogMs;

    static final class CachedPx {
        final long lastModified;
        final long size;
        final int w;          // 原图宽（全幅 = 产物宽；块图 = 块降采样宽）
        final int h;          // 原图高
        final boolean isFull; // true = 全幅类产物（整幅像素）；false = 块图（已块降采样）
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

    /** 当前画面的一次性预计算产物：每轮只算一次，本帧各分类共用（原先每个分类目录各自重复整帧块压缩，
     *  是识别耗时的最大来源）。 */
    static final class FrameWork {
        final int fw, fh;
        final int[] full;       // 画面全幅像素（全幅类逐点比对 + 方框交集图按坐标裁框共用）
        final int[] b8M, b8A, b8DA;   // 8×8 块多数 / 均值 / 去重均值
        final int[] b32M, b32A, b32DA; // 32×32 块多数 / 均值 / 去重均值
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
        /** 是否有唯一的最近似分类（有分类参与比对、且最高匹配度没被并列即为 true；不设阈值门槛）。 */
        public boolean recognized;
        /** 最近似分类标注（无分类可判定 / 无法区分时为空）。 */
        public String bestState;
        /** 最近似分类的差异度百分比（0~100，越低越像 = 100 − 加权匹配度）；无法区分时为并列最高那条的差异度。 */
        public double bestDiffPercent = Double.NaN;
        /** 最近似分类在 resource/runtime/ 下的产物子目录名（<dir>），无最近似分类时 null。 */
        public String bestFile;
        /** 命中分类定义的动作（click / none / other），来自算法调优落盘的 algorithm.json。 */
        public String action;
        /** 命中分类的<b>鼠标点击坐标</b>（仅 click 分类执行层使用；来源于 algorithm.json 的 actLeft/actTop，
         *  缺 act 键时回退注意点坐标）。 */
        public Integer clickLeft;
        /** 见 {@link #clickLeft}（取自 {@code actTop}）。 */
        public Integer clickTop;
        /** 实际算出有效匹配度（至少有一个特征没被放弃、且该分类判得了）的分类数。 */
        public int scannedSamples;
        /** resource/runtime/algorithm.json 里的分类总数（含分辨率不符 / 特征全被放弃而跳过的）。 */
        public int totalSamples;
        /** 未参与比对（被跳过）的分类子目录名 → 跳过原因（totalSamples > scannedSamples 时才非空，供界面提示与日志排查）。 */
        public final Map<String, String> skipped = new LinkedHashMap<>();
        /** 各分类的匹配结果，按加权匹配度降序（= 差异度升序）排列（前几个即“候选分类”）。 */
        public List<Candidate> candidates = new ArrayList<>();
        /** 无法区分：放弃打平特征后，最高匹配度仍被 ≥2 个分类并列（或本画面可用特征被全部放弃）→ 不执行动作。 */
        public boolean ambiguous;
        /** 并列最高匹配度的分类标注（ambiguous 时非空，供页面提示「有哪几个分类分不出」）。 */
        public final List<String> tiedStates = new ArrayList<>();
        /** 识别耗时（毫秒）。 */
        public long elapsedMs;
    }

    /** 一张特征产物（算法里某个 kind）与该次识别画面的比对明细。score = 不匹配点占比（0~100，越小越像 = 100 − 匹配值）。 */
    public record KindScore(String kind, String file, int w, int h, double score) {
    }

    /** 一个候选：某分类的差异度（= 100 − 加权匹配度）+ 算法各特征各自的分值明细（resource/runtime/&lt;matchedFile&gt;/ 下的产物） */
    public record Candidate(String state, double diffPercent, String matchedFile, List<KindScore> kinds) {
    }

    /** 某分类的两个框心坐标：actX/actY = 鼠标点击点（点击区图框心，仅 click 分类有效）、
     *  attnX/attnY = 注意点（注意区图框心，每个分类都有）；缺失的坐标以 -1 表示（该方框图比对直接返回 -1）。
     *  用独立 record 传参，避免原先 (acx, acy, ccx, ccy) 四个 int 位置传错而编译器无感。 */
    public record Centers(int actX, int actY, int attnX, int attnY) {
    }


    /**
     * 按运行时算法识别一张画面（执行模式与「未标注」里的单图智能推荐统一入口）。
     *
     * @param frame 最新一张画面截图（须与 resource/runtime/ 产物同分辨率：窗口先经 resize 对齐，比对不做缩放）
     * @param root  resource/runtime/ 目录（算法调优「综合最佳算法」的落地目录）
     * @param algo  运行时算法（{@code resource/runtime/algorithm.json}，见 {@link RuntimeAlgorithm}）
     * @return 识别输出（无人认可判 / 无法区分时 recognized=false；画面分辨率与全部产物都不一致时抛异常）
     */
    public synchronized Outcome classifyByAlgorithm(BufferedImage frame, Path root, RuntimeAlgorithm algo) {
        long t0 = System.currentTimeMillis();
        Outcome out = new Outcome();
        if (frame == null || root == null || algo == null || algo.features().isEmpty() || algo.states().isEmpty()) {
            return out;
        }
        List<RuntimeAlgorithm.Feature> feats = algo.features();
        List<RuntimeAlgorithm.State> states = algo.states();
        int nf = feats.size();
        int gn = states.size();
        int fw = frame.getWidth();
        int fh = frame.getHeight();
        int[] framePxFull = frame.getRGB(0, 0, fw, fh, null, 0, fw);   // 与产物同分辨率才可逐像素比对（resize 负责对齐）
        FrameWork work = null;                                          // 惰性：扫到首个可比分类才建

        out.totalSamples = gn;
        double[][] match = new double[nf][gn];                  // 匹配值（0~100）；-1 = 该分类没有此特征产物 / 比不了
        boolean[][] artValid = new boolean[nf][gn];             // 该分类此特征有没有「有效产物」（≥1 个不透明像素）= 调优侧的 vv
        List<List<KindScore>> details = new ArrayList<>(gn);    // 各分类逐特征分值明细（差异度口径，供页面展示）
        int expectedW = 0;
        int expectedH = 0;
        for (int j = 0; j < gn; j++) {
            RuntimeAlgorithm.State st = states.get(j);
            details.add(new ArrayList<>());
            if (st.width() > 0 && st.height() > 0 && (st.width() != fw || st.height() != fh)) {
                if (expectedW == 0) {
                    expectedW = st.width();
                    expectedH = st.height();
                }
                out.skipped.put(st.dir(), "产物分辨率 " + st.width() + "x" + st.height()
                        + " 与画面 " + fw + "x" + fh + " 不一致（比对不做图片缩放）");
                continue;
            }
            int ccx = st.attnLeft() != null ? st.attnLeft() : -1;
            int ccy = st.attnTop() != null ? st.attnTop() : -1;
            Centers centers = new Centers(st.actLeft() != null ? st.actLeft() : ccx,
                    st.actTop() != null ? st.actTop() : ccy, ccx, ccy);
            if (work == null) {
                work = new FrameWork(framePxFull, fw, fh);
            }
            boolean any = false;
            for (int f = 0; f < nf; f++) {
                RuntimeAlgorithm.Feature ft = feats.get(f);
                Path png = st.file(root, ft.kind());
                CachedPx ref = png == null ? null : loadCached(png, ft.kind());
                double bad = ref == null ? -1 : verifyKindScore(work, ref, ft.kind(), centers);
                if (bad < 0) {
                    match[f][j] = -1;   // 产物缺失 / 解码不出 / 口径不符：该特征对这个分类不参与
                    continue;
                }
                match[f][j] = 100.0 - bad;   // 匹配值 = 100 − 不匹配点占比
                // 「有效产物」= 至少一个不透明像素（与调优侧 vv / VerifyService.hasPixels 同口径）：
                // 匹配值 > 0 必然有有效像素，只有恰好 0 分时才需要实扫一次
                artValid[f][j] = match[f][j] > 0 || VerifyService.hasPixels(ref);
                details.get(j).add(new KindScore(ft.kind(), png.getFileName().toString(), ref.w, ref.h, bad));
                any = true;
            }
            if (!any) {
                out.skipped.put(st.dir(), "算法用到的特征产物在本分类缺失 / 无法解码");
            }
        }
        if (expectedW > 0 && work == null) {
            throw new IllegalArgumentException("画面分辨率 " + fw + "x" + fh + " 与运行时算法产物 "
                    + expectedW + "x" + expectedH + " 不一致：比对不做图片缩放，请先让窗口/截图对齐到目标分辨率");
        }

        // ② 打平剔除（口径见类注释；与算法调优的评价同口径：那种特征对本次判定没有区分力，
        // 留着只会把各分类的匹配度一起拉平。并列只在有有效产物的分类之间数，否则会把本可用的特征误判成打平）
        boolean[] use = new boolean[nf];
        for (int f = 0; f < nf; f++) {
            double best = -1;
            for (int j = 0; j < gn; j++) {
                if (artValid[f][j] && match[f][j] > best) {
                    best = match[f][j];
                }
            }
            if (best < 0) {
                continue;
            }
            int at = 0;
            for (int j = 0; j < gn; j++) {
                if (artValid[f][j] && match[f][j] >= best - TIE_EPS) {
                    at++;
                }
            }
            use[f] = at < 2;
        }

        // ③ 加权匹配度（只累加该分类可用特征；X × Y 全为 0 时退化为等权平均）
        double[] score = new double[gn];
        boolean[] ok = new boolean[gn];
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestCount = 0;
        int scanned = 0;
        for (int j = 0; j < gn; j++) {
            double sumW = 0;
            double sumWV = 0;
            double sumV = 0;
            int valid = 0;
            for (int f = 0; f < nf; f++) {
                if (!use[f] || match[f][j] < 0) {
                    continue;
                }
                double weight = feats.get(f).x() * feats.get(f).y();
                sumW += weight;
                sumWV += weight * match[f][j];
                sumV += match[f][j];
                valid++;
            }
            if (valid == 0) {
                out.skipped.put(states.get(j).dir(), "本画面该分类的算法特征被全部放弃（或产物缺失）");
                continue;
            }
            score[j] = Math.abs(sumW) < 1e-9 ? sumV / valid : sumWV / sumW;
            ok[j] = true;
            scanned++;
            // 并列计数与算法调优一字不差（见 AlgoEval.eval）：容差内的「更高分」不算刷新最高分，只算再并列一个
            if (score[j] > bestScore + TIE_EPS) {
                bestScore = score[j];
                bestCount = 1;
            } else if (score[j] >= bestScore - TIE_EPS) {
                bestCount++;
            }
        }
        out.scannedSamples = scanned;
        out.elapsedMs = System.currentTimeMillis() - t0;

        if (scanned > 0) {
            List<Integer> order = new ArrayList<>(scanned);
            for (int j = 0; j < gn; j++) {
                if (ok[j]) {
                    order.add(j);
                }
            }
            order.sort((a, b) -> Double.compare(score[b], score[a]));
            List<Candidate> top = new ArrayList<>(Math.min(7, order.size()));
            for (int i = 0; i < order.size() && i < 7; i++) {
                RuntimeAlgorithm.State st = states.get(order.get(i));
                top.add(new Candidate(st.state(), 100.0 - score[order.get(i)], st.dir(), details.get(order.get(i))));
            }
            out.candidates = top;
            out.bestDiffPercent = 100.0 - bestScore;
            if (bestCount >= 2) {
                out.ambiguous = true;   // 最高匹配度并列 ≥2 个分类 → 无法区分（执行层不动作）
                for (int j = 0; j < gn; j++) {
                    if (ok[j] && score[j] >= bestScore - TIE_EPS) {
                        out.tiedStates.add(states.get(j).state());
                    }
                }
            } else {
                for (int j = 0; j < gn; j++) {
                    if (!ok[j] || score[j] < bestScore - TIE_EPS) {
                        continue;
                    }
                    RuntimeAlgorithm.State st = states.get(j);
                    out.recognized = true;
                    out.bestState = st.state();
                    out.bestFile = st.dir();
                    out.action = st.action();
                    // 暴露给执行层的坐标 = 真实点击点（仅 click 分类有）；缺 act 键时回退注意点
                    out.clickLeft = st.actLeft() != null ? st.actLeft() : st.attnLeft();
                    out.clickTop = st.actTop() != null ? st.actTop() : st.attnTop();
                    break;
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("运行时算法识别完成：algo={}, recognized={}, ambiguous={}, best={} ({}) diff={}%, compared={}/{}",
                    algo.algoName(), out.recognized, out.ambiguous, out.bestState, out.bestFile,
                    String.format("%.2f", out.bestDiffPercent), out.scannedSamples, out.totalSamples);
        }
        logSkippedGroups(out);   // 有分类未参与比对时打一条含目录与原因的告警（节流，防识别循环刷屏）
        return out;
    }

    /** 解码 resource/classify/ 原图全幅像素但不读写缓存（特征验证用：每张样本整轮只在首个 kind 解码一次、像素由 works
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
        // 独有区图（含块图）与基础图公共区全空的空图：没有可判别的点＝无法做区分，比对侧按完全不匹配满值计、照常参与加权
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

    /** 读 resource/classify/ 原图全幅像素但不读写缓存（仅与 fw×fh 同分辨率，不符返回 null）：像素由验证的 works 持有、
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
