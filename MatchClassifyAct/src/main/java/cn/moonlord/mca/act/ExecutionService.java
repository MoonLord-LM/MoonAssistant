package cn.moonlord.mca.act;

import cn.moonlord.mca.capture.ScreenCaptureService;
import cn.moonlord.mca.capture.WindowFinder;
import cn.moonlord.mca.capture.WindowInfo;
import cn.moonlord.mca.capture.WindowResizer;
import cn.moonlord.mca.config.CaptureProperties;
import cn.moonlord.mca.config.ExecuteProperties;
import cn.moonlord.mca.config.StoragePaths;
import cn.moonlord.mca.mark.CaptureMark;
import cn.moonlord.mca.mark.ClassifyStore;
import cn.moonlord.mca.mark.ThinkService;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行模式的运行主轴：周期性截取目标窗口画面（必要时先 resize 对齐到与标注样本相同的尺寸），
 * 按<b>运行时算法</b>（{@code runtime/}，算法调优选出的「综合最佳算法」，见 {@link RuntimeService}）
 * 识别当前状态，并把结果连同动作 / 点击坐标发布成 Snapshot 供控制台页展示与执行。
 *
 * <p>没有 runtime/ 落地物（还没跑过算法调优、或整目录被删）时不识别，直接给「先完成算法调优」的提示；
 * 无法区分（最高匹配度被 ≥2 个分类并列）时同样不动作，并把并列的分类名称交代给页面。
 *
 * <p>与「标注模式的截图循环」是同一层截图 / 调窗机制，但触发方式不同：这里每一轮识别都由控制台页按需触发
 * （「立即识别」单次，或页面「开启自动识别」逐轮调用），服务端不自己起周期任务；
 * 截图只用于识别与展示、不写盘。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    /** 与截图循环一致：强制缩放窗口后，等窗口完成重排再重截验证的时长（毫秒） */
    private static final int RESIZE_SETTLE_MS = 400;
    /** 单轮内「截图 → 调窗 → 重截」的最多轮数 */
    private static final int MAX_VERIFY_ATTEMPTS = 3;
    /** 找不到目标窗口时的告警节流间隔（毫秒） */
    private static final long FIND_FAIL_LOG_INTERVAL = 20 * 1000L;
    /** 识别异常时的告警节流间隔（毫秒）：错误已进快照由页面展示，无需每轮刷屏 */
    private static final long CLASSIFY_FAIL_LOG_INTERVAL = 20 * 1000L;
    /** 等待「在途识别轮」完成的上限（毫秒）：超过则不再等，直接返回当前已有快照 */
    private static final long BUSY_WAIT_MS = 4000;

    /** 没有 runtime/ 落地物时的提示：本轮不识别，让用户先完成算法调优 */
    private static final String NO_RUNTIME_HINT = "还没有可用于执行的运行时算法：请先到「算法调优」完成一轮"
            + "（「刷新算法特征」或「自动调整参数」），把综合最佳算法落地到 runtime/ 后，执行与智能推荐会自动按它识别。";

    private final WindowFinder windowFinder;
    private final ScreenCaptureService screenCaptureService;
    private final WindowResizer windowResizer;
    private final RuntimeService runtimeService;
    private final WindowClicker windowClicker;
    private final ClassifyStore classifyStore;
    private final CaptureProperties captureProperties;
    private final ExecuteProperties executeProperties;
    private final StoragePaths storage;
    /** 标注分析服务：执行页存入分类样本后请求其自动补齐/刷新汇总产物。 */
    private final ThinkService thinkService;

    /** 防重入：页面可能并发触发（「立即识别」与「开启自动识别」循环重叠），只允许一个真正执行截图 / 识别。 */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** 最近一次识别快照（含画面缓存）。 */
    private volatile Snapshot latest = null;

    /** 运行期点击方式（null = 使用 execute.click-mode 配置默认）；控制台页可实时切换，重启后恢复配置默认。 */
    private volatile String clickMode = null;

    private long nextFindFailLogTime = 0;
    private long nextClassifyFailLogTime = 0;

    /* ================================================================ 对外控制 ========== */

    /** 当前生效的鼠标点击方式（运行期切换值优先；未切换时用 execute.click-mode 默认）。 */
    public String getClickMode() {
        String m = clickMode;
        return (m == null || m.isBlank()) ? executeProperties.getClickMode() : m;
    }

    /** 运行期切换鼠标点击方式（post = 后台消息 / screen = 前台点击）。非法值被忽略并保留原值。 */
    public void setClickMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return;
        }
        String m = mode.trim().toLowerCase();
        if (!WindowClicker.MODE_POST.equals(m) && !WindowClicker.MODE_SCREEN.equals(m)) {
            log.warn("忽略非法的点击方式：{}", mode);
            return;
        }
        clickMode = m;
        log.info("执行点击方式已切换为：{}（{}）", m,
                WindowClicker.MODE_SCREEN.equals(m) ? "前台点击：真实鼠标输入，模拟器/游戏需用此项" : "后台消息：完整点击消息序列，不抢鼠标焦点");
    }

    /**
     * 立即触发一轮「截图 + 识别」。供页面「立即识别」按钮与「开启自动识别」循环逐轮调用。
     *
     * @return 本轮产生的最新快照（若已有在途轮且等待超时，则返回当前已有快照）
     */
    public Snapshot refreshNow() {
        long deadline = System.currentTimeMillis() + BUSY_WAIT_MS;
        while (busy.get() && System.currentTimeMillis() < deadline) {
            sleepQuiet(100);
        }
        if (!busy.compareAndSet(false, true)) {
            return latestSnapshot();
        }
        try {
            latest = doSnapshot();
        } finally {
            busy.set(false);
        }
        return latestSnapshot();
    }

    /** 最近一次识别快照；尚未产生任何一轮时给出占位快照。 */
    public Snapshot latestSnapshot() {
        Snapshot s = latest;
        if (s != null) {
            return s;
        }
        return placeholderSnapshot();
    }

    /* ================================================================ 核心一轮 ========== */

    @SneakyThrows
    private Snapshot doSnapshot() {
        long t0 = System.currentTimeMillis();
        int targetW = captureProperties.getResizeWidth();
        int targetH = captureProperties.getResizeHeight();
        boolean enforce = targetW > 0 && targetH > 0;

        WindowInfo window = windowFinder.findTarget(captureProperties.getWindowKeywords());
        if (window == null) {
            if (System.currentTimeMillis() > nextFindFailLogTime) {
                nextFindFailLogTime = System.currentTimeMillis() + FIND_FAIL_LOG_INTERVAL;
                log.warn("执行模式：未找到目标窗口（关键字：{}）", captureProperties.getWindowKeywords());
            }
            return errorSnapshot(window, "未找到目标窗口（标题关键字：" + captureProperties.getWindowKeywords() + "）。请先启动目标程序。",
                    System.currentTimeMillis() - t0);
        }
        if (window.isMinimized()) {
            log.debug("执行模式：窗口 [{}] 已最小化，无法截图", window.getTitle());
            return errorSnapshot(window, "目标窗口「" + window.getTitle() + "」当前已最小化，无法截图与执行动作。",
                    System.currentTimeMillis() - t0);
        }

        try {
            FrameCapture cap = captureCompliant(window, targetW, targetH, enforce);
            if (cap == null || cap.image == null) {
                String msg = "截图失败：目标窗口未捕获到画面"
                        + (enforce ? "，或窗口尺寸尚未调整到目标 " + targetW + "x" + targetH + "（每轮识别都会先调整重试）" : "");
                return errorSnapshot(window, msg, System.currentTimeMillis() - t0);
            }
            // 截图成功后再取一次窗口几何信息，保证展示的是最新（resize 可能已移动/缩放窗口）
            WindowInfo finalWin = cap.window;
            WindowInfo fresh = windowFinder.findTarget(captureProperties.getWindowKeywords());
            if (fresh != null && !fresh.isMinimized()) {
                finalWin = fresh;
            }

            FrameClassifier.Outcome oc = runtimeService.classify(cap.image);
            if (oc == null) {
                return errorSnapshot(finalWin, NO_RUNTIME_HINT, System.currentTimeMillis() - t0);
            }
            if (oc.ambiguous) {
                return errorSnapshot(finalWin, "无法区分：当前画面与「" + String.join("」「", oc.tiedStates)
                        + "」这几个分类的匹配度并列最高，本轮不执行动作。请到「算法调优」调整权重或补充标注"
                        + "（扩大分类间的区分度）后再执行。", System.currentTimeMillis() - t0);
            }
            return snapshotNow(finalWin, cap.image, oc, null, System.currentTimeMillis() - t0, oc.elapsedMs);
        } catch (Exception e) {
            // 异常兜底：出错误快照供页面展示，不让定时轮重复抛栈
            if (System.currentTimeMillis() > nextClassifyFailLogTime) {
                nextClassifyFailLogTime = System.currentTimeMillis() + CLASSIFY_FAIL_LOG_INTERVAL;
                log.warn("执行模式识别一轮异常（页面已展示错误，下轮自动重试）：{}", e.toString());
            }
            return errorSnapshot(window, "识别失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    System.currentTimeMillis() - t0);
        }
    }

    /**
     * 截图并校验尺寸：不符则强制调窗后当轮内重截验证（与截图循环同一套逻辑），
     * 保证「画面 = 目标窗口内容像素」、图片坐标可直接用于点击。
     */
    private FrameCapture captureCompliant(WindowInfo window, int targetW, int targetH, boolean enforce) {
        WindowInfo current = window;
        for (int attempt = 1; attempt <= MAX_VERIFY_ATTEMPTS; attempt++) {
            BufferedImage image = screenCaptureService.captureWindow(current);
            if (image == null) {
                return null;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            if (!enforce || (w == targetW && h == targetH)) {
                return new FrameCapture(current, image);
            }
            if (attempt == MAX_VERIFY_ATTEMPTS) {
                break;
            }
            boolean adjusted = windowResizer.resizeWindowToPngSize(
                    current.getHwnd(), current.getTitle(), w, h, targetW, targetH);
            if (!adjusted) {
                sleepQuiet(200);
                return null;
            }
            sleepQuiet(RESIZE_SETTLE_MS);
            WindowInfo fresh = windowFinder.findTarget(captureProperties.getWindowKeywords());
            if (fresh != null && !fresh.isMinimized()) {
                current = fresh;
            }
        }
        return null;
    }

    /* ================================================================ Snapshot 组装 ===== */

    /** 一次「截图 + 识别」的结果快照（frame 只服务端缓存，不出 JSON）。 */
    public record Snapshot(
            long at,
            String error,
            boolean windowFound,
            String windowTitle,
            int windowLeft,
            int windowTop,
            int windowWidth,
            int windowHeight,
            boolean recognized,
            String state,
            String action,
            Integer left,
            Integer top,
            double bestDiffPercent,
            double thresholdPercent,
            String matchedSample,
            int scannedSamples,
            int totalSamples,
            Map<String, String> skippedGroups,
            List<FrameClassifier.Candidate> candidates,
            long captureMs,
            long classifyMs,
            int imageWidth,
            int imageHeight,
            @JsonIgnore BufferedImage frame
    ) {
    }

    private Snapshot placeholderSnapshot() {
        return new Snapshot(System.currentTimeMillis(), "尚未产生识别结果：请先点「立即识别」。",
                false, null, 0, 0, 0, 0,
                false, null, null, null, null, -1, executeProperties.getMatchThresholdPercent(),
                null, 0, 0, Map.of(), new ArrayList<>(), 0, 0, 0, 0, null);
    }

    /** 无画面的错误快照（窗口缺失 / 截图失败 / 最小化等），保留窗口信息便于界面提示。 */
    private Snapshot errorSnapshot(WindowInfo window, String message, long captureMs) {
        return snapshotNow(window, null, null, message, captureMs, 0);
    }

    private Snapshot snapshotNow(WindowInfo window, BufferedImage image, FrameClassifier.Outcome oc,
                                 String forcedError, long captureMs, long classifyMs) {
        long now = System.currentTimeMillis();
        String error = forcedError;
        boolean recognized = false;
        String state = null;
        String action = CaptureMark.ACTION_NONE;
        Integer left = null;
        Integer top = null;
        double bestDiff = -1;                       // -1 = 无可比样本/暂无差异（避免 NaN 进 JSON）
        String matched = null;
        int scanned = 0;
        int total = 0;
        List<FrameClassifier.Candidate> candidates = new ArrayList<>();

        Map<String, String> skippedGroups = Map.of();
        if (oc != null) {
            scanned = oc.scannedSamples;
            total = oc.totalSamples;
            skippedGroups = oc.skipped;
            if (oc.candidates != null) {
                candidates = oc.candidates;
            }

            // 动作 / 点击坐标直接取自命中分类（runtime/algorithm.json 与标注同源）
            if (oc.recognized) {
                recognized = true;
                state = oc.bestState;
                bestDiff = Double.isNaN(oc.bestDiffPercent) ? -1 : oc.bestDiffPercent;
                matched = oc.bestFile;
                if (oc.action != null) {
                    action = oc.action;
                }
                // 坐标只对「鼠标点击」分类对外暴露：无动作分类的注意点只用于产物生成 / 匹配
                left = CaptureMark.ACTION_CLICK.equals(action) ? oc.clickLeft : null;
                top = CaptureMark.ACTION_CLICK.equals(action) ? oc.clickTop : null;
            }
        }

        int w = image == null ? 0 : image.getWidth();
        int h = image == null ? 0 : image.getHeight();
        int winX = 0, winY = 0, winW = 0, winH = 0;
        String winTitle = null;
        boolean winFound = window != null;
        if (window != null) {
            winTitle = window.getTitle();
            Rectangle b = window.getBounds();
            if (b != null) {
                winX = b.x;
                winY = b.y;
                winW = b.width;
                winH = b.height;
            }
        }
        if (error == null && image == null) {
            error = "画面为空：请检查目标窗口是否可见、未被系统遮挡，稍后自动重试。";
        }
        return new Snapshot(now, error, winFound, winTitle, winX, winY, winW, winH,
                recognized, state, action, left, top, bestDiff, executeProperties.getMatchThresholdPercent(),
                matched, scanned, total, skippedGroups, candidates, captureMs, classifyMs, w, h, image);
    }

    /* ================================================================ 画面 PNG 供图 ==== */

    /** 把最新快照里的画面编码成 PNG（带缓存：同一快照只编一次）。 */
    public byte[] pngOfLatest() {
        Snapshot s = latestSnapshot();
        if (s.frame() == null) {
            return null;
        }
        byte[] cached = pngCache;
        Snapshot cachedFor = pngCacheFor;
        if (cached != null && cachedFor == s) {
            return cached;
        }
        byte[] png = encodePng(s.frame());
        if (png != null) {
            pngCache = png;
            pngCacheFor = s;
        }
        return png;
    }

    private volatile byte[] pngCache = null;
    private volatile Snapshot pngCacheFor = null;

    private byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(image.getWidth() * image.getHeight() / 2);
            boolean ok = ImageIO.write(image, "png", bos);
            return ok ? bos.toByteArray() : null;
        } catch (Exception e) {
            log.warn("画面 PNG 编码失败：{}", e.toString());
            return null;
        }
    }

    /* ================================================================ 动作执行 ========== */

    /** 触发执行：按最近一次识别结果的动作 / 坐标发送鼠标左键点击，不重新截图识别。
     *  本页为人工复核 / 测试，画面已变化时请先点「立即识别」取新结果。
     *
     *  @return 执行结果（业务未就绪时也返回 200 + ok=false + message，便于前端直接提示）
     */
    public Map<String, Object> act() {
        Map<String, Object> res = new LinkedHashMap<>();
        Snapshot s = latestSnapshot();
        // 不设识别阈值门槛：以最近似分类为准（差异分值仅作界面参考）
        if (s.state() == null || s.state().isBlank()) {
            res.put("ok", false);
            res.put("message", "还没有可执行的识别结果：请先点「立即识别」识别出已标注分类后再执行。");
            return res;
        }
        if (!CaptureMark.ACTION_CLICK.equals(s.action()) || s.left() == null || s.top() == null) {
            res.put("ok", false);
            res.put("message", "分类「" + s.state + "」没有可执行的「鼠标点击」动作坐标，无法执行。");
            return res;
        }
        WindowInfo window = windowFinder.findTarget(captureProperties.getWindowKeywords());
        if (window == null || window.getHwnd() == null) {
            res.put("ok", false);
            res.put("message", "目标窗口已不存在，无法发送鼠标点击。");
            return res;
        }
        WindowClicker.Result r = windowClicker.click(window, s.left(), s.top(), getClickMode());
        res.put("ok", r.ok());
        res.put("mode", r.mode());
        res.put("message", r.message());
        res.put("state", s.state());
        res.put("action", s.action());
        res.put("left", s.left());
        res.put("top", s.top());
        res.put("screenX", r.screenX());
        res.put("screenY", r.screenY());
        return res;
    }

    /* ================================================================ 快速标记 ========== */

    /** classify/ 下唯一样本名的时间戳格式（到毫秒，极端同毫秒冲突追加序号）。 */
    private static final DateTimeFormatter SAMPLE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /**
     * 快速标记（识别纠错）：把最近一次识别画面另存为所选分类的新样本，减少「同一画面反复认错」。
     *
     * <p>写入前去重，口径与「存到待标注」完全一致（{@link ScreenCaptureService#duplicateReference} +
     * 手动保存阈值），避免分类样本重复堆叠；被拦截时返回 kind=dup（含 dupOf / diffPercent / threshold），
     * 便于页面提示用户改标既有样本。</p>
     *
     * @return ok=true + 新样本文件名；失败时 ok=false + message（HTTP 200，便于前端直接提示）
     */
    public Map<String, Object> markFrameAsSample(String state) {
        Map<String, Object> res = new LinkedHashMap<>();
        String st = state == null ? "" : state.trim();
        if (st.isEmpty()) {
            res.put("ok", false);
            res.put("message", "未指定要存入的分类（state 为空）");
            return res;
        }
        if (st.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || st.endsWith(".")) {
            res.put("ok", false);
            res.put("message", "分类名包含非法字符，无法作为样本目录");
            return res;
        }
        Snapshot s = latestSnapshot();
        if (s.frame() == null) {
            res.put("ok", false);
            res.put("message", s.error() != null ? s.error() : "当前没有可保存的画面，请先点「立即识别」。");
            return res;
        }
        // 与「存到待标注」同一套去重；阈值 ≤ 0（去重关闭）时不检查
        double threshold = Math.round(captureProperties.getDiffThresholdManualPercent() * 100.0) / 100.0;
        if (threshold > 0) {
            ScreenCaptureService.DuplicateMatch dup = screenCaptureService.duplicateReference(s.frame(), threshold);
            if (dup != null) {
                log.info("执行模式「存入分类」被去重拦截：画面与 {} 的不一致像素点占比 {}% ≤ 阈值 {}%，未存入分类「{}」",
                        dup.name(), pctText(dup.diffPercent()), pctText(threshold), st);
                res.put("ok", false);
                res.put("kind", "dup");
                res.put("dupOf", dup.name());
                res.put("diffPercent", dup.diffPercent());
                res.put("threshold", threshold);
                if (dup.refState() != null && dup.refState().equals(st)) {
                    res.put("message", "当前画面与分类「" + st + "」的样本「" + dup.name() + "」仅 "
                            + pctText(dup.diffPercent()) + "% 像素点不同（≤ " + pctText(threshold)
                            + "% 阈值，视为同一画面），无需重复存入。");
                } else if (dup.refState() != null) {
                    res.put("message", "当前画面与分类「" + dup.refState() + "」的样本「" + dup.name() + "」仅 "
                            + pctText(dup.diffPercent()) + "% 像素点不同（≤ " + pctText(threshold)
                            + "% 阈值，视为同一画面），未存入分类「" + st + "」。如需把它改归「" + st
                            + "」，请在标注模式修改该样本的分类标注。");
                } else {
                    res.put("message", "当前画面与待标注截图「" + dup.name() + "」仅 " + pctText(dup.diffPercent())
                            + "% 像素点不同（≤ " + pctText(threshold)
                            + "% 阈值，视为同一画面），未存入分类「" + st + "」。该画面已在截图列表，请直接在标注模式标注它。");
                }
                return res;
            }
        }
        try {
            Path dir = storage.classify();
            Files.createDirectories(dir);
            String name = uniqueSampleName(dir);
            Path png = dir.resolve(name);
            Path tmp = dir.resolve(name + ".tmp");
            boolean wrote = ImageIO.write(s.frame(), "png", tmp.toFile());
            if (!wrote) {
                Files.deleteIfExists(tmp);
                res.put("ok", false);
                res.put("message", "画面编码为 PNG 失败，未保存");
                return res;
            }
            try {
                Files.move(tmp, png, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, png, StandardCopyOption.REPLACE_EXISTING);
            }
            classifyStore.saveSample(name, st);
            thinkService.requestRecompute();   // 样本集合已变化：自动补齐/刷新该分类对照图，无需手动去汇总分析
            log.info("执行模式快速标记：画面 {}x{} 另存为分类「{}」的样本 {}（已请求后台重算）",
                    s.imageWidth(), s.imageHeight(), st, name);
            res.put("ok", true);
            res.put("name", name);
            res.put("state", st);
            res.put("imageWidth", s.imageWidth());
            res.put("imageHeight", s.imageHeight());
            return res;
        } catch (Exception e) {
            log.error("执行模式快速标记保存失败：{}", e.toString());
            res.put("ok", false);
            res.put("message", "保存样本失败：" + e.getMessage());
            return res;
        }
    }

    /* ================================================================ 另存为待标注截图 ===== */

    /**
     * 把当前识别画面另存为 capture/ 下的原始截图（未标注，不写标注数据）：执行画面本身不落盘，
     * 这里以「另存」方式与截图循环产物同目录、同命名，需要人工精确标注 / 修正坐标时再走「标注模式」。
     *
     * <p>保存前执行与自动截图循环同一套去重判定（{@link ScreenCaptureService#duplicateReference}），
     * 但套「手动保存」阈值 {@code capture.diff-threshold-manual-percent}：与任一张历史画面几乎相同即
     * 拒绝另存，避免堆积重复的待标注图；差异与阈值都先四舍五入到两位小数，拦截效果与提示文字一致。
     * 阈值 ≤ 0（关闭去重）时直接保存。</p>
     *
     * @return ok=true + 新文件名；ok=false + kind=dup（被去重拦截）/ message（HTTP 200，便于前端直接提示）
     */
    public Map<String, Object> saveFrameToCapture() {
        Map<String, Object> res = new LinkedHashMap<>();
        Snapshot s = latestSnapshot();
        if (s.frame() == null) {
            res.put("ok", false);
            res.put("message", s.error() != null ? s.error() : "当前没有可保存的画面，请先点「立即识别」。");
            return res;
        }
        // 与去重判定同一口径：阈值先四舍五入到两位小数再参与比较与提示（配置值通常 ≤2 位小数，此处幂等）
        double threshold = Math.round(captureProperties.getDiffThresholdManualPercent() * 100.0) / 100.0;
        if (threshold > 0) {
            ScreenCaptureService.DuplicateMatch dup = screenCaptureService.duplicateReference(s.frame(), threshold);
            if (dup != null) {
                String who = dup.refState() != null
                        ? "分类「" + dup.refState() + "」的样本「" + dup.name() + "」"
                        : "截图「" + dup.name() + "」";
                log.info("执行模式「存到待标注」被去重拦截：画面与 {} 的不一致像素点占比 {}% ≤ 阈值 {}%，未另存",
                        who, pctText(dup.diffPercent()), pctText(threshold));
                res.put("ok", false);
                res.put("kind", "dup");
                res.put("dupOf", dup.name());
                res.put("diffPercent", dup.diffPercent());
                res.put("threshold", threshold);
                res.put("message", "当前画面与" + who + "仅 " + pctText(dup.diffPercent())
                        + "% 像素点不同（≤ " + pctText(threshold) + "% 阈值，视为同一画面），本次未另存。");
                return res;
            }
        }
        try {
            Path dir = storage.capture();
            Files.createDirectories(dir);
            String name = uniqueCaptureName();
            Path png = dir.resolve(name);
            Path tmp = dir.resolve(name + ".tmp");
            boolean wrote = ImageIO.write(s.frame(), "png", tmp.toFile());
            if (!wrote) {
                Files.deleteIfExists(tmp);
                res.put("ok", false);
                res.put("message", "画面编码为 PNG 失败，未保存");
                return res;
            }
            try {
                Files.move(tmp, png, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, png, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("执行模式把画面 {}x{} 另存为 capture/ 待标注截图：{}", s.imageWidth(), s.imageHeight(), name);
            res.put("ok", true);
            res.put("name", name);
            res.put("imageWidth", s.imageWidth());
            res.put("imageHeight", s.imageHeight());
            return res;
        } catch (Exception e) {
            log.error("执行模式另存画面到 capture/ 失败：{}", e.toString());
            res.put("ok", false);
            res.put("message", "保存失败：" + e.getMessage());
            return res;
        }
    }

    /** 百分比显示文本：整数直显，非整数留两位小数并去尾零。
     *  拒存提示会把实际差异与阈值并排比较：精度不足时「确实低于阈值」的差异会被显示成与阈值相等，
     *  观感矛盾；判定始终用原始值比较，与此处展示精度无关。 */
    private static String pctText(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        String s = String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
        if (s.endsWith(".")) {
            // 两位四舍五入后恰好成整数：仍可能与阈值并排相等，改三位小数
            s = String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "");
            s = s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        }
        return s;
    }

    /** 生成 capture/ 下不冲突的新截图文件名（IMG_yyyyMMdd_HHmmss_SSS.png，同毫秒追加 _2、_3…；
     *  并避开 classify/ 同名——两目录同名时标注列表会以已标注版本为准，避免保存了却看不到）。 */
    private String uniqueCaptureName() {
        String base = "IMG_" + SAMPLE_TS.format(LocalDateTime.now());
        Path cap = storage.capture(), cls = storage.classify();
        String name = base + ".png";
        for (int i = 2; Files.exists(cap.resolve(name)) || Files.exists(cls.resolve(name)); i++) {
            name = base + "_" + i + ".png";
        }
        return name;
    }

    /** 在 classify/ 下生成一个不冲突的样本文件名（IMG_yyyyMMdd_HHmmss_SSS.png，同毫秒时追加 _2、_3…）。 */
    private String uniqueSampleName(Path dir) {
        String base = "IMG_" + SAMPLE_TS.format(LocalDateTime.now());
        String name = base + ".png";
        for (int i = 2; Files.exists(dir.resolve(name)); i++) {
            name = base + "_" + i + ".png";
        }
        return name;
    }

    /* ================================================================ 杂项 ============== */

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record FrameCapture(WindowInfo window, BufferedImage image) {
    }
}
