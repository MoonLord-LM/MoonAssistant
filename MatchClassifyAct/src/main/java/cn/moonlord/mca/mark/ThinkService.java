package cn.moonlord.mca.mark;

import cn.moonlord.mca.act.FrameClassifier;
import cn.moonlord.mca.config.ExecuteProperties;
import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 汇总分析 · 同一「分类标注（state）」（动作一致）截图组的逐像素对照与记录。
 *
 * <p>界面语言里：分类标注 = 界面上的文本标签（数据字段 state），匹配动作 = none/click
 * （数据字段 action）；同一分类标注只允许一种匹配动作（控制台已按此规则约束）。
 * 本服务对每组截图逐像素分析，产出 14 张对照图（7 张基础图 + 7 张 -unique 独有区图）：</p>
 * <ol>
 *   <li><b>交集图</b>（same.png）：每个像素取“覆盖大于 90%”的值——统计所有样本在该像素的
 *       颜色，覆盖率 = 该颜色出现的样本占比；覆盖 &gt;90%（达标样本数向上取整）时该像素以该
 *       主流颜色保留为不透明，否则透明。不透明区 = 样本间公共（稳定）画面。</li>
 *   <li><b>多数图</b>（major.png）：每个像素取“覆盖率最多”的颜色——逐像素统计所有样本的颜色，
 *       取出现次数最多（同票取样本顺序靠前）的颜色作为该点颜色。</li>
 *   <li><b>均值图</b>（avg.png）：每个像素对全部样本的 R、G、B 分别取平均，
 *       得到一张“各点平均颜色”的合成画面（透明通道统一视为不透明）。</li>
 *   <li><b>1/8 多数图</b>（major8.png）：按 8×8 网格把所有样本对齐切块，将「同位置的块内全部
 *       像素」跨样本合并成一个集合，输出该集合中出现次数最多的颜色（覆盖率最多，同票取样本顺序
 *       靠前）；即一个输出像素 = 全部样本同一 8×8 块内所有原始像素的众数色，不分步压缩。</li>
 *   <li><b>1/32 多数图</b>（major32.png）：同上，块为 32×32。</li>
 *   <li><b>1/8 均值图</b>（avg8.png）：按 8×8 网格把所有样本对齐切块，将「同位置的块内全部
 *       像素」跨样本合并成一个集合，输出该集合全部像素 R/G/B 的总平均色。</li>
 *   <li><b>1/32 均值图</b>（avg32.png）：同上，块为 32×32。</li>
 * </ol>
 *
 * <p>每张基础图还额外合成一张对应的 <b>-unique 独有区图</b>（same-unique.png / major-unique.png /
 * avg-unique.png / major8-unique.png / avg8-unique.png / major32-unique.png / avg32-unique.png）：
 * 以该基础图为起点，把「其它分类标注（同尺寸的已汇总分组）的<b>同 kind 基础图</b>在同一像素位置
 * 颜色完全相同」的像素剔除（那些位置对“区分本分类”没有贡献），只保留本分类独有的画面区域，
 * 便于快速观察两两相近的分类到底差在哪里；同时作为执行 / 智能比对中与基础图互补的正式比对维度——
 * 独有区图只在本分类独有区域计分，专为拉开相近分类的差异度。独有区图是跨分类产物：
 * <b>要等全部分组的 7 张基础图都生成完才开始算</b>（全集门禁），且 14 张图必须齐全该分类
 * 才参与执行识别（见 {@link cn.moonlord.mca.act.FrameClassifier}）。</p>
 *
 * <p>产物统一放在 {@code summary/<分类标注>/} 目录下（capture/classify/summary 三阶段布局见
 * {@link StoragePaths}），14 张图使用固定文件名：
 * {@code same.png / same-unique.png / major.png / major-unique.png / avg.png / avg-unique.png /
 * major8.png / major8-unique.png / avg8.png / avg8-unique.png / major32.png / major32-unique.png / avg32.png / avg32-unique.png}，
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

    /** 每组合（一个分类标注目录）内固定文件名：7 张基础合成图（major 系列 = 多数色合成，8/32 为块边长） */
    private static final String FILE_SAME = "same.png";      // 交集图（覆盖>90%）
    private static final String FILE_MAX = "major.png";      // 多数图
    private static final String FILE_AVG = "avg.png";        // 均值图
    private static final String FILE_M8 = "major8.png";      // 1/8 多数图（8×8 块）
    private static final String FILE_A8 = "avg8.png";        // 1/8 均值图（8×8 块）
    private static final String FILE_M32 = "major32.png";    // 1/32 多数图（32×32 块）
    private static final String FILE_A32 = "avg32.png";      // 1/32 均值图（32×32 块）
    /** 7 张基础图各自的 -unique 独有区图（基础图里剔除“其它分类同 kind 基础图同像素同色”后剩下的区域） */
    private static final String FILE_UNIQUE = "same-unique.png";
    private static final String FILE_MAX_UNIQUE = "major-unique.png";
    private static final String FILE_AVG_UNIQUE = "avg-unique.png";
    private static final String FILE_M8_UNIQUE = "major8-unique.png";
    private static final String FILE_A8_UNIQUE = "avg8-unique.png";
    private static final String FILE_M32_UNIQUE = "major32-unique.png";
    private static final String FILE_A32_UNIQUE = "avg32-unique.png";
    private static final String FILE_INFO = "info.json";

    /** 7 张基础图的 kind（每张基础图都对应一张「kind + "-unique"」的独有区图） */
    private static final List<String> BASE_KINDS = List.of("same", "max", "avg", "major8", "avg8", "major32", "avg32");

    /** kind → 产物文件名：14 张图（7 基础 + 7 -unique）全部可经 /img/{kind} 读取 */
    private static final Map<String, String> KIND_FILE = Map.ofEntries(
        Map.entry("same", FILE_SAME),
        Map.entry("same-unique", FILE_UNIQUE),
        Map.entry("max", FILE_MAX),
        Map.entry("max-unique", FILE_MAX_UNIQUE),
        Map.entry("avg", FILE_AVG),
        Map.entry("avg-unique", FILE_AVG_UNIQUE),
        Map.entry("major8", FILE_M8),
        Map.entry("major8-unique", FILE_M8_UNIQUE),
        Map.entry("avg8", FILE_A8),
        Map.entry("avg8-unique", FILE_A8_UNIQUE),
        Map.entry("major32", FILE_M32),
        Map.entry("major32-unique", FILE_M32_UNIQUE),
        Map.entry("avg32", FILE_A32),
        Map.entry("avg32-unique", FILE_A32_UNIQUE));

    /** 旧版 kind 短码（uniqueCov 落盘键）→ 现 kind 全名：历史产物读取时迁移 */
    private static final Map<String, String> UNIQ_KIND_ALIAS = Map.of(
        "m8-unique", "major8-unique",
        "a8-unique", "avg8-unique",
        "m32-unique", "major32-unique",
        "a32-unique", "avg32-unique");

    /** 逐行像素处理时的行带高，控制峰值内存 */
    private static final int BAND_H = 64;

    /** same.png 交集图判定：某像素的颜色在样本中的一致占比 ≥ 该阈值即视为公共（稳定）像素 */
    private static final double SAME_AGREE_RATIO = 0.90;

    /** 产物生成规则版本：改动产物生成逻辑后递增，使旧产物自动判 stale 并重算 */
    private static final int ART_RULE_VERSION = 6;

    /** -unique 独有区图全量刷新时，同一尺寸类单一 kind 基础图文件总量上限：超过则本轮跳过，避免瞬时内存过高 */
    private static final long UNIQUE_CLASS_BYTES_LIMIT = 250L * 1024 * 1024;

    /** 计算池：像素比对较重，串行避免并发打满 CPU（手动批量分析任务 / 自动重算共用） */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mca-think");
        t.setDaemon(true);
        return t;
    });

    /** 智能建议独立单线程池：与批量分析/自动重算隔开，长汇总分析不会阻塞「停留 1 秒」的单图建议即时出结果 */
    private final ExecutorService suggestPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mca-suggest");
        t.setDaemon(true);
        return t;
    });

    /* ------------------------------------------------- 自动重算（标注样本变化后后台补齐/刷新产物） */
    /** 自动重算防抖窗口：最后一次标注变化后延迟多久启动扫描，避免连续标注期间反复全量重算 */
    private static final long RECOMPUTE_DELAY_MS = 3000L;

    /** 仅做延迟触发，真正的分析仍交给上面的计算池串行执行 */
    private final ScheduledExecutorService autoScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mca-think-auto");
        t.setDaemon(true);
        return t;
    });
    private final Object recomputeLock = new Object();
    private boolean recomputeArmed;    // 已有延迟计划（防抖窗口内合并多次变化）
    private boolean recomputeRunning;  // 自动重算正在计算池里执行
    private boolean recomputeAgain;    // 执行期间样本又变化 → 本轮跑完后再补一轮

    private final StoragePaths storage;
    private final ClassifyStore classifyStore;
    /** 执行模式的画面识别器：智能建议直接复用它做比对，保证与执行模式同算法、同阈值、同缓存 */
    private final FrameClassifier frameClassifier;
    private final ExecuteProperties executeProperties;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /** 单图智能建议任务 */
    private final Map<String, SuggestTask> suggestTasks = new ConcurrentHashMap<>();
    /** 最新一次建议请求：供排队中的旧建议任务启动时自检作废 */
    private final AtomicReference<SuggestTask> latestSuggest = new AtomicReference<>();
    /** 建议结果缓存：key = 目标图|产物签名，避免同一张图反复重算；取访问序淘汰（再命中=用户还在来回比对该图，应续命留驻），上限 60 条 */
    private final Map<String, List<Map<String, Object>>> suggestCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Map<String, Object>>> eldest) {
            return size() > 60;
        }
    };

    public ThinkService(StoragePaths storage, ClassifyStore classifyStore,
                        FrameClassifier frameClassifier, ExecuteProperties executeProperties) {
        this.storage = storage;
        this.classifyStore = classifyStore;
        this.frameClassifier = frameClassifier;
        this.executeProperties = executeProperties;
        requestRecompute();   // 启动后自动补一轮：上次退出没跑完 / 重启期间样本有变的产物尽快对齐（约 3 秒后执行）
    }

    /* ---------------------------------------------------------------- 对外 API */

    /**
     * 分析全部待分析的组合并写入/刷新 `summary/<分类标注>/` 下产物。
     *
     * @param force true = 无视已有产物全部重算
     */
    public String startAnalyze(boolean force) {
        if (tasks.size() > 32) {
            tasks.entrySet().removeIf(e -> !"running".equals(e.getValue().status));   // 只留运行中的任务，防止长期挂机后 map 无限膨胀
        }
        String id = UUID.randomUUID().toString();
        Task t = new Task(id, force);
        tasks.put(id, t);
        pool.submit(() -> runAnalyze(t));
        return id;
    }

    /**
     * 一键重建（前端「重新生成全部对照图」按钮）：先清空 `summary/` 下全部产物目录，再全量重跑一轮分析。
     *
     * <p>等价于“手动删掉整个 summary/ 再触发自动生成”，但删除动作放进本服务的串行计算池执行，
     * 不会与自动重算 / 其它手动分析互踩；清场同时带走历史遗留文件（旧命名、孤儿图）。
     * 产物由 classify/ 已标注样本派生，删除不影响原始截图与标注。
     */
    public String startRebuild() {
        if (tasks.size() > 32) {
            tasks.entrySet().removeIf(e -> !"running".equals(e.getValue().status));   // 只留运行中的任务，防止 map 无限膨胀
        }
        String id = UUID.randomUUID().toString();
        Task t = new Task(id, true, true);
        tasks.put(id, t);
        pool.submit(() -> runAnalyze(t));
        return id;
    }

    /** 任务快照；不存在返回 null */
    public Task task(String taskId) {
        return taskId == null ? null : tasks.get(taskId);
    }

    /**
     * 标注样本集合（classify/）发生变化后调用：约 3 秒防抖后，自动启动一轮后台汇总分析，
     * 把「有样本（≥ 1 张）且产物缺失 / 样本数有变」的分组全部补齐或重算。
     *
     * <p>用于「窗口挂机持续标注」的场景：无需停留在汇总分析页，也不用点按钮，
     * 只要样本变化，summary/ 下的 14 张对照图（含跨分类的 -unique 独有区图）就会自动保持与最新样本一致，
     * 供执行模式随时取用。与前端手动分析共用同一计算池，串行执行互不并发。
     */
    public void requestRecompute() {
        synchronized (recomputeLock) {
            if (recomputeRunning) {
                recomputeAgain = true;      // 正在跑：跑完这轮后自动补一轮，把最新变化扫进去
                return;
            }
            if (recomputeArmed) {
                return;                     // 已有防抖计划：到点时自会扫描到此刻的最新状态，无需重复排队
            }
            recomputeArmed = true;
        }
        autoScheduler.schedule(this::autoDispatch, RECOMPUTE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** 防抖到期：占位并提交计算池（不在本调度线程里做重活） */
    private void autoDispatch() {
        synchronized (recomputeLock) {
            if (!recomputeArmed) {
                return;
            }
            recomputeArmed = false;
            if (recomputeRunning) {
                recomputeAgain = true;
                return;
            }
            recomputeRunning = true;
        }
        pool.submit(this::runAutoRecomputeLoop);
    }

    /** 自动重算主体：一轮扫描补齐后，若执行期间样本又变化则继续下一轮，直到追上最新状态 */
    private void runAutoRecomputeLoop() {
        try {
            for (; ; ) {
                try {
                    runAnalyze(new Task("auto", false));   // 自动任务不进 tasks map（无需前端轮询）
                } catch (Throwable e) {
                    log.warn("自动汇总分析异常，本轮终止（样本再次变化时会自动重试）：{}", e.toString());
                    return;    // 失败不原地死循环：下次 requestRecompute 自然再触发
                }
                boolean again;
                synchronized (recomputeLock) {
                    again = recomputeAgain;
                    recomputeAgain = false;
                }
                if (!again) {
                    return;
                }
            }
        } finally {
            boolean again;
            synchronized (recomputeLock) {
                recomputeRunning = false;
                again = recomputeAgain;    // 极边角：最后一轮检查之后、解锁之前又收到变化
                recomputeAgain = false;
            }
            if (again) {
                requestRecompute();
            }
        }
    }

    /* ---------------------------------------------------------------- 智能建议（未标注图 × 已生成的七张对照图） */

    /** 单图智能建议任务：把一张未标注截图交给执行模式的识别器（FrameClassifier）与各分类对照图比对 */
    public static class SuggestTask {
        public final String taskId;
        public final String file;
        public volatile String status = "running";   // running / done / error
        public volatile String message = "";
        /** 候选组，与执行模式同口径：按 14 图「不匹配占比」的加权平均差异度升序；每条含 diffPercent/recognized 等字段 */
        public volatile List<Map<String, Object>> candidates = List.of();

        SuggestTask(String taskId, String file) {
            this.taskId = taskId;
            this.file = file;
        }
    }

    /** 启动单图智能建议。走独立建议池，新请求使排队中的旧建议任务作废，只计算最新停留的一张图 */
    public String startSuggest(String file) {
        String id = UUID.randomUUID().toString();
        if (suggestTasks.size() > 100) {
            suggestTasks.entrySet().removeIf(e -> !"running".equals(e.getValue().status));
        }
        SuggestTask t = new SuggestTask(id, file);
        suggestTasks.put(id, t);
        latestSuggest.set(t);
        suggestPool.submit(() -> runSuggest(t, file));
        return id;
    }

    /** 建议任务快照；不存在返回 null */
    public SuggestTask suggestTask(String taskId) {
        return taskId == null ? null : suggestTasks.get(taskId);
    }

    private void runSuggest(SuggestTask t, String file) {
        /* 已被更新的建议请求取代 → 作废，不再占用计算 */
        if (latestSuggest.get() != t) {
            t.status = "done";
            return;
        }
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
            List<Map<String, Object>> out = suggestWithClassifier(target);   // 复用执行模式的识别器，与执行模式同口径
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

    /** 建议结果签名：目标图 + 每个「14 图齐全」分类产物目录内全部对照图/info 的尺寸与修改时间（任一产物重算即失效） */
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
                    if (!artifactsAllComplete(d)) {
                        continue;   // 与执行模式识别器同口径：14 张图齐全的分类才参与
                    }
                    sb.append(d.getFileName()).append('{');
                    for (String f : List.of(FILE_SAME, FILE_UNIQUE, FILE_MAX, FILE_MAX_UNIQUE,
                        FILE_AVG, FILE_AVG_UNIQUE, FILE_M8, FILE_M8_UNIQUE,
                        FILE_A8, FILE_A8_UNIQUE, FILE_M32, FILE_M32_UNIQUE,
                        FILE_A32, FILE_A32_UNIQUE, FILE_INFO)) {
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
     * 单图智能建议：把目标截图交给「执行模式」的同一画面识别器比对打分，结果口径与执行模式完全一致——
     * 每个分类的 14 张对照图（交集 / 独有交集 / 多数 / 均值 / 1-8、1-32 块图及其各自的 -unique 独有区图）
     * 分别同尺度逐像素比对。判据按维度类别分两套：交集/多数类（交集图、多数图、1-8/1-32 多数块图及各自
     * -unique）逐像素完全一致（R/G/B 三通道差都为 0）；均值类（均值图、1-8/1-32 均值块图及各自 -unique）
     * 走逐通道容差（三通道差都不超过 execute.rgb-dist-threshold 才算匹配，默认 255/3=85），
     * 分类得分 = 各图「不匹配点占比」的加权平均差异度 = (独有交集图×50 + 交集图×30
     * + 其余 12 张平均×20) / 100（交集 / 独有交集锁定公共稳定区与独占核心区，权重最高；
     * 其余图合计只占 20%，个别维明显差异不会被过度放大）。识别不设阈值门槛：
     * 有可比的最近似分类即视为已识别（差异度仅供展示参考）。
     */
    private List<Map<String, Object>> suggestWithClassifier(BufferedImage target) {
        FrameClassifier.Outcome oc = frameClassifier.classify(target);
        if (oc == null || oc.candidates == null || oc.candidates.isEmpty()) {
            return List.of();
        }
        double thr = executeProperties.getMatchThresholdPercent();
        List<Map<String, Object>> out = new ArrayList<>(oc.candidates.size());
        for (int i = 0; i < oc.candidates.size(); i++) {
            FrameClassifier.Candidate c = oc.candidates.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", c.state());
            m.put("dir", c.matchedFile());
            m.put("diffPercent", Math.round(c.diffPercent() * 100.0) / 100.0);   // 14 图不匹配占比的加权平均差异度（%）
            if (i == 0) {
                m.put("action", oc.action);    // 建议分类的动作只在最佳候选上读取（来自该分类 info.json）
            }
            m.put("recognized", oc.recognized);          // 不设识别阈值：有可比的最近似分类即 true
            m.put("thresholdPercent", Math.round(thr * 100.0) / 100.0);
            List<Map<String, Object>> kinds = new ArrayList<>(c.kinds().size());
            for (FrameClassifier.KindScore ks : c.kinds()) {
                kinds.add(Map.of("kind", ks.kind(), "file", ks.file(),
                        "w", ks.w(), "h", ks.h(), "score", Math.round(ks.score() * 100.0) / 100.0));
            }
            m.put("kinds", kinds);
            out.add(m);
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
            g.put("canAnalyze", !marks.isEmpty());   // 有样本（≥1 张）即可分析：单张也生成七图，供执行模式匹配
            int[] cc = commonClick(marks);
            g.put("clickLeft", cc[0]);
            g.put("clickTop", cc[1]);

            Path gdir = groupDir(dir);
            Map<String, Object> info = readInfo(gdir);
            boolean complete = artifactsComplete(gdir);
            g.put("analyzed", complete);   // 7 张基础产物齐全才算完成（基础图是 -unique 独有区图的前置）
            if (complete) {
                boolean hasUnique = uniqueArtifactsComplete(gdir);   // 7 张 -unique 独有区图是否已随重算全部生成
                g.put("hasUnique", hasUnique);
                // 各独有区图剩余独有像素占各自全图的比例（kind → 百分数值，与 coverage 同口径）；未生成时为 null
                g.put("uniqueCov", hasUnique ? normUniqueCov(info.get("uniqueCov")) : null);
                g.put("coverage", info.get("coverage"));
                g.put("width", info.get("width"));
                g.put("height", info.get("height"));
                g.put("mtime", infoMtime(gdir));   // 产物 info.json 修改时刻：前端作缓存失效版本号（后台重算后界面能拉到新图）
                // 样本数相较上次分析有变化，或产物生成规则版本不一致 → 标记为待重分析
                Object fc = info.get("fileCount");
                boolean ruleChanged = !Integer.valueOf(ART_RULE_VERSION).equals(info.get("artVer"));
                // 「原分类内重定义动作/点击坐标」不改变样本数量，样本数与 artVer 都发现不了：
                // 只有产物 info.json 记录的坐标与当前中心表定义（commonClick 按样本实时合成）不一致时才判 stale，
                // 使 requestRecompute 重算刷新 summary/ 产物——否则执行模式将一直按 info.json 里的旧坐标点击。
                boolean defChanged = infoClick(info.get("clickLeft")) != cc[0]
                        || infoClick(info.get("clickTop")) != cc[1];
                g.put("stale", ruleChanged || (fc != null && !fc.equals(marks.size())) || defChanged);
            } else {
                g.put("hasUnique", false);
                g.put("uniqueCov", null);
                g.put("coverage", null);
                g.put("width", null);
                g.put("height", null);
                g.put("stale", false);
            }
            out.add(g);
        }
        // 按“匹配度”排序（对应前端列表标题「分类标注列表（按匹配度）」）。
        // 覆盖率 = 交集图 ≥90% 样本同色的像素占比。覆盖率越高说明该组截图彼此差异越小——多为同一画面反复
        // 截取（采样不足，可信度反而低）；覆盖率越低说明采到了该状态不同时刻的真实差异（采样更充分）。
        // 故已分析（产物齐全且样本未变）组合：覆盖率低的靠前、高的靠后；同覆盖率按「分类标注 → 动作」文字升序。
        // 其余（未分析 / 样本有变待重算）排后：段内先按样本数从多到少，再按「分类标注 → 动作」文字升序。
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
                int c = Double.compare(x, y);   // 覆盖率（截图差异小 = 采样不足）降序 → 升序：低者靠前
                if (c != 0) {
                    return c;
                }
            } else {
                // ≥1 张样本即可后台自动分析，未分析 / 待重算的组通常是产物刷新前的瞬时状态：样本多的排前
                int ca = ((Number) a.get("sampleCount")).intValue();
                int cb = ((Number) b.get("sampleCount")).intValue();
                if (ca != cb) {
                    return Integer.compare(cb, ca);
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

    /** 读取分析产物 PNG（kind=14 图之一：7 基础 + 7 -unique，如 same|same-unique|max|max-unique|avg|major8|avg8|major32|avg32…，dir=产物目录名，禁止穿越）；非法返回 null */
    public Path resolveArtifact(String kind, String dir) {
        String file = KIND_FILE.get(kind);
        if (file == null) {
            return null;
        }
        if (dir == null || dir.isEmpty() || dir.indexOf('/') >= 0 || dir.indexOf('\\') >= 0) {
            return null;
        }
        try {
            Path root = storage.summary();
            Path p = root.resolve(dir).resolve(file).normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) {
                return null;
            }
            return p;
        } catch (RuntimeException e) {
            return null;   // dir 解码结果含非法字符（NUL/控制符等）导致路径无法解析
        }
    }

    /* ---------------------------------------------------------------- 后台分析 */

    private void runAnalyze(Task t) {
        try {
            if (t.rebuild) {
                wipeSummary();
                log.info("一键重建：summary/ 已清空，开始全量重建全部对照图");
            }
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
            // 全部分组的基础 7 图生成完成后，再刷新跨分类依赖的各 -unique 独有区图：
            // refreshUniqueArtifacts 内部带全集门禁（任一有样本分组的基础图未齐则整轮跳过），
            // 门禁通过后再按「各成员 7 张基础图 mtime 签名」增量检查（签名未变则跳过，成本只有 stat）
            int uniqueClasses = 0;
            try {
                t.stage = 2;
                t.current = "";
                uniqueClasses = refreshUniqueArtifacts(groups, t);
            } catch (Exception e) {
                log.warn("刷新 -unique 独有区图异常（不影响本轮分析结果）：{}", e.toString());
            }
            t.status = "done";
            t.message = (todo.isEmpty()
                    ? "没有需要分析的组合（已有样本的分组都已有分析结果）"
                    : String.format("完成 %d/%d 个分类的基础对照图", processed, todo.size())
                        + (t.errors > 0 ? "（" + t.errors + " 个失败，详见日志）" : ""))
                + (uniqueClasses > 0 ? "；独有区图已同步刷新（" + uniqueClasses + " 个分类）" : "");
            log.info("汇总分析批量分析结束：{}", t.message);
        } catch (Exception e) {
            log.warn("汇总分析批量分析异常: {}", e.toString());
            t.status = "error";
            t.message = "分析失败：" + e.getMessage();
        }
    }

    /** 计算单个分类（state+action）的 7 张基础对照图并刷新产物目录（固定文件名原子替换；-unique 独有区图由跨分类刷新统一生成） */
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
        if (group.isEmpty()) {
            throw new IllegalStateException("该组合没有可用样本");
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

        // 解码全部样本（分辨率须与第一张一致：同一窗口截图同尺寸，不一致 readScaled 直接报错）
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
        if (imgs.isEmpty()) {
            throw new IOException("该组合的样本全部无法解码");
        }
        int S = imgs.size();
        // same.png 判定：某像素颜色一致张数 ≥ needAgree（即 ≥90%，向上取整）即视为公共像素；
        // 单样本时全部像素天然一致（交集图即原图本身），故下限放宽到 1
        final int needAgree = Math.max(1, (int) Math.ceil(S * SAME_AGREE_RATIO));

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
        BufferedImage major8Img = lowSample(imgs, w, h, 8, true);
        BufferedImage avg8Img = lowSample(imgs, w, h, 8, false);
        BufferedImage major32Img = lowSample(imgs, w, h, 32, true);
        BufferedImage avg32Img = lowSample(imgs, w, h, 32, false);

        boolean multiAction = stateActions().getOrDefault(state, Set.of()).size() > 1;
        String dir = dirNameOf(state, action, multiAction);
        Path gdir = groupDir(dir);
        Files.createDirectories(gdir);
        atomicWritePng(sameImg, gdir.resolve(FILE_SAME));
        atomicWritePng(maxImg, gdir.resolve(FILE_MAX));
        atomicWritePng(avgImg, gdir.resolve(FILE_AVG));
        atomicWritePng(major8Img, gdir.resolve(FILE_M8));
        atomicWritePng(avg8Img, gdir.resolve(FILE_A8));
        atomicWritePng(major32Img, gdir.resolve(FILE_M32));
        atomicWritePng(avg32Img, gdir.resolve(FILE_A32));
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

    /* ----------------------------------------------- -unique 独有区图（7 张基础图 → 7 张独有区图） */

    /**
     * 刷新「classify/ 中当前有样本（≥ 1 张）的全部分组」的各 -unique 独有区图（每张基础图一张：
     * same-unique / max-unique / avg-unique / major8-unique / avg8-unique / major32-unique / avg32-unique）。
     *
     * <p>基础合成图只刻画“本分类稳定出现的画面”，而独有区图进一步要求该稳定像素<b>只属于本分类</b>：
     * 以本分类某张基础图（kind）为起点，逐个与其它分类标注（同尺寸的已汇总分组）的<b>同 kind 基础图</b>
     * 同位比较，凡其它分类该图在该像素颜色完全相同的点都从本图剔除（它们无助于区分分类），
     * 保留下来的即本分类独有的画面区域。7 个 kind 逐张独立生成、互不干扰。
     *
     * <p><b>全集门禁</b>：独有区图是跨分类产物——少算一个分类，其它分类“哪些像素独有”的判定就不完整。
     * 因此只有「当前有样本的全部分组」的 7 张基础对照图都生成完毕，本方法才真正开始计算；任一分组基础图
     * 尚未齐备（本轮新增样本的分组还没轮到、或该分组本轮生成失败留下残图），整轮直接跳过，等下一轮补齐后再算
     * ——不允许在“分类集合不完整”的状态下过早生成。互比对象以 classify/ 有样本的分组为准，
     * 而非简单枚举 summary/ 磁盘目录（历史遗留的孤儿目录不参与现行分类判定）。
     *
     * <p>尺寸不同的分组无法逐像素对齐，彼此不参与比较；同一尺寸类按“各成员 7 张基础图 mtime 序列签名”
     * 增量更新——自身或任一其它分类的基础图更新过、或任一 -unique 图缺失 / 无独有覆盖率字段时才真正重算
     * 该尺寸类，因此自动重算频繁触发时成本仅为 stat。内存上逐 kind 单独处理：任一时点只保留一个 kind 的
     * 逐成员像素，且每种 kind 的文件总量超限即整类跳过本轮（签名不落盘，下轮自动重算会再尝试）。</p>
     */
    /** 刷新全部 -unique 独有区图；返回本轮实际刷新了多少个分类（门禁未过 / 无需变更返回 0） */
    private int refreshUniqueArtifacts(List<Map<String, Object>> groups, Task t) {
        Path root = storage.summary();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        // 先做全集门禁并收集互比目录：只放行「有样本且 7 张基础图齐全」的组
        List<Path> dirs = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            if (!Boolean.TRUE.equals(g.get("canAnalyze"))) {
                continue;
            }
            Path d = groupDir(String.valueOf(g.get("dir")));
            if (!artifactsComplete(d)) {
                log.warn("分组 {} 的 7 张基础对照图尚未齐备，本轮跳过 -unique 独有区图刷新，等补齐后重算",
                    d.getFileName());
                return 0;
            }
            dirs.add(d);
        }
        if (dirs.isEmpty()) {
            return 0;
        }
        // 按 (宽,高) 归类：同尺寸才可逐像素同位比较
        Map<Long, List<Path>> byDim = new LinkedHashMap<>();
        for (Path d : dirs) {
            int[] wh = pngSize(d.resolve(FILE_SAME));
            if (wh == null || wh[0] <= 0 || wh[1] <= 0) {
                continue;
            }
            byDim.computeIfAbsent((((long) wh[0]) << 32) | (wh[1] & 0xffffffffL), k -> new ArrayList<>()).add(d);
        }
        int refreshed = 0;
        for (List<Path> cls : byDim.values()) {
            try {
                if (refreshUniqueClass(cls, t)) {
                    refreshed += cls.size();
                }
            } catch (Exception e) {
                log.warn("刷新 -unique 独有区图失败（{} 个同尺寸分类）：{}", cls.size(), e.toString());
            }
        }
        return refreshed;
    }

    /** 重算一个“同尺寸类”的全部 -unique 独有区图；签名未变且各 -unique 图齐全时整类跳过。返回 true = 本轮实际执行了刷新 */
    private boolean refreshUniqueClass(List<Path> cls, Task t) throws IOException {
        String sig = classSig(cls);
        boolean need = false;
        for (Path d : cls) {
            Map<String, Object> info = readInfo(d);
            if (!sig.equals(String.valueOf(info.get("uniqueSig")))) {
                need = true;
                break;
            }
            Object covObj = info.get("uniqueCov");
            if (!(covObj instanceof Map)) {
                need = true;   // 旧产物没有分 kind 的独有覆盖率 map → 一并补算
                break;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cov = (Map<String, Object>) covObj;
            for (String kind : BASE_KINDS) {
                String uniqKind = kind + "-unique";
                if (!Files.isRegularFile(d.resolve(KIND_FILE.get(uniqKind)))
                    || cov.get(uniqKind) == null) {
                    need = true;
                    break;
                }
            }
            if (need) {
                break;
            }
        }
        if (!need) {
            return false;
        }
        int[] wh = pngSize(cls.get(0).resolve(FILE_SAME));
        // 内存防护：按 kind 单独处理，每种 kind 的文件总量都要在限内；任一种超限本轮跳过且不记录签名，
        // 之后每轮自动重算会再尝试，避免瞬时峰值内存过高
        for (String kind : BASE_KINDS) {
            long bytes = 0;
            for (Path d : cls) {
                try {
                    bytes += Files.size(d.resolve(KIND_FILE.get(kind)));
                } catch (IOException ignore) {
                    // 文件刚被重算替换：按 0 计，让签名差异触发下一轮重试
                }
            }
            if (bytes > UNIQUE_CLASS_BYTES_LIMIT) {
                log.warn("同尺寸 {} 基础图共约 {}MB，超过 {}MB 上限，本轮跳过 -unique 独有区图刷新",
                    kind, bytes >> 20, UNIQUE_CLASS_BYTES_LIMIT >> 20);
                return false;
            }
        }
        // 逐 kind 计算：以本分类该 kind 基础图为起点，剔除“其它分类同 kind 基础图同位同色”的像素
        Map<Path, Map<String, Double>> covOf = new LinkedHashMap<>();
        int kindIndex = 0;
        for (String kind : BASE_KINDS) {
            kindIndex++;
            if (t != null) {
                t.current = kindLabel(kind) + "（" + kindIndex + "/" + BASE_KINDS.size() + "）";
            }
            Map<Path, Double> cov = refreshUniqueKind(cls, kind);
            for (Path d : cls) {
                covOf.computeIfAbsent(d, k -> new LinkedHashMap<>()).put(kind + "-unique", cov.get(d));
            }
        }
        // 全部 kind 算完才统一写签名与覆盖率，避免写一半造成信息不一致
        for (Path d : cls) {
            Map<String, Object> info = readInfo(d);
            info.put("uniqueSig", sig);
            info.put("uniqueCov", covOf.get(d));
            info.remove("uniqueCoverage");   // 旧版单一覆盖率字段清理
            atomicWriteJson(d.resolve(FILE_INFO), info);
        }
        log.info("-unique 独有区图已刷新：{} 个同尺寸分类（{}×{}），每分类 7 张",
            cls.size(), wh == null ? 0 : wh[0], wh == null ? 0 : wh[1]);
        return true;
    }

    /** 计算并落盘某一 kind 的 -unique 独有区图：把本分类该 kind 基础图中「其它分类同 kind 基础图同位同色」的点清透明 */
    private Map<Path, Double> refreshUniqueKind(List<Path> cls, String kind) throws IOException {
        String baseFile = KIND_FILE.get(kind);
        String uniqKind = kind + "-unique";
        Path first = cls.get(0).resolve(baseFile);
        int[] wh = pngSize(first);
        if (wh == null) {
            throw new IOException("PNG 尺寸读取失败：" + first);
        }
        int w = wh[0];
        int h = wh[1];
        Map<Path, int[]> pxOf = new LinkedHashMap<>();
        Map<Path, String> stateOf = new HashMap<>();
        for (Path d : cls) {
            BufferedImage img = ImageIO.read(d.resolve(baseFile).toFile());
            if (img == null || img.getWidth() != w || img.getHeight() != h) {
                throw new IOException(baseFile + " 读取失败/尺寸不一致：" + d.getFileName());
            }
            pxOf.put(d, img.getRGB(0, 0, w, h, null, 0, w));
            Object st = readInfo(d).get("state");
            stateOf.put(d, st == null ? "" : String.valueOf(st));
        }
        int n = w * h;
        Map<Path, Double> cov = new LinkedHashMap<>();
        for (Path me : cls) {
            int[] own = pxOf.get(me);
            int[] uniq = own.clone();   // 起点 = 本 kind 基础图；命中“其它分类同 kind 同位同色”的点逐个清透明
            String myState = stateOf.get(me);
            for (Path other : cls) {
                if (other.equals(me) || myState.equals(stateOf.get(other))) {
                    continue;   // 同分类标注的其它动作目录不算“其它分类”
                }
                int[] op = pxOf.get(other);
                for (int i = 0; i < n; i++) {
                    if (uniq[i] == 0) {
                        continue;                       // 自身基础图透明，或已被判定非独有
                    }
                    int oc = op[i];
                    if (oc != 0 && (oc & 0xffffff) == (uniq[i] & 0xffffff)) {
                        uniq[i] = 0;                    // 其它分类同 kind 基础图同位同色 → 非本分类独有
                    }
                }
            }
            long uniqueCount = 0;                        // 剔完后保留的独有像素数
            for (int i = 0; i < n; i++) {
                if (uniq[i] != 0) {
                    uniqueCount++;
                }
            }
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, w, h, uniq, 0, w);
            atomicWritePng(out, me.resolve(KIND_FILE.get(uniqKind)));
            // 独有像素占该图全图的比例（百分数值，口径与 coverage 一致：0.5 = 0.5%）
            cov.put(me, Math.round(uniqueCount * 1000000.0 / n) / 10000.0);
        }
        return cov;
    }

    /** 尺寸类签名 = 各成员目录“目录名=7 张基础图最后修改时刻序列”排序后拼接：任一成员任一基础图更新即变化 */
    private String classSig(List<Path> cls) {
        List<String> parts = new ArrayList<>(cls.size());
        for (Path d : cls) {
            StringBuilder sb = new StringBuilder(d.getFileName().toString());
            for (String kind : BASE_KINDS) {
                long m = 0;
                try {
                    m = Files.getLastModifiedTime(d.resolve(KIND_FILE.get(kind))).toMillis();
                } catch (IOException ignore) {
                    // 读取失败按 0 计：签名必变，触发下一轮重算
                }
                sb.append('=').append(m);
            }
            parts.add(sb.toString());
        }
        parts.sort(String::compareTo);
        return String.join("|", parts);
    }

    /** 轻量读取 PNG 尺寸（只解析文件头，不整图解码） */
    private int[] pngSize(Path p) {
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) {
                return null;
            }
            ImageReader r = it.next();
            try {
                r.setInput(in, true, true);
                return new int[]{r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /* ---------------------------------------------------------------- 产物工具 */

    /** 产物子目录完整路径：summary/&lt;dir&gt;/ */
    private Path groupDir(String dir) {
        return storage.summary().resolve(dir).normalize();
    }

    /** 7 张基础对照图是否齐全（-unique 独有区图的前置） */
    private boolean artifactsComplete(Path gdir) {
        return Files.isRegularFile(gdir.resolve(FILE_SAME))
            && Files.isRegularFile(gdir.resolve(FILE_MAX))
            && Files.isRegularFile(gdir.resolve(FILE_AVG))
            && Files.isRegularFile(gdir.resolve(FILE_M8))
            && Files.isRegularFile(gdir.resolve(FILE_A8))
            && Files.isRegularFile(gdir.resolve(FILE_M32))
            && Files.isRegularFile(gdir.resolve(FILE_A32));
    }

    /** 7 张 -unique 独有区图是否齐全 */
    private boolean uniqueArtifactsComplete(Path gdir) {
        return Files.isRegularFile(gdir.resolve(FILE_UNIQUE))
            && Files.isRegularFile(gdir.resolve(FILE_MAX_UNIQUE))
            && Files.isRegularFile(gdir.resolve(FILE_AVG_UNIQUE))
            && Files.isRegularFile(gdir.resolve(FILE_M8_UNIQUE))
            && Files.isRegularFile(gdir.resolve(FILE_A8_UNIQUE))
            && Files.isRegularFile(gdir.resolve(FILE_M32_UNIQUE))
            && Files.isRegularFile(gdir.resolve(FILE_A32_UNIQUE));
    }

    /** 14 张对照图（7 基础 + 7 -unique）是否齐全：与执行模式识别器同口径，齐全才参与匹配 */
    private boolean artifactsAllComplete(Path gdir) {
        return artifactsComplete(gdir) && uniqueArtifactsComplete(gdir);
    }

    /** 产物目录 info.json 的最后修改时刻（毫秒）；缺失/读不到返回 0。作为该组七图产物整体是否更新过的版本号 */
    private long infoMtime(Path gdir) {
        Path info = gdir.resolve(FILE_INFO);
        if (!Files.isRegularFile(info)) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(info).toMillis();
        } catch (IOException e) {
            return 0L;
        }
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
        String s = dirBaseName(state);
        return multiAction ? s + "_" + action : s;
    }

    /** 分类标注 → 产物目录基础名（文件名安全化 + 截断；目录命名与改名迁移共用同一口径；静态方法内不便复用实例 trim） */
    private static String dirBaseName(String state) {
        String s = (state == null ? "" : state.trim())
            .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")   // 兜底：非法文件名符号
            .replaceAll("[\\.\\s]+$", "");                  // 兜底：Windows 禁止尾部的 . 与空格
        if (s.isEmpty()) {
            s = "unnamed";
        }
        if (s.length() > 120) {
            s = s.substring(0, 120);
        }
        return s;
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

    /** 分类标注改名后产物迁移结果：moved = 成功迁名的目录数；needRebuild = 删除待后台重建的目录数 */
    public record RenameArtifactsResult(int moved, int needRebuild) {
    }

    /**
     * 分类标注整体改名后调用：把 summary/ 下该分类的产物目录整体迁名为新名，并把 info.json 的 state 改为新名。
     * 样本画面未变，7 张基础图内容不变；-unique 独有区图按像素互比、与目录名无关，均无需重算——
     * 改名即刻对执行模式生效，不再出现「删旧产物 + 后台重建」期间 14 张不全的半成品目录被识别到而报红字。
     * 产物不齐 / 目录名不是标准同名目录 / 迁移失败的旧目录删除，交由后台按新名重建补齐。
     */
    public RenameArtifactsResult renameArtifacts(String from, String to) {
        Path sum = storage.summary();
        String f = trim(from);
        String t = trim(to);
        if (!Files.isDirectory(sum) || f.isEmpty() || t.isEmpty()) {
            return new RenameArtifactsResult(0, 0);
        }
        String baseFrom = dirBaseName(f);
        String baseTo = dirBaseName(t);
        if (baseFrom.equals(baseTo)) {
            return new RenameArtifactsResult(0, 0);
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(sum)) {
            dirs = s.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.warn("枚举 summary/ 失败，跳过产物目录改名: {}", e.toString());
            return new RenameArtifactsResult(0, 0);
        }
        int moved = 0;
        int needRebuild = 0;
        for (Path d : dirs) {
            Object st = readInfo(d).get("state");
            if (!f.equals(st == null ? "" : String.valueOf(st).trim())) {
                continue;
            }
            String name = d.getFileName().toString();
            String newName = name.equals(baseFrom) ? baseTo : null;
            if (newName == null || !artifactsAllComplete(d)) {
                // 名字非标准同名目录（历史遗留后缀等）或 14 张产物不齐：删除，后台按新名重建
                try {
                    deleteTree(d);
                    needRebuild++;
                    log.info("分类标注改名后旧产物目录 {} 无法直接迁移，删除待后台重建", d);
                } catch (IOException e) {
                    log.warn("删除 {} 失败: {}", d, e.toString());
                }
                continue;
            }
            Path target = sum.resolve(newName);
            if (Files.exists(target)) {
                try {
                    deleteTree(target);
                } catch (IOException e) {
                    log.warn("删除 {} 失败: {}", target, e.toString());
                }
            }
            if (Files.exists(target)) {
                try {
                    deleteTree(d);
                    needRebuild++;
                    log.warn("目标产物目录 {} 清理失败，删除旧目录 {} 待后台重建", target, d);
                } catch (IOException e) {
                    log.warn("删除 {} 失败: {}", d, e.toString());
                }
                continue;
            }
            try {
                Files.move(d, target);
                Map<String, Object> info = readInfo(target);
                info.put("state", t);
                atomicWriteJson(target.resolve(FILE_INFO), info);
                moved++;
                log.info("分类标注改名后产物目录已迁移：{} → {}", d, target);
            } catch (IOException e) {
                log.warn("迁移产物目录 {} → {} 失败，删除待后台重建: {}", d, target, e.toString());
                try {
                    deleteTree(d);
                } catch (IOException ignore) {
                }
                needRebuild++;
            }
        }
        return new RenameArtifactsResult(moved, needRebuild);
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

    /** kind → 中文短名（用于任务阶段提示文案） */
    private String kindLabel(String kind) {
        return switch (kind) {
            case "same" -> "交集图";
            case "max" -> "多数图";
            case "avg" -> "均值图";
            case "major8" -> "多数块 8×8";
            case "avg8" -> "均值块 8×8";
            case "major32" -> "多数块 32×32";
            case "avg32" -> "均值块 32×32";
            default -> kind;
        };
    }

    /** 历史产物 info.json 的 uniqueCov 键为旧 kind 短码 → 迁移为 kind 全名；非 map / 空返回 null */
    private static Map<String, Object> normUniqueCov(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(UNIQ_KIND_ALIAS.getOrDefault(String.valueOf(e.getKey()), String.valueOf(e.getKey())), e.getValue());
        }
        return out.isEmpty() ? null : out;
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

    /** info.json 里 clickLeft/clickTop 归一化为 int：null / 负数 → -1（与 commonClick 的「无有效坐标 = -1」同语义）。 */
    private static int infoClick(Object v) {
        return v instanceof Number n && n.intValue() >= 0 ? n.intValue() : -1;
    }

    /** 读取样本并校验分辨率与组内基准一致：同一窗口的同组截图分辨率本就相同，不一致直接报错（禁止缩放混用不同分辨率样本）。 */
    private BufferedImage readScaled(Path p, int w, int h) throws IOException {
        BufferedImage bi = ImageIO.read(p.toFile());
        if (bi == null) {
            return null;
        }
        if (bi.getWidth() != w || bi.getHeight() != h) {
            throw new IOException("样本分辨率与组内不一致，无法合成对照图：" + p.getFileName()
                    + " = " + bi.getWidth() + "x" + bi.getHeight() + "，组内基准 = " + w + "x" + h
                    + "（同一分类的样本须同一分辨率，请重新在同分辨率下标注）");
        }
        return bi;
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
        Files.deleteIfExists(tmp);
        Files.writeString(tmp, JSON.writeValueAsString(m));
        moveReplace(tmp, json);
    }

    private void atomicWritePng(BufferedImage img, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
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

    /** 递归删除整个 `summary/` 产物目录（一键重建前清场；单个文件删除失败仅告警，不中断后续重建） */
    private void wipeSummary() {
        Path root = storage.summary();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("清理 summary/ 时无法删除 {}（保留并随重建覆盖，不影响运行）：{}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("清理 summary/ 失败：{}", e.toString());
        }
    }

    /* ---------------------------------------------------------------- 模型 */

    /** 后台分析任务（running → done / error） */
    public static class Task {
        public final String taskId;
        public final boolean force;
        /** true = 一键重建：开始前先清空整个 summary/ 再全量分析 */
        public final boolean rebuild;
        public volatile String status = "running";
        public volatile String message = "";
        public volatile int total;
        public volatile int processed;
        public volatile int errors;
        /** 正在处理的分类展示文案 */
        public volatile String current = "";
        /** 当前阶段：1 = 逐分类生成 7 张基础对照图；2 = 生成各分类 7 张 -unique 独有区图 */
        public volatile int stage = 1;

        Task(String taskId, boolean force) {
            this(taskId, force, false);
        }

        Task(String taskId, boolean force, boolean rebuild) {
            this.taskId = taskId;
            this.force = force;
            this.rebuild = rebuild;
        }
    }
}
