package cn.moonlord.mca.act;

import cn.moonlord.mca.config.ExecuteProperties;
import cn.moonlord.mca.mark.CaptureMark;
import cn.moonlord.mca.mark.ClassifyStore;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画面识别器：把「当前最新截图」与 classify/ 下每一张已标注样本逐张做像素比较，
 * 为每个分类保留“与该分类最像的一张样本”的差异度，差异度最小者即为最近似分类；
 * 只有当最小差异 ≤ execute.match-threshold-percent（默认 25%）时才判定为「已识别」，
 * 否则视为未识别画面（最近似结果仅作参考展示）。
 *
 * <p>坐标可靠性：整个工程靠 resize 把窗口/截图尺寸强制对齐到与标注样本一致，
 * 因此当前画面与样本天然等尺寸、像素坐标一一对应，识别结果里的点击点可直接用于执行动作。</p>
 *
 * <p>性能：全图逐像素比较（1280×720）对大量样本代价偏高，这里对横/纵每隔
 * {@link #SAMPLE_STEP} 像素取 1 点参与比较（约 1/16 采样），并缓存样本的抽样像素
 * （带 mtime 失效），整屏状态识别无需全分辨率。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameClassifier {

    /** 抽样步长：横、纵都每隔 4 像素取 1 点（约 1/16 画面像素），足够区分“整屏状态”又很快。 */
    private static final int SAMPLE_STEP = 4;

    /** 样本抽样像素缓存的 LRU 容量上限，防止 classify/ 数量膨胀时内存失控。 */
    private static final int MAX_CACHE = 400;

    private final ClassifyStore classifyStore;
    private final ExecuteProperties executeProperties;

    /** 命中样本的像素缓存：key = 文件绝对路径。 */
    private final Map<String, SamplePixels> cache = new LinkedHashMap<>(64, 0.75f, true);

    private static final class SamplePixels {
        final long lastModified;
        final long size;
        final int width;
        final int height;
        final int[] px;

        SamplePixels(long lastModified, long size, int width, int height, int[] px) {
            this.lastModified = lastModified;
            this.size = size;
            this.width = width;
            this.height = height;
            this.px = px;
        }
    }

    /** 识别输出（内部使用，ExecutionService 负责把它转成对外 Snapshot）。 */
    public static final class Outcome {
        /** 是否「已识别」：最近似分类的最小差异 ≤ 阈值。 */
        public boolean recognized;
        /** 最近似分类标注（未识别时也填最近似结果，便于界面展示参考）。 */
        public String bestState;
        /** 最近似样本的平均像素差异百分比（0~100）。 */
        public double bestDiffPercent = Double.NaN;
        /** 最近似样本文件名（如 IMG_xxx.png）。 */
        public String bestFile;
        /** 实际参与比较的同尺寸样本数。 */
        public int scannedSamples;
        /** classify/ 中存在有效分类标注的样本总数（含尺寸不匹配被跳过的）。 */
        public int totalSamples;
        /** 各分类的最近似结果，按差异度升序排列（前几个即“候选分类”）。 */
        public List<Candidate> candidates = new ArrayList<>();
        /** 识别耗时（毫秒）。 */
        public long elapsedMs;
    }

    /** 一个候选：某分类离当前画面最近的那张样本的差异度。 */
    public record Candidate(String state, double diffPercent, String matchedFile) {
    }

    private static final class StateBest {
        double diff = Double.POSITIVE_INFINITY;
        String file;
    }

    /**
     * 对一张截图做状态识别。
     *
     * @param frame 最新一张画面截图（任意尺寸；只与同尺寸样本比较）
     * @return 识别输出（可能为“未识别”，此时 bestState 仍给出最近似参考）
     */
    public synchronized Outcome classify(BufferedImage frame) {
        long t0 = System.currentTimeMillis();
        Outcome out = new Outcome();
        if (frame == null) {
            return out;
        }
        int fw = frame.getWidth();
        int fh = frame.getHeight();
        int[] framePx = samplePixels(frame);

        Map<String, StateBest> perState = new LinkedHashMap<>();
        int total = 0;
        int scanned = 0;

        for (Path png : classifyStore.listClassifiedPngs()) {
            String name = png.getFileName().toString();
            CaptureMark mark;
            try {
                mark = classifyStore.readSample(name);
            } catch (Exception e) {
                log.debug("读取样本 {} 失败，跳过：{}", name, e.toString());
                continue;
            }
            String state = mark == null ? null : mark.getState();
            if (state == null || (state = state.trim()).isEmpty()) {
                continue; // 还没被标注的分类归属不参与识别
            }
            total++;

            SamplePixels sp;
            try {
                sp = loadCached(png);
            } catch (Exception e) {
                log.debug("样本 {} 解码失败，跳过：{}", name, e.toString());
                continue;
            }
            if (sp.width != fw || sp.height != fh || sp.px.length != framePx.length) {
                continue; // 尺寸不一致无法逐像素对齐（正常情况不会发生，因为 resize 已强制对齐）
            }
            scanned++;
            double diff = diffPercent(framePx, sp.px);
            StateBest best = perState.computeIfAbsent(state, s -> new StateBest());
            if (diff < best.diff) {
                best.diff = diff;
                best.file = name;
            }
        }

        List<CandidateRef> sorted = new ArrayList<>();
        for (Map.Entry<String, StateBest> e : perState.entrySet()) {
            sorted.add(new CandidateRef(e.getKey(), e.getValue()));
        }
        sorted.sort((a, b) -> Double.compare(a.diff, b.diff));
        int n = Math.min(sorted.size(), 6);
        for (int i = 0; i < n; i++) {
            CandidateRef c = sorted.get(i);
            out.candidates.add(new Candidate(c.state, c.diff, c.file));
        }

        out.scannedSamples = scanned;
        out.totalSamples = total;
        if (!sorted.isEmpty()) {
            CandidateRef top = sorted.get(0);
            out.bestState = top.state;
            out.bestDiffPercent = top.diff;
            out.bestFile = top.file;
            out.recognized = top.diff <= executeProperties.getMatchThresholdPercent();
        }
        out.elapsedMs = System.currentTimeMillis() - t0;
        if (log.isDebugEnabled()) {
            log.debug("画面识别完成：recognized={}, best={} ({}) diff={}%, scanned={}/{}",
                    out.recognized, out.bestState, out.bestFile, String.format("%.2f", out.bestDiffPercent),
                    out.scannedSamples, out.totalSamples);
        }
        return out;
    }

    /* ---------------- 像素采样与缓存 ---------------- */

    private int[] samplePixels(BufferedImage im) {
        int w = im.getWidth();
        int h = im.getHeight();
        int cw = (w + SAMPLE_STEP - 1) / SAMPLE_STEP;
        int ch = (h + SAMPLE_STEP - 1) / SAMPLE_STEP;
        int[] all = im.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[cw * ch];
        int idx = 0;
        for (int y = 0; y < h; y += SAMPLE_STEP) {
            int base = y * w;
            for (int x = 0; x < w; x += SAMPLE_STEP) {
                out[idx++] = all[base + x];
            }
        }
        return out;
    }

    private SamplePixels loadCached(Path png) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(png, BasicFileAttributes.class);
        String key = png.toAbsolutePath().toString();
        SamplePixels hit = cache.get(key);
        if (hit != null && hit.lastModified == attrs.lastModifiedTime().toMillis() && hit.size == attrs.size()) {
            return hit;
        }
        BufferedImage im = ImageIO.read(png.toFile());
        if (im == null) {
            cache.remove(key);
            throw new IOException("无法解码图片: " + png);
        }
        SamplePixels sp = new SamplePixels(
                attrs.lastModifiedTime().toMillis(), attrs.size(),
                im.getWidth(), im.getHeight(), samplePixels(im));
        cache.put(key, sp);
        while (cache.size() > MAX_CACHE) {
            Iterator<SamplePixels> it = cache.values().iterator();
            it.next();
            it.remove();
        }
        return sp;
    }

    /** 两段已抽样的像素序列的平均 RGB 差异百分比（0~100）。 */
    private double diffPercent(int[] a, int[] b) {
        long sum = 0;
        int n = a.length;
        for (int i = 0; i < n; i++) {
            int ca = a[i];
            int cb = b[i];
            sum += Math.abs(((ca >> 16) & 0xff) - ((cb >> 16) & 0xff));
            sum += Math.abs(((ca >> 8) & 0xff) - ((cb >> 8) & 0xff));
            sum += Math.abs((ca & 0xff) - (cb & 0xff));
        }
        return sum * 100.0 / (n * 3L * 255);
    }

    /* 排序用的临时内部结构 */
    private static final class CandidateRef {
        final String state;
        final double diff;
        final String file;

        CandidateRef(String state, StateBest best) {
            this.state = state;
            this.diff = best.diff;
            this.file = best.file;
        }
    }
}
