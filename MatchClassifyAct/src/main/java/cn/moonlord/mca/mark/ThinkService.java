package cn.moonlord.mca.mark;

import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 汇总分析 · 同一「分类标注（state）」（动作一致）截图组的逐像素对照与记录。
 *
 * <p>界面语言里：分类标注 = 界面上的文本标签（数据字段 state），匹配动作 = none/click
 * （数据字段 action）；同一分类标注只允许一种匹配动作（控制台已按此规则约束）。
 * 本服务对每组截图逐像素分析，产出七张对照图：</p>
 * <ol>
 *   <li><b>交集图</b>（same.png）：每个像素取“覆盖大于 90%”的值——统计所有样本在该像素的
 *       颜色，覆盖率 = 该颜色出现的样本占比；覆盖 &gt;90%（达标样本数向上取整）时该像素以该
 *       主流颜色保留为不透明，否则透明。不透明区 = 样本间公共（稳定）画面。</li>
 *   <li><b>多数图</b>（max.png）：每个像素取“覆盖率最多”的颜色——逐像素统计所有样本的颜色，
 *       取出现次数最多（同票取样本顺序靠前）的颜色作为该点颜色。</li>
 *   <li><b>均值图</b>（avg.png）：每个像素对全部样本的 R、G、B 分别取平均，
 *       得到一张“各点平均颜色”的合成画面（透明通道统一视为不透明）。</li>
 *   <li><b>1/8 多数图</b>（maj8.png）：按 8×8 网格把所有样本对齐切块，将「同位置的块内全部
 *       像素」跨样本合并成一个集合，输出该集合中出现次数最多的颜色（覆盖率最多，同票取样本顺序
 *       靠前）；即一个输出像素 = 全部样本同一 8×8 块内所有原始像素的众数色，不分步压缩。</li>
 *   <li><b>1/32 多数图</b>（maj32.png）：同上，块为 32×32。</li>
 *   <li><b>1/8 均值图</b>（avg8.png）：按 8×8 网格把所有样本对齐切块，将「同位置的块内全部
 *       像素」跨样本合并成一个集合，输出该集合全部像素 R/G/B 的总平均色。</li>
 *   <li><b>1/32 均值图</b>（avg32.png）：同上，块为 32×32。</li>
 * </ol>
 *
 * <p>产物统一放在 {@code summary/<分类标注>/} 目录下（capture/classify/summary 三阶段布局见
 * {@link StoragePaths}），七张图使用固定文件名：
 * {@code same.png / max.png / avg.png / maj8.png / avg8.png / maj32.png / avg32.png}，
 * 分析信息（样本数、覆盖率、公共点击坐标等）
 * 写入同目录 {@code info.json}。产物仅供人工目检展示，<b>不参与任何标注 / 结论决策</b>，
 * 画面标签一律以控制台的人工标注为准。</p>
 *
 * <p>样本截图只读 classify/（已标注的截图 + .json）；单图智能建议的目标图（未标注）
 * 只读 capture/。旧版单目录布局 captures/（含 sum/、think/ 与标注混排）已由
 * {@code config/LegacyStorageMigrator} 启动时自动迁移，本服务不再处理。</p>
 *
 * <p>截图来自同一窗口同一坐标系，画面位置固定，只需逐像素同位比较，无需平移匹配。</p>
 */
@Slf4j
@Service
public class ThinkService {

    /** 标注文件统一 UTF-8 + 缩进 + 忽略 null 字段（与 AnnotateController 一致） */
    private static final ObjectMapper JSON = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter TS_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /** 每组合（一个分类标注目录）内固定文件名 */
    private static final String FILE_SAME = "same.png";   // 交集图（覆盖>90%）
    private static final String FILE_MAX = "max.png";     // 多数图
    private static final String FILE_AVG = "avg.png";     // 均值图
    private static final String FILE_M8 = "maj8.png";     // 1/8 多数图（8×8 块）
    private static final String FILE_A8 = "avg8.png";     // 1/8 均值图（8×8 块）
    private static final String FILE_M32 = "maj32.png";   // 1/32 多数图（32×32 块）
    private static final String FILE_A32 = "avg32.png";   // 1/32 均值图（32×32 块）
    private static final String FILE_INFO = "info.json";

    /** kind → 产物文件名 */
    private static final Map<String, String> KIND_FILE = Map.of(
        "same", FILE_SAME,
        "max", FILE_MAX,
        "avg", FILE_AVG,
        "m8", FILE_M8,
        "a8", FILE_A8,
        "m32", FILE_M32,
        "a32", FILE_A32);

    /** 逐行像素处理时的行带高，控制峰值内存 */
    private static final int BAND_H = 64;

    /** same.png 交集图判定：某像素的颜色在样本中的一致占比 ≥ 该阈值即视为公共（稳定）像素 */
    private static final double SAME_AGREE_RATIO = 0.90;

    /** 产物生成规则版本：改动产物生成逻辑后递增，使旧产物自动判 stale 并重算 */
    private static final int ART_RULE_VERSION = 5;

    /** 单线程池：像素比对较重，串行避免并发打满 CPU */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mca-think");
        t.setDaemon(true);
        return t;
    });

    private final StoragePaths storage;
    private final ClassifyStore classifyStore;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /** 单图智能建议任务 */
    private final Map<String, SuggestTask> suggestTasks = new ConcurrentHashMap<>();
    /** 建议结果缓存：key = 目标图|产物签名，避免同一张图反复重算（最多留 60 条，按访问序淘汰） */
    private final Map<String, List<Map<String, Object>>> suggestCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Map<String, Object>>> eldest) {
            return size() > 60;
        }
    };

    public ThinkService(StoragePaths storage, ClassifyStore classifyStore) {
        this.storage = storage;
        this.classifyStore = classifyStore;
    }

    /* ---------------------------------------------------------------- 对外 API */

    /**
     * 分析全部待分析的组合并写入/刷新 `summary/<分类标注>/` 下产物。
     *
     * @param force true = 无视已有产物全部重算
     */
    public String startAnalyze(boolean force) {
        String id = UUID.randomUUID().toString();
        Task t = new Task(id, force);
        tasks.put(id, t);
        pool.submit(() -> runAnalyze(t));
        return id;
    }

    /** 任务快照；不存在返回 null */
    public Task task(String taskId) {
        return taskId == null ? null : tasks.get(taskId);
    }

    /* ---------------------------------------------------------------- 智能建议（未标注图 × 已生成的七张对照图） */

    /** 单图智能建议任务：把一张未标注截图与每个分类标注已生成的七张对照图做逐像素相似度比对 */
    public static class SuggestTask {
        public final String taskId;
        public final String file;
        public volatile String status = "running";   // running / done / error
        public volatile String message = "";
        /** 候选组，按 score（最高像素一致率）降序 */
        public volatile List<Map<String, Object>> candidates = List.of();

        SuggestTask(String taskId, String file) {
            this.taskId = taskId;
            this.file = file;
        }
    }

    /** 启动单图智能建议（与批量分析共用后台串行池，避免并发打满 CPU） */
    public String startSuggest(String file) {
        String id = UUID.randomUUID().toString();
        if (suggestTasks.size() > 100) {
            suggestTasks.entrySet().removeIf(e -> !"running".equals(e.getValue().status));
        }
        SuggestTask t = new SuggestTask(id, file);
        suggestTasks.put(id, t);
        pool.submit(() -> runSuggest(t, file));
        return id;
    }

    /** 建议任务快照；不存在返回 null */
    public SuggestTask suggestTask(String taskId) {
        return taskId == null ? null : suggestTasks.get(taskId);
    }

    private void runSuggest(SuggestTask t, String file) {
        try {
            Path png = suggestPng(file);
            if (png == null) {
                t.status = "error";
                t.message = "截图不存在或尚未写入完成：" + file;
                return;
            }
            BufferedImage target = ImageIO.read(png.toFile());
            if (target == null) {
                t.status = "error";
                t.message = "无法解码截图：" + file;
                return;
            }
            String sig = suggestSig(png, target);
            synchronized (suggestCache) {
                List<Map<String, Object>> hit = suggestCache.get(file + "|" + sig);
                if (hit != null) {
                    t.candidates = hit;
                    t.status = "done";
                    return;
                }
            }
            List<Map<String, Object>> out = compareWithGroups(target);
            synchronized (suggestCache) {
                suggestCache.put(file + "|" + sig, out);
            }
            t.candidates = out;
            t.status = "done";
        } catch (Exception e) {
            log.warn("智能建议 分析失败 {}: {}", file, e.toString());
            t.status = "error";
            t.message = "分析失败：" + e.getMessage();
        }
    }

    /** 建议结果签名：目标图 + 每个已分析分类产物目录内四图/info 的尺寸与修改时间（任一产物重算即失效） */
    private String suggestSig(Path png, BufferedImage target) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(png.getFileName()).append('|').append(Files.size(png)).append('|')
              .append(Files.getLastModifiedTime(png).toMillis()).append('|')
              .append(target.getWidth()).append('x').append(target.getHeight()).append('|');
        } catch (IOException e) {
            sb.append("png?|");
        }
        Path sum = storage.summary();
        if (Files.isDirectory(sum)) {
            try (Stream<Path> ds = Files.list(sum)) {
                for (Path d : (Iterable<Path>) ds.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))::iterator) {
                    if (!artifactsComplete(d)) {
                        continue;
                    }
                    sb.append(d.getFileName()).append('{');
                    for (String f : List.of(FILE_SAME, FILE_MAX, FILE_AVG,
                        FILE_M8, FILE_A8, FILE_M32, FILE_A32, FILE_INFO)) {
                        Path p = d.resolve(f);
                        try {
                            sb.append(f).append('=').append(Files.size(p)).append(',')
                              .append(Files.getLastModifiedTime(p).toMillis()).append(';');
                        } catch (IOException e) {
                            sb.append(f).append("=?;");
                        }
                    }
                    sb.append('}');
                }
            } catch (IOException ignored) {
            }
        }
        return sb.toString();
    }

    /** 校验目标截图名：必须是 capture/（未标注原始截图）下 img_*.png 且已写完 */
    private Path suggestPng(String file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        Path dir = storage.capture();
        Path p = dir.resolve(file).normalize();
        if (!p.startsWith(dir) || !isCapturedPng(p)) {
            return null;
        }
        return p;
    }

    /**
     * 目标截图与每个「七张对照图齐全」的分类标注逐像素比对。
     * 相似度口径（对照图透明像素不计入）：
     *   pct   像素一致比例 = RGB 完全一致的同位像素占比（%）
     *   adiff 平均通道色差 = √(Σ(ΔR²+ΔG²+ΔB²) ÷ 像素数 ÷ 3)，0~255，越小越接近
     * 组得分取七张图里一致比例最高的一张，七张图各自指标一并返回供界面展示。
     */
    private List<Map<String, Object>> compareWithGroups(BufferedImage target) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        Path sum = storage.summary();
        if (!Files.isDirectory(sum)) {
            return out;
        }
        List<Path> dirs;
        try (Stream<Path> ds = Files.list(sum)) {
            dirs = ds.filter(Files::isDirectory)
                     .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                     .toList();
        }
        for (Path d : dirs) {
            if (!artifactsComplete(d)) {
                continue;
            }
            Map<String, Object> info = readInfo(d);
            Object wObj = info.get("width"), hObj = info.get("height");
            if (!(wObj instanceof Number wn) || !(hObj instanceof Number hn)) {
                continue;
            }
            int w = wn.intValue();
            int h = hn.intValue();
            if (w <= 0 || h <= 0) {
                continue;
            }
            BufferedImage base = (target.getWidth() == w && target.getHeight() == h)
                ? target : scaleTo(target, w, h);
            List<Map<String, Object>> kinds = new ArrayList<>(7);
            double bestPct = 0;
            String bestKind = null;
            double bestAdiff = 0;
            for (String kind : KIND_FILE.keySet()) {
                Path art = d.resolve(KIND_FILE.get(kind));
                if (!Files.isRegularFile(art)) {
                    continue;
                }
                BufferedImage ref = ImageIO.read(art.toFile());
                if (ref == null) {
                    continue;
                }
                BufferedImage cmp = base;
                int[] dn = kindDown(kind);   // {块边长, 1=块内多数 / 0=块内均值}；边长 1 = 不压缩
                if (dn[0] > 1) {
                    cmp = blockDown(base, dn[0], dn[1] == 1);   // 低分辨率产物同口径：目标也按块压缩再比
                }
                if (cmp.getWidth() != ref.getWidth() || cmp.getHeight() != ref.getHeight()) {
                    cmp = scaleTo(cmp, ref.getWidth(), ref.getHeight());
                }
                double[] m = comparePixels(cmp, ref);
                kinds.add(Map.of("kind", kind, "pct", m[0], "adiff", m[1]));
                if (m[0] > bestPct) {
                    bestPct = m[0];
                    bestKind = kind;
                    bestAdiff = m[1];
                }
            }
            if (bestKind == null) {
                continue;
            }
            Map<String, Object> cand = new LinkedHashMap<>();
            cand.put("state", info.getOrDefault("state", ""));
            cand.put("action", info.getOrDefault("action", CaptureMark.ACTION_NONE));
            cand.put("dir", d.getFileName().toString());
            Object fc = info.get("sampleCount");
            cand.put("sampleCount", fc == null ? 0 : fc);
            cand.put("coverage", info.get("coverage"));
            cand.put("score", bestPct);
            cand.put("scoreKind", bestKind);
            cand.put("adiff", bestAdiff);
            cand.put("kinds", kinds);
            out.add(cand);
        }
        out.sort((a, b) -> Double.compare(
            ((Number) b.get("score")).doubleValue(), ((Number) a.get("score")).doubleValue()));
        return out;
    }

    /** 逐像素比对，返回 {一致比例%, 平均通道色差} */
    private double[] comparePixels(BufferedImage a, BufferedImage b) {
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = a.getRGB(0, 0, w, h, null, 0, w);
        int[] pb = b.getRGB(0, 0, w, h, null, 0, w);
        long overlap = 0, equal = 0, sq = 0;
        for (int i = 0; i < pa.length; i++) {
            int cb = pb[i];
            if (((cb >>> 24) & 0xff) < 0x80) {   // 参考图透明（交集图的非公共像素）不参与统计
                continue;
            }
            overlap++;
            int ca = pa[i];
            int dr = ((ca >>> 16) & 0xff) - ((cb >>> 16) & 0xff);
            int dg = ((ca >>> 8) & 0xff) - ((cb >>> 8) & 0xff);
            int db = (ca & 0xff) - (cb & 0xff);
            if (dr == 0 && dg == 0 && db == 0) {
                equal++;
            } else {
                sq += (long) dr * dr + (long) dg * dg + (long) db * db;
            }
        }
        if (overlap == 0) {
            return new double[]{0, 0};
        }
        double pct = Math.round(equal * 1000.0 / overlap) / 10.0;          // 1 位小数 %
        double adiff = Math.round(Math.sqrt(sq / (3.0 * overlap)) * 10) / 10.0;
        return new double[]{pct, adiff};
    }

    /** 尺寸不一致时缩放到 w×h（双线性） */
    private BufferedImage scaleTo(BufferedImage src, int w, int h) {
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

    /** kind 对应的块压缩参数：{块边长, 1=块内多数 / 0=块内均值}；same/max/avg（不压缩）返回边长 1 */
    private int[] kindDown(String kind) {
        switch (kind) {
            case "m8":
                return new int[]{8, 1};
            case "m32":
                return new int[]{32, 1};
            case "a8":
                return new int[]{8, 0};
            case "a32":
                return new int[]{32, 0};
            default:
                return new int[]{1, 0};
        }
    }

    /** 整图按 block×block 块压缩：多数=块内取覆盖最多的颜色 / 均值=块内 R/G/B 平均，输出约 w/block × h/block 尺寸 */
    private BufferedImage blockDown(BufferedImage src, int block, boolean majority) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        int wb = Math.max(1, w / block), hb = Math.max(1, h / block);
        int[] outPx = blockDown(px, w, h, block, wb, hb, majority);
        BufferedImage out = new BufferedImage(wb, hb, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, wb, hb, outPx, 0, wb);
        return out;
    }

    /** 对像素数组做 block×block 块压缩（多数 / 均值），输出 wb×hb；块按整块对齐，右侧/底部不足整块的像素忽略 */
    private int[] blockDown(int[] px, int w, int h, int block, int wb, int hb, boolean majority) {
        int[] out = new int[wb * hb];
        if (majority) {
            HashMap<Integer, Integer> freq = new HashMap<>();
            for (int oy = 0; oy < hb; oy++) {
                int rowY = oy * block;
                for (int ox = 0; ox < wb; ox++) {
                    int colX = ox * block;
                    freq.clear();
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
            int cnt = block * block;
            for (int oy = 0; oy < hb; oy++) {
                int rowY = oy * block;
                for (int ox = 0; ox < wb; ox++) {
                    int colX = ox * block;
                    long r = 0, g = 0, b = 0;
                    for (int dy = 0; dy < block; dy++) {
                        int base = (rowY + dy) * w + colX;
                        for (int dx = 0; dx < block; dx++) {
                            int c = px[base + dx];
                            r += (c >>> 16) & 0xff;
                            g += (c >>> 8) & 0xff;
                            b += c & 0xff;
                        }
                    }
                    out[oy * wb + ox] = 0xff000000
                        | ((int) ((r + cnt / 2) / cnt) << 16)
                        | ((int) ((g + cnt / 2) / cnt) << 8)
                        | (int) ((b + cnt / 2) / cnt);
                }
            }
        }
        return out;
    }

    /**
     * 1/8、1/32 多数 / 均值图：按 block×block 网格把所有样本对齐切块，将「同位置的块内全部
     * 像素」跨样本合并成一个像素集合（右侧 / 底部不足整块的余边忽略，产物尺寸 w/block × h/block）——
     * 多数图：输出该集合中出现次数最多的颜色（同票取样本顺序靠前的颜色）；
     * 均值图：输出该集合全部像素 R/G/B 的总平均色。即一个输出像素直接对应
     * 「全部样本同一块区域的所有原始像素」，不再先逐张块内压缩再跨样本合成。
     */
    private BufferedImage lowSample(List<BufferedImage> imgs, int w, int h, int block, boolean majority) {
        int wb = Math.max(1, w / block), hb = Math.max(1, h / block);
        int S = imgs.size();
        int[] outPx = new int[wb * hb];
        if (majority) {
            // 跨样本块内合并多数：同一输出行带（原图 block 行）逐输出列统计，控制峰值内存
            int[] buf = new int[w * block];
            int[][] band = new int[S][w * block];
            HashMap<Integer, Integer> freq = new HashMap<>();
            for (int oy = 0; oy < hb; oy++) {
                int y0 = oy * block;
                for (int s = 0; s < S; s++) {
                    imgs.get(s).getRGB(0, y0, w, block, buf, 0, w);
                    System.arraycopy(buf, 0, band[s], 0, buf.length);
                }
                for (int ox = 0; ox < wb; ox++) {
                    int x0 = ox * block;
                    freq.clear();
                    int bestCnt = 0, bestColor = 0;
                    for (int s = 0; s < S; s++) {
                        int[] row = band[s];
                        for (int dy = 0; dy < block; dy++) {
                            int base = dy * w + x0;
                            for (int dx = 0; dx < block; dx++) {
                                int c = row[base + dx];
                                int cnt = freq.merge(c, 1, Integer::sum);
                                if (cnt > bestCnt) {   // 同票保留先出现的颜色（样本顺序靠前）
                                    bestCnt = cnt;
                                    bestColor = c;
                                }
                            }
                        }
                    }
                    outPx[oy * wb + ox] = bestColor | 0xff000000;
                }
            }
        } else {
            // 跨样本块内合并均值：全部样本同位置块内的每个原始像素都计入同一条累加器
            int total = S * block * block;
            long[] sr = new long[wb * hb], sg = new long[wb * hb], sb = new long[wb * hb];
            for (BufferedImage im : imgs) {
                int[] px = im.getRGB(0, 0, w, h, null, 0, w);
                for (int y = 0; y < hb * block; y++) {
                    int rowBase = y * w;
                    int cellRow = (y / block) * wb;
                    for (int x = 0; x < wb * block; x++) {
                        int idx = cellRow + x / block;
                        int c = px[rowBase + x];
                        sr[idx] += (c >>> 16) & 0xff;
                        sg[idx] += (c >>> 8) & 0xff;
                        sb[idx] += c & 0xff;
                    }
                }
            }
            for (int i = 0; i < outPx.length; i++) {
                outPx[i] = 0xff000000
                    | ((int) ((sr[i] + total / 2) / total) << 16)
                    | ((int) ((sg[i] + total / 2) / total) << 8)
                    | (int) ((sb[i] + total / 2) / total);
            }
        }
        BufferedImage out = new BufferedImage(wb, hb, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, wb, hb, outPx, 0, wb);
        return out;
    }

    /**
     * 组合总览：枚举 classify/（已标注截图 + .json）里全部「分类标注（state）+ 动作」组合，
     * 附样本数、产物目录、是否已分析（七张产物齐全）及覆盖率。
     */
    public List<Map<String, Object>> groups() {
        List<Path> pngs = annotatedPngs();
        Map<String, List<CaptureMark>> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> actionsOf = new HashMap<>();
        for (Path p : pngs) {
            CaptureMark m = classifyStore.sampleOf(p);
            if (m == null || trim(m.getState()).isEmpty()) {
                continue;
            }
            String state = trim(m.getState());
            String action = m.getAction() == null ? CaptureMark.ACTION_NONE : m.getAction();
            actionsOf.computeIfAbsent(state, k -> new HashSet<>()).add(action);
            byKey.computeIfAbsent(state + "\u0001" + action, k -> new ArrayList<>()).add(m);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<CaptureMark>> e : byKey.entrySet()) {
            String[] sa = e.getKey().split("\u0001", 2);
            String state = sa[0];
            String action = sa[1];
            List<CaptureMark> marks = e.getValue();
            boolean multiAction = actionsOf.getOrDefault(state, Set.of()).size() > 1;
            String dir = dirNameOf(state, action, multiAction);

            Map<String, Object> g = new LinkedHashMap<>();
            g.put("state", state);
            g.put("action", action);
            g.put("dir", dir);
            g.put("sampleCount", marks.size());
            g.put("canAnalyze", marks.size() >= 2);
            int[] cc = commonClick(marks);
            g.put("clickLeft", cc[0]);
            g.put("clickTop", cc[1]);

            Path gdir = groupDir(dir);
            Map<String, Object> info = readInfo(gdir);
            boolean complete = artifactsComplete(gdir);
            g.put("analyzed", complete);   // 七张产物齐全才算完成
            if (complete) {
                g.put("coverage", info.get("coverage"));
                g.put("width", info.get("width"));
                g.put("height", info.get("height"));
                // 样本数相较上次分析有变化，或产物生成规则版本不一致 → 标记为待重分析
                Object fc = info.get("fileCount");
                boolean ruleChanged = !Integer.valueOf(ART_RULE_VERSION).equals(info.get("artVer"));
                g.put("stale", ruleChanged || (fc != null && !fc.equals(marks.size())));
            } else {
                g.put("coverage", null);
                g.put("width", null);
                g.put("height", null);
                g.put("stale", false);
            }
            out.add(g);
        }
        // 按“匹配度”排序（对应前端列表标题「分类标注列表（按匹配度）」）：
        // 汇总完成（产物齐全且样本未变）的组合排前，匹配度取交集图像素覆盖率（coverage），高者靠前；
        // 匹配度相同按「分类标注 → 动作」文字升序；其余（未分析 / 样本有变待重算 / 不足）排后，同样按文字升序。
        out.sort((a, b) -> {
            boolean ad = Boolean.TRUE.equals(a.get("analyzed")) && !Boolean.TRUE.equals(a.get("stale"));
            boolean bd = Boolean.TRUE.equals(b.get("analyzed")) && !Boolean.TRUE.equals(b.get("stale"));
            if (ad != bd) {
                return ad ? -1 : 1;
            }
            if (ad) {
                Object ca = a.get("coverage"), cb = b.get("coverage");
                double x = ca instanceof Number na ? na.doubleValue() : -1d;
                double y = cb instanceof Number nb ? nb.doubleValue() : -1d;
                int c = Double.compare(y, x);   // 覆盖率（匹配度）降序
                if (c != 0) {
                    return c;
                }
            }
            int c = String.valueOf(a.get("state")).compareTo(String.valueOf(b.get("state")));
            if (c != 0) {
                return c;
            }
            return String.valueOf(a.get("action")).compareTo(String.valueOf(b.get("action")));
        });
        return out;
    }

    /** 读取分析产物 PNG（kind=same|max|avg|m8|a8|m32|a32，dir=产物目录名，禁止穿越）；非法返回 null */
    public Path resolveArtifact(String kind, String dir) {
        String file = KIND_FILE.get(kind);
        if (file == null) {
            return null;
        }
        if (dir == null || dir.isEmpty() || dir.indexOf('/') >= 0 || dir.indexOf('\\') >= 0) {
            return null;
        }
        Path root = storage.summary();
        Path p = root.resolve(dir).resolve(file).normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p)) {
            return null;
        }
        return p;
    }

    /* ---------------------------------------------------------------- 后台分析 */

    private void runAnalyze(Task t) {
        try {
            List<Map<String, Object>> groups = groups();
            List<Map<String, Object>> todo = new ArrayList<>();
            for (Map<String, Object> g : groups) {
                boolean need = Boolean.TRUE.equals(t.force)
                    || !Boolean.TRUE.equals(g.get("analyzed"))
                    || Boolean.TRUE.equals(g.get("stale"));
                if (Boolean.TRUE.equals(g.get("canAnalyze")) && need) {
                    todo.add(g);
                }
            }
            t.total = todo.size();
            int processed = 0;
            for (Map<String, Object> g : todo) {
                String state = String.valueOf(g.get("state"));
                String action = String.valueOf(g.get("action"));
                t.current = state + " ｜ " + actionLabel(action);
                try {
                    computeGroup(state, action);
                    processed++;
                    t.processed = processed;
                } catch (Exception e) {
                    log.warn("汇总分析 分类 [{}|{}] 分析失败: {}", state, action, e.toString());
                    t.processed = ++processed;
                    t.errors++;
                }
            }
            t.status = "done";
            t.message = todo.isEmpty()
                ? "没有需要分析的组合（所有 ≥2 张样本的组合都已有分析结果）"
                : String.format("完成 %d/%d 个分类的分析", processed, todo.size())
                    + (t.errors > 0 ? "（" + t.errors + " 个失败，详见日志）" : "");
            log.info("汇总分析批量分析结束：{}", t.message);
        } catch (Exception e) {
            log.warn("汇总分析批量分析异常: {}", e.toString());
            t.status = "error";
            t.message = "分析失败：" + e.getMessage();
        }
    }

    /** 计算单个分类（state+action）的七张对照图并刷新产物目录（固定文件名原子替换） */
    private void computeGroup(String state, String action) throws IOException {
        List<Path> pngs = annotatedPngs();
        List<Path> group = new ArrayList<>();
        for (Path p : pngs) {
            CaptureMark m = classifyStore.sampleOf(p);
            if (m != null && trim(state).equals(trim(m.getState()))
                && action.equals(m.getAction() == null ? CaptureMark.ACTION_NONE : m.getAction())) {
                group.add(p);
            }
        }
        if (group.size() < 2) {
            throw new IllegalStateException("样本不足 2 张");
        }

        // click：最常见点击坐标（写入 info.json）
        Integer cx = null, cy = null;
        if (CaptureMark.ACTION_CLICK.equals(action)) {
            Map<Long, Integer> freq = new HashMap<>();
            long bestKey = 0;
            int best = 0;
            for (Path p : group) {
                CaptureMark m = classifyStore.sampleOf(p);
                if (m == null || m.getLeft() == null || m.getTop() == null
                    || m.getLeft() < 0 || m.getTop() < 0) {
                    continue;
                }
                long key = (((long) m.getLeft()) << 32) | (m.getTop() & 0xffffffffL);
                int c = freq.merge(key, 1, Integer::sum);
                if (c > best) {
                    best = c;
                    bestKey = key;
                }
            }
            if (best > 0) {
                cx = (int) (bestKey >> 32);
                cy = (int) bestKey;
            }
        }

        // 解码全部样本（缩放到第一张的尺寸兜底）
        BufferedImage first = ImageIO.read(group.get(0).toFile());
        if (first == null) {
            throw new IOException("无法解码样本 " + group.get(0).getFileName());
        }
        int w = first.getWidth();
        int h = first.getHeight();
        List<BufferedImage> imgs = new ArrayList<>();
        imgs.add(first);
        for (int g = 1; g < group.size(); g++) {
            BufferedImage bi = readScaled(group.get(g), w, h);
            if (bi != null) {
                imgs.add(bi);
            }
        }
        if (imgs.size() < 2) {
            throw new IOException("该组合样本解码成功数不足 2 张");
        }
        int S = imgs.size();
        // same.png 判定：某像素颜色一致张数 ≥ needAgree（即 ≥90%，向上取整）即视为公共像素
        final int needAgree = Math.max(2, (int) Math.ceil(S * SAME_AGREE_RATIO));

        int n = w * h;
        int[] samePx = new int[n];   // 交集图：有效像素 = 该点颜色，其余保持 0（透明）
        int[] maxPx = new int[n];    // 多数图：每个像素 = 样本中出现最多的颜色
        int[] sumR = new int[n];     // 逐像素均值图用：各通道在所有样本上的累加
        int[] sumG = new int[n];
        int[] sumB = new int[n];
        boolean[] dead = new boolean[n];
        // 以行带方式逐点处理，控制峰值内存
        for (int y = 0; y < h; y += BAND_H) {
            int hh = Math.min(BAND_H, h - y);
            int[][] band = new int[S][w * hh];
            int[] buf = new int[w * hh];
            for (int s = 0; s < S; s++) {
                imgs.get(s).getRGB(0, y, w, hh, buf, 0, w);
                System.arraycopy(buf, 0, band[s], 0, buf.length);
            }
            HashMap<Integer, Integer> freq = new HashMap<>();
            for (int yy = 0; yy < hh; yy++) {
                int rowBase = yy * w;
                int globalBase = (y + yy) * w;
                for (int x = 0; x < w; x++) {
                    int ii = rowBase + x;
                    int gi = globalBase + x;
                    int c0 = band[0][ii];
                    int sr = (c0 >>> 16) & 0xff;
                    int sg = (c0 >>> 8) & 0xff;
                    int sb = c0 & 0xff;
                    boolean allSame = true;
                    for (int s = 1; s < S; s++) {
                        int c = band[s][ii];
                        if (c != c0) {
                            allSame = false;
                        }
                        sr += (c >>> 16) & 0xff;
                        sg += (c >>> 8) & 0xff;
                        sb += c & 0xff;
                    }
                    sumR[gi] = sr;
                    sumG[gi] = sg;
                    sumB[gi] = sb;
                    if (allSame) {
                        samePx[gi] = c0;
                        maxPx[gi] = c0;
                        continue;
                    }
                    // 该点各样本颜色不全相同：统计出现最多的颜色（同票取样本顺序靠前的）
                    freq.clear();
                    int bestColor = c0;
                    int bestCnt = 0;
                    for (int s = 0; s < S; s++) {
                        int c = band[s][ii];
                        int cnt = freq.merge(c, 1, Integer::sum);
                        if (cnt > bestCnt) {
                            bestCnt = cnt;
                            bestColor = c;
                        }
                    }
                    maxPx[gi] = bestColor | 0xff000000;
                    if (bestCnt >= needAgree) {
                        // 交集图：只要 ≥90% 的样本在该像素颜色一致，就保留该主流颜色（不再要求 100%）
                        samePx[gi] = bestColor;
                    } else {
                        dead[gi] = true;   // 一致度不足 90% → 该点透明，不计入公共（稳定）区域
                    }
                }
            }
        }
        // 均值图：每点 R/G/B 分别取全部样本的平均（所有图均按不透明画面输出）
        int[] avgPx = new int[n];
        for (int i = 0; i < n; i++) {
            int r = (sumR[i] + S / 2) / S;
            int g = (sumG[i] + S / 2) / S;
            int b = (sumB[i] + S / 2) / S;
            avgPx[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        int valid = 0;
        for (int i = 0; i < n; i++) {
            if (!dead[i]) {
                valid++;
            }
        }
        double coverage = valid / (double) n;
        for (int i = 0; i < n; i++) {
            if (dead[i]) {
                samePx[i] = 0x00000000;
            } else {
                samePx[i] |= 0xff000000;
            }
        }

        BufferedImage sameImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        sameImg.setRGB(0, 0, w, h, samePx, 0, w);
        BufferedImage maxImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        maxImg.setRGB(0, 0, w, h, maxPx, 0, w);
        BufferedImage avgImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        avgImg.setRGB(0, 0, w, h, avgPx, 0, w);

        // 1/8、1/32 多数 / 均值图：把所有样本按块对齐切分后，同一块内的全部原始像素跨样本合并统计——
        // 多数 = 合并集中出现次数最多的颜色；均值 = 合并集 R/G/B 的总平均（不再先逐张压缩再合成）
        BufferedImage m8Img = lowSample(imgs, w, h, 8, true);
        BufferedImage a8Img = lowSample(imgs, w, h, 8, false);
        BufferedImage m32Img = lowSample(imgs, w, h, 32, true);
        BufferedImage a32Img = lowSample(imgs, w, h, 32, false);

        boolean multiAction = stateActions().getOrDefault(state, Set.of()).size() > 1;
        String dir = dirNameOf(state, action, multiAction);
        Path gdir = groupDir(dir);
        Files.createDirectories(gdir);
        atomicWritePng(sameImg, gdir.resolve(FILE_SAME));
        atomicWritePng(maxImg, gdir.resolve(FILE_MAX));
        atomicWritePng(avgImg, gdir.resolve(FILE_AVG));
        atomicWritePng(m8Img, gdir.resolve(FILE_M8));
        atomicWritePng(a8Img, gdir.resolve(FILE_A8));
        atomicWritePng(m32Img, gdir.resolve(FILE_M32));
        atomicWritePng(a32Img, gdir.resolve(FILE_A32));
        Files.deleteIfExists(gdir.resolve("half.png"));   // 旧版半分辨率均值图产物已废弃，随重算清理

        // 分析记录文件（仅展示参考；不再存储带时间戳的文件名，产物名固定）
        Path info = gdir.resolve(FILE_INFO);
        Map<String, Object> d = Files.isRegularFile(info) ? readInfo(gdir) : new LinkedHashMap<>();
        d.put("state", trim(state));
        d.put("action", action);
        d.put("dir", dir);
        d.put("fileCount", group.size());
        d.put("sampleCount", S);
        d.put("artVer", ART_RULE_VERSION);
        d.put("width", w);
        d.put("height", h);
        d.put("coverage", Math.round(coverage * 1000000) / 10000.0d);   // 4 位小数百分比
        d.put("clickLeft", cx);
        d.put("clickTop", cy);
        d.put("updatedAt", LocalDateTime.now().format(TS_FORMAT));
        atomicWriteJson(info, d);
        pruneObsoleteGroupDirs(state, action, dir);
        log.info("汇总分析 分类 [{}|{}] 完成：{} 张样本，公共像素覆盖率（≥90% 一致）{}% ，产物 → summary/{}/",
            trim(state), action, S, Math.round(coverage * 10000) / 100.0d, dir);
    }

    /* ---------------------------------------------------------------- 产物工具 */

    /** 产物子目录完整路径：summary/&lt;dir&gt;/ */
    private Path groupDir(String dir) {
        return storage.summary().resolve(dir).normalize();
    }

    /** 七张对照图是否齐全 */
    private boolean artifactsComplete(Path gdir) {
        return Files.isRegularFile(gdir.resolve(FILE_SAME))
            && Files.isRegularFile(gdir.resolve(FILE_MAX))
            && Files.isRegularFile(gdir.resolve(FILE_AVG))
            && Files.isRegularFile(gdir.resolve(FILE_M8))
            && Files.isRegularFile(gdir.resolve(FILE_A8))
            && Files.isRegularFile(gdir.resolve(FILE_M32))
            && Files.isRegularFile(gdir.resolve(FILE_A32));
    }

    /** 读取产物目录下的 info.json；不存在/损坏返回空 map */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readInfo(Path gdir) {
        Path info = gdir.resolve(FILE_INFO);
        if (!Files.isRegularFile(info)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(info.toFile(), LinkedHashMap.class);
        } catch (IOException e) {
            log.warn("读取分析记录 {} 失败: {}", info, e.toString());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 产物目录名：= 分类标注文本（经文件名安全化后），正常一份标注对应一个目录；
     * 仅当同分类标注在历史数据里混用了多种动作时，追加 _action 后缀避免互相覆盖
     * （新界面规则已不允许此类混用）。
     */
    private String dirNameOf(String state, String action, boolean multiAction) {
        String s = trim(state)
            .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")   // 兜底：非法文件名符号
            .replaceAll("[\\.\\s]+$", "");                  // 兜底：Windows 禁止尾部的 . 与空格
        if (s.isEmpty()) {
            s = "unnamed";
        }
        if (s.length() > 120) {
            s = s.substring(0, 120);
        }
        return multiAction ? s + "_" + action : s;
    }

    /** 汇总 classify/ 中所有已标注图片的分类标注 → 该标注用到的动作集合（判断是否混用） */
    private Map<String, Set<String>> stateActions() {
        Map<String, Set<String>> map = new HashMap<>();
        for (Path p : annotatedPngs()) {
            CaptureMark m = classifyStore.sampleOf(p);
            if (m == null || trim(m.getState()).isEmpty()) {
                continue;
            }
            String action = m.getAction() == null ? CaptureMark.ACTION_NONE : m.getAction();
            map.computeIfAbsent(trim(m.getState()), k -> new HashSet<>()).add(action);
        }
        return map;
    }

    /** 删除指定分类+动作在其它目录下遗留的同组合产物（如分类标注改名后产生的旧目录） */
    private void pruneObsoleteGroupDirs(String state, String action, String keepDir) {
        Path sum = storage.summary();
        if (!Files.isDirectory(sum)) {
            return;
        }
        try (Stream<Path> s = Files.list(sum)) {
            for (Path d : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(d) || d.getFileName().toString().equals(keepDir)) {
                    continue;
                }
                Map<String, Object> dm = readInfo(d);
                if (trim(state).equals(dm.get("state")) && action.equals(dm.get("action"))) {
                    deleteTree(d);
                    log.info("已删除该分类旧产物目录 {}", d);
                }
            }
        } catch (IOException e) {
            log.warn("清理旧产物目录失败: {}", e.toString());
        }
    }

    /**
     * 分类标注整体改名后调用：删除 summary/ 下所有记录 state 为该值的产物目录。
     * 产物可随时由样本重算，因此直接删除，避免磁盘残留旧分类名目录。
     */
    public int purgeArtifactsOfState(String state) {
        if (state == null || state.trim().isEmpty()) {
            return 0;
        }
        String st = state.trim();
        Path sum = storage.summary();
        if (!Files.isDirectory(sum)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> s = Files.list(sum)) {
            for (Path d : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(d)) {
                    continue;
                }
                if (st.equals(readInfo(d).get("state"))) {
                    deleteTree(d);
                    n++;
                    log.info("分类标注改名后已删除旧产物目录 {}", d);
                }
            }
        } catch (IOException e) {
            log.warn("清理分类标注旧产物目录失败: {}", e.toString());
        }
        return n;
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除 {} 失败: {}", p, e.toString());
                }
            }
        }
    }

    /* ---------------------------------------------------------------- 通用工具 */

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private String actionLabel(String a) {
        return CaptureMark.ACTION_CLICK.equals(a) ? "点击" : "无动作";
    }

    /** 一组标注中最常见的点击坐标；无有效坐标返回 {-1,-1} */
    private int[] commonClick(List<CaptureMark> marks) {
        Map<Long, Integer> freq = new HashMap<>();
        long bestKey = 0;
        int best = 0;
        for (CaptureMark m : marks) {
            if (m.getLeft() == null || m.getTop() == null
                || m.getLeft() < 0 || m.getTop() < 0) {
                continue;
            }
            long key = (((long) m.getLeft()) << 32) | (m.getTop() & 0xffffffffL);
            int c = freq.merge(key, 1, Integer::sum);
            if (c > best) {
                best = c;
                bestKey = key;
            }
        }
        return best > 0 ? new int[]{(int) (bestKey >> 32), (int) bestKey} : new int[]{-1, -1};
    }

    /** 读取图片；尺寸与基准不一致时缩放到 w×h（同一窗口一般一致，仅兜底） */
    private BufferedImage readScaled(Path p, int w, int h) throws IOException {
        BufferedImage bi = ImageIO.read(p.toFile());
        if (bi == null) {
            return null;
        }
        if (bi.getWidth() == w && bi.getHeight() == h) {
            return bi;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(bi, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** classify/ 下 IMG_*.png：已标注数据集（保存标注时完整移入；写入端统一 .tmp→原子改名，半成品按后缀天然排除） */
    private List<Path> annotatedPngs() {
        List<Path> pngs = new ArrayList<>();
        Path dir = storage.classify();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> pngShape(p) && nonEmpty(p))
                 .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                 .forEach(pngs::add);
            } catch (IOException e) {
                log.warn("枚举 classify/ 目录失败: {}", e.toString());
            }
        }
        return pngs;
    }

    /** capture/（未标注）截图：命名规范且非空即可读。写入端是「.png.tmp → 原子改名 .png」，
     *  `.png` 出现即完整落盘（写入中的 .png.tmp 不符合 img_*.png 命名），无需时间等待 */
    private boolean isCapturedPng(Path p) {
        return pngShape(p) && nonEmpty(p);
    }

    private boolean pngShape(Path p) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        String n = p.getFileName().toString().toLowerCase();
        return n.startsWith("img_") && n.endsWith(".png");
    }

    private boolean nonEmpty(Path p) {
        try {
            return Files.size(p) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void atomicWriteJson(Path json, Map<String, Object> m) throws IOException {
        Path tmp = json.resolveSibling(json.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writeValueAsString(m));
        moveReplace(tmp, json);
    }

    private void atomicWritePng(BufferedImage img, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        boolean ok = ImageIO.write(img, "png", tmp.toFile());
        if (!ok) {
            Files.deleteIfExists(tmp);
            throw new IOException("ImageIO 无法写出 PNG");
        }
        moveReplace(tmp, target);
    }

    private void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /* ---------------------------------------------------------------- 模型 */

    /** 后台分析任务（running → done / error） */
    public static class Task {
        public final String taskId;
        public final boolean force;
        public volatile String status = "running";
        public volatile String message = "";
        public volatile int total;
        public volatile int processed;
        public volatile int errors;
        /** 正在处理的分类展示文案 */
        public volatile String current = "";

        Task(String taskId, boolean force) {
            this.taskId = taskId;
            this.force = force;
        }
    }
}
