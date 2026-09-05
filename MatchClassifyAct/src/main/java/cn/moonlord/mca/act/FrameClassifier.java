package cn.moonlord.mca.act;

import cn.moonlord.mca.config.ExecuteProperties;
import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
import java.util.stream.Stream;

/**
 * 画面识别器（执行模式）：把「当前最新截图」与 summary/ 下每个已汇总分析的分类做像素比对。
 *
 * <p>匹配源是汇总分析产物，而不是 classify/ 的散装样本：每个分类标注（state）经「汇总分析」
 * 会生成 7 张对照图（same 交集 / max 多数 / avg 均值 / maj8·avg8·maj32·avg32 块降采样），
 * 它们把同一分类的多张样本合成成一张张“该状态的代表画面”。
 *
 * <p>匹配口径：把每个对应像素当作 (R,G,B) 三维空间中的一点，两点欧氏距离
 * √(ΔR²+ΔG²+ΔB²) ≥ {@code execute.rgb-dist-threshold}（默认 256）即判该点为「不匹配」；
 * 7 张图分别与当前画面按同一缩放口径逐点判定并统计各自的「不匹配点占比」（0~100，
 * 交集图的透明非公共像素不参与统计），再取 7 张图的算术平均作为该分类的差异度；
 * 不同分类按各自的平均占比比较，最小者即为最近似分类。
 * 仅当最近似平均占比 ≤ {@code execute.match-threshold-percent}（默认 25%）时才判定为「已识别」，
 * 否则视为未识别画面（最近似分类仅作参考展示）。
 *
 * <p>坐标可靠性：整个工程靠 resize 把窗口/截图尺寸强制对齐到与标注样本一致，summary 产物
 * 与当前画面天然等尺寸、像素一一对应，因此命中分类记录在 info.json 里的点击坐标可直接用于执行动作。
 *
 * <p>性能：全幅图（same/max/avg）比对时横/纵每隔 {@link #FULL_SAMPLE_STEP} 像素取 1 点
 * （约 1/16 采样）；1/8、1/32 块图本来就小，直接逐像素比较。每个产物像素缓存带 mtime/size 失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameClassifier {

    /** 全幅对照图（same/max/avg）比对时的抽样步长：横、纵都每隔 4 像素取 1 点（约 1/16）。 */
    private static final int FULL_SAMPLE_STEP = 4;

    /** 1/8、1/32 块图参与比较的块边长与口径：{块边长, 1=块内多数 / 0=块内均值}；全幅图不压缩。 */
    private static final Map<String, int[]> KIND_DOWN = Map.of(
            "same", new int[]{1, 0},
            "max", new int[]{1, 0},
            "avg", new int[]{1, 0},
            "m8", new int[]{8, 1},
            "a8", new int[]{8, 0},
            "m32", new int[]{32, 1},
            "a32", new int[]{32, 0});

    /** 7 张对照图的文件名（与 ThinkService 产物保持一致）。 */
    private static final Map<String, String> KIND_FILE = Map.of(
            "same", "same.png",
            "max", "max.png",
            "avg", "avg.png",
            "m8", "maj8.png",
            "a8", "avg8.png",
            "m32", "maj32.png",
            "a32", "avg32.png");

    /** 7 张对照图的固定展示/比较顺序（same 交集 → max/avg 全幅 → 8 块 → 32 块）。 */
    private static final List<String> KIND_ORDER =
            List.of("same", "max", "avg", "m8", "a8", "m32", "a32");

    /** 产物像素缓存的 LRU 容量上限，防止分类数量膨胀时内存失控。 */
    private static final int MAX_CACHE = 400;

    /** 全幅图参与比对的有效像素占比下限：交集图公共区域太少时该图信噪比过低，跳过不参与平均。 */
    private static final double MIN_OVERLAP_RATIO = 0.005;

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

    /** 识别输出（内部使用，ExecutionService 负责把它转成对外 Snapshot）。 */
    public static final class Outcome {
        /** 是否「已识别」：最近似分类的平均差异 ≤ 阈值。 */
        public boolean recognized;
        /** 最近似分类标注（未识别时也填最近似结果，便于界面展示参考）。 */
        public String bestState;
        /** 最近似分类的「7 图平均不匹配点占比」百分比（0~100，越低越像）。 */
        public double bestDiffPercent = Double.NaN;
        /** 命中分类的汇总产物目录名（summary/<dir>），未命中时 null。 */
        public String bestFile;
        /** 命中分类定义的动作（click / none / other），来自 info.json。 */
        public String action;
        /** 命中分类定义的点击坐标（无点击动作时 null）。 */
        public Integer clickLeft;
        /** 命中分类定义的点击坐标（无点击动作时 null）。 */
        public Integer clickTop;
        /** 实际参与比较（7 图齐全且算出有效平均差异）的分类数。 */
        public int scannedSamples;
        /** summary/ 下存在有效分类标注的产物目录数（含 7 图不全被跳过的）。 */
        public int totalSamples;
        /** 各分类的最近似结果，按平均差异升序排列（前几个即“候选分类”）。 */
        public List<Candidate> candidates = new ArrayList<>();
        /** 识别耗时（毫秒）。 */
        public long elapsedMs;
    }

    /** 一张对照图（某个 kind 产物）与该次识别画面的比对明细。score < 0 表示该图未参与平均（缺失或公共区过少）。 */
    public record KindScore(String kind, String file, int w, int h, double score) {
    }

    /** 一个候选：某分类 7 张对照图的平均差异 + 各图各自的分值明细（matchedFile 目录下的产物）。 */
    public record Candidate(String state, double diffPercent, String matchedFile, List<KindScore> kinds) {
    }

    private static final class GroupBest {
        String state;
        double diff = Double.POSITIVE_INFINITY;
        String dir;
        String action;
        Integer clickLeft;
        Integer clickTop;
        /** 成为该分类最优产物目录那一轮的 7 图分值明细（按 KIND_ORDER 顺序）。 */
        Map<String, KindScore> kindScores = new LinkedHashMap<>();
    }

    /**
     * 对一张截图做状态识别。
     *
     * @param frame 最新一张画面截图（任意尺寸；按各分类产物尺寸缩放对齐后比较）
     * @return 识别输出（可能为“未识别”，此时 bestState 仍给出最近似参考）
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
        int[] framePxFull = frame.getRGB(0, 0, fw, fh, null, 0, fw);   // 与产物同尺寸时直接复用
        Map<String, int[]> scaledPx = new LinkedHashMap<>();            // 按产物尺寸缓存缩放像素

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
                continue;   // 7 张对照图不齐（未做汇总分析）无法比较
            }
            Number wObj = info.get("width") instanceof Number n ? n : null;
            Number hObj = info.get("height") instanceof Number n ? n : null;
            if (wObj == null || hObj == null || wObj.intValue() <= 0 || hObj.intValue() <= 0) {
                continue;
            }
            int w = wObj.intValue();
            int h = hObj.intValue();

            double sumDiff = 0;
            int kindCount = 0;
            Map<String, KindScore> scores = new LinkedHashMap<>();   // 该目录 7 张图各自的分值（缺失/不可比 = -1）
            for (String kind : KIND_ORDER) {
                String file = KIND_FILE.get(kind);
                Path art = dir.resolve(file);
                CachedPx ref = loadCached(art);
                KindScore ks = (ref == null) ? new KindScore(kind, file, 0, 0, -1)
                        : new KindScore(kind, file, ref.w, ref.h,
                        compareKind(framePxFull, fw, fh, w, h, ref, kind, scaledPx));
                scores.put(kind, ks);
                if (ks.score() >= 0) {
                    sumDiff += ks.score();
                    kindCount++;
                }
            }
            if (kindCount == 0) {
                continue;   // 7 张图都无法有效比较
            }
            scanned++;
            double avg = sumDiff / kindCount;
            GroupBest g = perState.computeIfAbsent(state, s -> new GroupBest());
            g.state = state;
            if (avg < g.diff) {
                g.diff = avg;
                g.dir = dir.getFileName().toString();
                g.kindScores = scores;   // 只保留该分类“最优产物目录”那一轮的 7 图分值
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
            out.recognized = top.diff <= executeProperties.getMatchThresholdPercent();
        }
        out.elapsedMs = System.currentTimeMillis() - t0;
        if (log.isDebugEnabled()) {
            log.debug("画面识别完成：recognized={}, best={} ({}) avgDiff={}%, compared={}/{}",
                    out.recognized, out.bestState, out.bestFile, String.format("%.2f", out.bestDiffPercent),
                    out.scannedSamples, out.totalSamples);
        }
        return out;
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
     * 把当前画面按某张产物的口径压到同一尺度后比较，返回该图「不匹配点占比」百分比（0~100）：
     * 逐点按 RGB 三维空间欧氏距离 ≥ {@code execute.rgb-dist-threshold} 判为不匹配点并统计占比；
     * 无法对齐/无有效公共像素时返回 -1（该图不参与平均）。
     */
    private double compareKind(int[] framePxFull, int fw, int fh, int w, int h,
                               CachedPx ref, String kind, Map<String, int[]> scaledPx) {
        int[] down = KIND_DOWN.get(kind);
        if (down == null || down.length != 2) {
            return -1;
        }
        int block = down[0];
        boolean majority = down[1] == 1;
        int distThr = executeProperties.getRgbDistThreshold();

        if (block == 1) {
            // 全幅对照图：当前画面需缩放到与产物相同尺寸后，双方同用 1/4 抽样网格比较
            int[] basePx = basePixels(framePxFull, fw, fh, w, h, scaledPx);
            if (basePx == null) {
                return -1;
            }
            if (!ref.sampled) {
                return -1;
            }
            return mismatchPercent(sampleStep(basePx, w, h, FULL_SAMPLE_STEP), ref.px, distThr);
        }
        // 1/8、1/32 块图：把当前画面按块压缩到产物同尺度（整块对齐、右侧/底部不足整块忽略），
        // 产物本身也是同一 floor 网格生成，尺寸天然一致。
        int[] basePx = basePixels(framePxFull, fw, fh, w, h, scaledPx);
        if (basePx == null) {
            return -1;
        }
        if (ref.sampled) {
            return -1;
        }
        int wb = Math.max(1, w / block);
        int hb = Math.max(1, h / block);
        if (ref.w != wb || ref.h != hb) {
            return -1;
        }
        return mismatchPercent(blockDown(basePx, w, h, block, wb, hb, majority), ref.px, distThr);
    }

    /** 取一张与产物同尺寸的当前画面像素：尺寸一致直接复用整帧数组，否则缩放后缓存。 */
    private int[] basePixels(int[] framePxFull, int fw, int fh, int w, int h, Map<String, int[]> scaledPx) {
        if (w == fw && h == fh) {
            return framePxFull;
        }
        String key = w + "x" + h;
        int[] hit = scaledPx.get(key);
        if (hit != null) {
            return hit;
        }
        if (w <= 0 || h <= 0) {
            return null;
        }
        BufferedImage src = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, fw, fh, framePxFull, 0, fw);
        BufferedImage sc = scaleTo(src, w, h);
        int[] px = sc.getRGB(0, 0, w, h, null, 0, w);
        scaledPx.put(key, px);
        return px;
    }

    /* ---------------- 像素采样 / 块压缩 / 差异 ---------------- */

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
     * 两段已对齐像素序列的「不匹配点占比」（0~100）：把每个对应像素当 (R,G,B) 三维空间的一点，
     * 计算两点欧氏距离 √(ΔR²+ΔG²+ΔB²)；距离 ≥ distThr 判为不匹配点，否则视为匹配点。
     * 结果为不匹配点数 / 有效点数 × 100。参考序列（b，即产物）的透明像素
     * （交集图非公共区域）不参与统计。长度不一致或无有效公共像素时返回 -1。
     * <p>与逐通道线性平均色差不同：三通道的差异是联动比较的，任一颜色方向偏得够远都算“不一致”，
     * 不受背景大片近似色的平均稀释。</p>
     */
    private static double mismatchPercent(int[] a, int[] b, int distThr) {
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }
        // 欧氏距离比较对阈值单调，直接用「距离平方 ≥ 阈值平方」判定，省掉每点开平方
        long thr2 = (long) distThr * distThr;
        long bad = 0;
        long n = 0;
        for (int i = 0; i < a.length; i++) {
            int cb = b[i];
            if (((cb >>> 24) & 0xff) < 0x80) {
                continue;
            }
            int ca = a[i];
            long dr = ((ca >> 16) & 0xff) - ((cb >> 16) & 0xff);
            long dg = ((ca >> 8) & 0xff) - ((cb >> 8) & 0xff);
            long db = (ca & 0xff) - (cb & 0xff);
            if (dr * dr + dg * dg + db * db >= thr2) {
                bad++;
            }
            n++;
        }
        if (n < Math.max(16, a.length * MIN_OVERLAP_RATIO)) {
            return -1;   // 有效公共像素过少（如同类图基本全透明），视为不可比
        }
        return bad * 100.0 / n;
    }

    /* ---------------- 产物读取与缓存 ---------------- */

    /** 读取一张产物图并缓存（全幅图缓存抽样序列；块图缓存整幅小图），解码失败返回 null。 */
    private CachedPx loadCached(Path png) {
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
        String name = png.getFileName().toString();
        boolean isFull = name.equals(KIND_FILE.get("same")) || name.equals(KIND_FILE.get("max"))
                || name.equals(KIND_FILE.get("avg"));
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

    /** 7 张对照图是否齐全。 */
    private static boolean artifactsComplete(Path gdir) {
        for (String f : KIND_FILE.values()) {
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

    /** 尺寸不一致时缩放到 w×h（双线性）。 */
    private static BufferedImage scaleTo(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) {
            return src;
        }
        BufferedImage out = new BufferedImage(Math.max(w, 1), Math.max(h, 1), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, Math.max(w, 1), Math.max(h, 1), null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
