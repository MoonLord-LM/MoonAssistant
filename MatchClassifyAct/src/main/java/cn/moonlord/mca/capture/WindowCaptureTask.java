package cn.moonlord.mca.capture;

import cn.moonlord.mca.config.CaptureProperties;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 截图任务（标注模式的采集端）：默认不开启，需在控制台网页点击「自动采集」后才开始截取目标窗口并保存 PNG。
 *
 * <p>节拍用 Spring fixedDelay：每处理完一帧（截图 + 比对去重基准 + 保存/判重丢弃）后再等
 * {@code capture.interval-ms} 取下一帧——帧处理耗时多少就顺延多少，绝不与上一帧并发或排队积压。</p>
 *
 * <p>截图基于 Windows Graphics Capture，不切前台、不置顶，目标窗口在后台/被遮挡时也能抓到它自身的画面。</p>
 *
 * <p>尺寸强校验：{@code capture.resize-width}/{@code capture.resize-height} 都 &gt;0 时
 * <b>只有恰好是该尺寸的 PNG 才会被保存</b>，不符则用 {@link WindowResizer#resizeWindowToPngSize}
 * 强制调窗并在当轮内重截验证，持续不达标按下方阈值自动暂停。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WindowCaptureTask implements ApplicationRunner {

    private final WindowFinder windowFinder;
    private final ScreenCaptureService screenCaptureService;
    private final WindowResizer windowResizer;
    private final CaptureProperties properties;

    /** 强制缩放窗口后，等窗口完成重排再重截验证的时长（毫秒） */
    private static final int RESIZE_SETTLE_MS = 400;

    /** 单轮内「截图 → 调窗 → 重截」的最多轮数（含首次），避免一帧反复调窗 */
    private static final int MAX_VERIFY_ATTEMPTS = 3;

    /** 找不到窗口时的告警节流间隔（毫秒） */
    private static final long FIND_FAIL_LOG_INTERVAL = 30 * 1000L;

    /** 截图尺寸持续不达标时的告警节流间隔（毫秒） */
    private static final long SIZE_FAIL_LOG_INTERVAL = 30 * 1000L;

    /** 相似帧汇总日志的最小间隔（毫秒），避免画面静止时刷屏 */
    private static final long SKIP_LOG_INTERVAL = 30 * 1000L;

    /** 连续多少次「强制缩放后截图尺寸毫无变化」即判定窗口无法被调整、立即自动暂停 */
    private static final int SIZE_NO_CHANGE_AUTO_STOP_TIMES = 3;

    /** 兜底：连续多少轮尺寸始终不达标即自动暂停。按「轮」而非时长计，因为每轮耗时不固定 */
    private static final int SIZE_STUCK_AUTO_STOP_ROUNDS = 10;

    /** 截图是否暂停；初始 {@code true} = 启动后不自动截图，需控制台网页「自动采集」开启 */
    private final AtomicBoolean paused = new AtomicBoolean(true);

    /** 防重入：定时轮与手动首截只允许一个真正执行 */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private long nextFindFailLogTime = 0;
    private long nextSizeFailLogTime = 0;

    /** 距上次汇总日志以来被丢弃的相似帧数 */
    private long skippedSinceLog = 0;

    /** 下一轮允许打印相似帧汇总日志的时间点 */
    private long nextSkipLogTime = 0;

    /** 尺寸连续未达标的轮数（resume 时清零） */
    private final AtomicInteger sizeStuckRounds = new AtomicInteger();

    /** 「强制缩放后尺寸毫无变化」的连续次数（resume / 尺寸有变化 / 尺寸达标时清零） */
    private final AtomicInteger noChangeResizes = new AtomicInteger();

    /** 最近一次自动暂停原因，供 /api/app/meta 轮询取走弹窗；用户手动开启时清除，便于下次失败再提示 */
    private volatile String autoStopReason = null;

    /** 最近一次截图结果（saved / dup），供 /api/app/meta 轮询取走做右下角即时提示；
     *  只保留最新一条，完整历史见 {@link #shotHistory} */
    private volatile ShotNotice shotNotice = null;

    /** 截图结果全局序号（从 1 起单调递增），前端按 seq 增量补齐历史 */
    private final AtomicLong shotSeqGen = new AtomicLong();

    /** 每轮截图结果的完整历史（含被轮询覆盖的中间条），供「历史日志」回溯；内存保留、重启清空。
     *  访问需在 {@code shotHistory} 上同步 */
    private final List<ShotNotice> shotHistory = new ArrayList<>();

    /** 成功保存截图的总次数（单调递增）：前端轮询看到它变化即静默刷新列表 */
    private volatile long savedSeq = 0;

    /** 已使用的最大 seq：前端据此识别「后端已重启」（归零）并重置增量基线 */
    public long getShotSeq() {
        return shotSeqGen.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    public String getAutoStopReason() {
        return autoStopReason;
    }

    /** 最近一次截图结果；尚未完成任何一轮时为 null */
    public ShotNotice getShotNotice() {
        return shotNotice;
    }

    /** 返回 {@code seq > afterSeq} 的历史记录快照；afterSeq = -1 表示从第一条起全量 */
    public List<ShotNotice> shotHistorySince(long afterSeq) {
        synchronized (shotHistory) {
            if (shotHistory.isEmpty()) {
                return new ArrayList<>();
            }
            List<ShotNotice> out = new ArrayList<>();
            for (ShotNotice n : shotHistory) {
                if (n.seq > afterSeq) {
                    out.add(n);
                }
            }
            return out;
        }
    }

    public long getSavedSeq() {
        return savedSeq;
    }

    /** 手动开启 / 暂停；与截图线程的自动暂停互斥，避免「开启清零」与「自动停置位」交错出错乱 */
    public synchronized void setPaused(boolean p) {
        paused.set(p);
        if (p) {
            log.info("截图任务已暂停：不再保存新截图（控制台仍可用）");
            return;
        }
        sizeStuckRounds.set(0);
        noChangeResizes.set(0);
        autoStopReason = null;
        log.info("截图任务已开启：每处理完一帧（截图 + 匹配比对）后等 {} ms 再取下一帧，"
                + "尺寸校验 = {}x{}", properties.getIntervalMs(),
                properties.getResizeWidth(), properties.getResizeHeight());
        // 先异步跑一轮，让点按钮后尽快出图（与定时轮撞车则让位）
        Thread first = new Thread(this::tick, "capture-first-shot");
        first.setDaemon(true);
        first.start();
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("截图任务默认未开启：启动时不会自动截图。请在弹出的控制台网页点击「自动采集」手动开始；"
                + "若网页未自动打开，可手动访问 http://127.0.0.1:8080/annotate");
    }

    /** 定时入口（fixedDelay 语义见类注释） */
    @Scheduled(initialDelayString = "${capture.interval-ms:1000}",
            fixedDelayString = "${capture.interval-ms:1000}")
    public void captureTick() {
        tick();
    }

    /** 单轮入口：暂停中或上一轮未结束时直接跳过 */
    private void tick() {
        if (paused.get()) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            doTick();
        } finally {
            busy.set(false);
        }
    }

    @SneakyThrows
    private void doTick() {
        int targetW = properties.getResizeWidth();
        int targetH = properties.getResizeHeight();
        boolean enforce = targetW > 0 && targetH > 0;

        WindowInfo window = windowFinder.findTarget(properties.getWindowKeywords());
        if (window == null) {
            logFindFailure();
            return;
        }
        if (window.isMinimized()) {
            log.debug("窗口 [{}] 已最小化，跳过本次截图", window.getTitle());
            return;
        }

        int prevW = 0, prevH = 0;   // 最近一次强制缩放前截到的尺寸，用于判断缩放是否真的改变了窗口
        for (int attempt = 1; attempt <= MAX_VERIFY_ATTEMPTS; attempt++) {
            BufferedImage image = screenCaptureService.captureWindow(window);
            if (image == null) {
                return;   // 窗口不可捕获/超时，交给下一轮
            }
            int imageW = image.getWidth();
            int imageH = image.getHeight();

            // 与缩放前一模一样 = SetWindowPos 没能让窗口产生任何变化
            if (prevW > 0 && imageW == prevW && imageH == prevH) {
                if (noChangeResizes.incrementAndGet() >= SIZE_NO_CHANGE_AUTO_STOP_TIMES) {
                    stopByUnresizable(window, imageW, imageH, targetW, targetH);
                    return;
                }
                log.warn("窗口 [{}] 强制缩放后截图尺寸仍是 {}x{}，窗口未随调整变化（已连续 {}/{} 次无变化）",
                        window.getTitle(), imageW, imageH,
                        noChangeResizes.get(), SIZE_NO_CHANGE_AUTO_STOP_TIMES);
            } else if (prevW > 0) {
                noChangeResizes.set(0);   // resize 生效
            }

            if (!enforce || (imageW == targetW && imageH == targetH)) {
                if (paused.get()) {
                    return;   // 截图期间被暂停，放弃这帧
                }
                ScreenCaptureService.DuplicateMatch dup = screenCaptureService.duplicateReference(image);
                if (dup != null) {
                    logSkippedSimilar();
                    recordShotResult("dup", dup.name(), dup.diffPercent(), dup.refState(), dup.threshold());
                } else {
                    save(image, window);
                }
                sizeStuckRounds.set(0);
                noChangeResizes.set(0);
                return;
            }

            log.warn("第 {}/{} 次截图尺寸为 {}x{}，不符合目标 {}x{}，已丢弃该帧并强制调整窗口",
                    attempt, MAX_VERIFY_ATTEMPTS, imageW, imageH, targetW, targetH);
            if (attempt == MAX_VERIFY_ATTEMPTS) {
                break;
            }

            boolean adjusted = windowResizer.resizeWindowToPngSize(
                    window.getHwnd(), window.getTitle(), imageW, imageH, targetW, targetH);
            if (!adjusted) {
                // 窗口最小化 / 目标超屏等暂不可调，本帧不保存
                logSizeStuck(window, imageW, imageH, targetW, targetH);
                onSizeStuck(window, targetW, targetH);
                return;
            }
            prevW = imageW;
            prevH = imageH;
            sleep(RESIZE_SETTLE_MS);

            // 调窗后几何 / 最小化状态可能已变
            WindowInfo fresh = windowFinder.findTarget(properties.getWindowKeywords());
            if (fresh != null) {
                window = fresh;
            }
        }

        logSizeStuck(window, -1, -1, targetW, targetH);
        onSizeStuck(window, targetW, targetH);
    }

    /**
     * 手动采集一次（「手动采集」按钮 → {@code /api/capture/manual}）：与自动轮同一套截图 / 调窗逻辑，
     * 差别只在去重套「手动保存」阈值（{@code capture.diff-threshold-manual-percent}，
     * 见 {@link ScreenCaptureService#duplicateReference}），且不触发自动暂停、不写截图事件流；
     * 不受截图开关约束，与定时轮经 {@link #busy} 互斥（撞车时先等其收尾）。
     *
     * @return 本次结果（{@link ManualShotResult}）
     */
    @SneakyThrows
    public ManualShotResult manualShot() {
        for (int i = 0; i < 20 && busy.get(); i++) {
            sleep(100);   // 等定时轮 / 首截线程收尾，避免两路同时抓帧
        }
        if (!busy.compareAndSet(false, true)) {
            return ManualShotResult.of("busy");
        }
        try {
            return doManualShot();
        } catch (Exception e) {
            log.error("手动采集异常：{}", e.toString());
            return ManualShotResult.of("error");
        } finally {
            busy.set(false);
        }
    }

    /** 手动采集执行体；与 {@link #doTick()} 的差异见 {@link #manualShot()} */
    @SneakyThrows
    private ManualShotResult doManualShot() {
        int targetW = properties.getResizeWidth();
        int targetH = properties.getResizeHeight();
        boolean enforce = targetW > 0 && targetH > 0;

        WindowInfo window = windowFinder.findTarget(properties.getWindowKeywords());
        if (window == null) {
            log.warn("手动采集：未找到标题匹配的窗口（关键字: {}）", properties.getWindowKeywords());
            return ManualShotResult.of("window-not-found");
        }
        if (window.isMinimized()) {
            log.warn("手动采集：窗口 [{}] 已最小化，无法截图", window.getTitle());
            return ManualShotResult.of("minimized");
        }
        // 与去重判定同口径：阈值先四舍五入到两位小数
        double threshold = Math.round(properties.getDiffThresholdManualPercent() * 100.0) / 100.0;

        for (int attempt = 1; attempt <= MAX_VERIFY_ATTEMPTS; attempt++) {
            BufferedImage image = screenCaptureService.captureWindow(window);
            if (image == null) {
                log.warn("手动采集：窗口 [{}] 截图失败（不可捕获或抓帧超时）", window.getTitle());
                return ManualShotResult.of("capture-fail");
            }
            int imageW = image.getWidth();
            int imageH = image.getHeight();
            if (!enforce || (imageW == targetW && imageH == targetH)) {
                // 去重判定与「提示用差异」共用一次扫描；minDiffPercent 落盘前算，故不含本张
                ScreenCaptureService.DedupScan scan = screenCaptureService.scanReference(image, threshold);
                ScreenCaptureService.DuplicateMatch dup = scan.dup();
                if (dup != null) {
                    log.info("手动采集被去重拦截：画面与 {} 的不一致像素点占比 {}% ≤ 阈值 {}%，未保存",
                            dup.name(), dup.diffPercent(), dup.threshold());
                    return new ManualShotResult("dup", dup.name(), dup.diffPercent(), dup.refState(), dup.threshold());
                }
                try {
                    Path file = screenCaptureService.savePng(image, window);
                    log.info("手动采集已保存（{}x{}）：{}", imageW, imageH, file);
                    return new ManualShotResult("saved", file.getFileName().toString(), 0, null, 0, scan.minDiffPercent());
                } catch (Exception e) {
                    log.error("手动采集保存 PNG 失败：{}", e.toString());
                    return ManualShotResult.of("save-fail");
                }
            }
            log.warn("手动采集第 {}/{} 次截图尺寸为 {}x{}，不符合目标 {}x{}，已丢弃该帧并强制调整窗口",
                    attempt, MAX_VERIFY_ATTEMPTS, imageW, imageH, targetW, targetH);
            if (attempt == MAX_VERIFY_ATTEMPTS) {
                break;
            }
            boolean adjusted = windowResizer.resizeWindowToPngSize(
                    window.getHwnd(), window.getTitle(), imageW, imageH, targetW, targetH);
            if (!adjusted) {
                log.warn("手动采集：窗口 [{}] 暂无法调整尺寸到 {}x{}（最小化/超屏等），本次未保存",
                        window.getTitle(), targetW, targetH);
                return ManualShotResult.of("resize-fail");
            }
            sleep(RESIZE_SETTLE_MS);
            WindowInfo fresh = windowFinder.findTarget(properties.getWindowKeywords());
            if (fresh != null) {
                window = fresh;
            }
        }
        log.warn("手动采集：窗口 [{}] 多次强制调窗后截图尺寸仍不达标（目标 {}x{}），本次未保存",
                window.getTitle(), targetW, targetH);
        return ManualShotResult.of("resize-fail");
    }

    /** 手动采集结果：kind = saved（已存 capture/）/ dup（与 name 这张已存图差异 diffPercent% ≤ threshold% 被拦截，
     *  refState 为其所属分类，capture/ 未标注图为 null）/ window-not-found / minimized / capture-fail /
     *  resize-fail / save-fail / busy / error。 */
    public static final class ManualShotResult {

        public final String kind;
        public final String name;
        public final double diffPercent;
        public final String refState;
        public final double threshold;
        /** saved 时 = 本次扫描中与任一已有图的最小不一致像素占比（提示用）；-1 = 无可比参考（如首张图） */
        public final double minDiffPercent;

        ManualShotResult(String kind, String name, double diffPercent, String refState, double threshold) {
            this(kind, name, diffPercent, refState, threshold, -1);
        }

        ManualShotResult(String kind, String name, double diffPercent, String refState, double threshold,
                double minDiffPercent) {
            this.kind = kind;
            this.name = name;
            this.diffPercent = diffPercent;
            this.refState = refState;
            this.threshold = threshold;
            this.minDiffPercent = minDiffPercent;
        }

        public static ManualShotResult of(String kind) {
            return new ManualShotResult(kind, "", 0, null, 0);
        }
    }

    /**
     * 快速判死：强制缩放后尺寸毫无变化，累计到 {@link #SIZE_NO_CHANGE_AUTO_STOP_TIMES} 次
     * 即判定窗口无法被调整，立即自动暂停（不等满 {@link #SIZE_STUCK_AUTO_STOP_ROUNDS} 轮）。
     */
    private synchronized void stopByUnresizable(WindowInfo window, int imageW, int imageH, int targetW, int targetH) {
        paused.set(true);
        // 文案用 \n 分段：前端弹窗按换行符多行展示
        autoStopReason = String.format(
                "截图任务已自动暂停：窗口 [%s] 连续 %d 次被强制调整尺寸后，截图仍为 %dx%d，\n"
                        + "窗口大小没有任何变化，已判定该窗口无法被程序调整尺寸。\n"
                        + "\n请检查：\n"
                        + "1) 窗口是否被系统/目标程序锁定了大小（固定尺寸、最大化或最小化中，或未完整显示在屏幕内）；\n"
                        + "2) 目标程序是否支持被缩放——部分模拟器需在设置里把分辨率和方向设为 %dx%d 横屏。\n"
                        + "\n处理完成后，点击右上角「自动采集」即可重新开始。",
                window.getTitle(), SIZE_NO_CHANGE_AUTO_STOP_TIMES, imageW, imageH,
                targetW, targetH);
        log.error("截图因「调整后尺寸毫无变化」而自动暂停：{}", autoStopReason);
    }

    /**
     * 兜底：resize 无法执行（最小化 / 超屏）或窗口在变却迟迟不达标，累计
     * {@link #SIZE_STUCK_AUTO_STOP_ROUNDS} 轮即判定「持续调整不成功」并自动暂停。
     */
    private synchronized void onSizeStuck(WindowInfo window, int targetW, int targetH) {
        if (sizeStuckRounds.incrementAndGet() < SIZE_STUCK_AUTO_STOP_ROUNDS) {
            return;
        }
        paused.set(true);
        autoStopReason = String.format(
                "截图任务已自动暂停：窗口 [%s] 连续 %d 轮都无法把截图调整到目标尺寸 %dx%d，\n"
                        + "判定「窗口尺寸调整持续不成功」，截图任务停止。\n"
                        + "\n请检查：\n"
                        + "1) 目标窗口是否被锁定最小/最大尺寸，且完整显示在屏幕内；\n"
                        + "2) 目标程序的显示分辨率/方向是否已配置为 %dx%d 横屏\n"
                        + "（如 MuMu 模拟器需在设置里把分辨率和方向设为 %dx%d）。\n"
                        + "\n处理完成后，点击右上角「自动采集」即可重新开始。",
                window.getTitle(), SIZE_STUCK_AUTO_STOP_ROUNDS, targetW, targetH,
                targetW, targetH, targetW, targetH);
        log.error("截图因尺寸持续无法达标而自动暂停：{}", autoStopReason);
    }

    @SneakyThrows
    private void save(BufferedImage image, WindowInfo window) {
        Path file = screenCaptureService.savePng(image, window);
        savedSeq++;   // 落盘成功即递增，前端轮询到变化立刻刷新列表
        recordShotResult("saved", file.getFileName().toString(), 0, null, 0);
        log.info("已截图并保存（{}x{}）: {}", image.getWidth(), image.getHeight(), file);
    }

    /** 记录一次截图结果（saved / dup）：写入完整历史，并作为「最近一次」供轮询即时提示 */
    private void recordShotResult(String kind, String name, double diffPercent, String refState, double threshold) {
        long seq = shotSeqGen.incrementAndGet();
        ShotNotice n = new ShotNotice(seq, System.currentTimeMillis(), kind, name, diffPercent, refState, threshold);
        synchronized (shotHistory) {
            shotHistory.add(n);
        }
        shotNotice = n;
    }

    /** 累计被丢弃的相似帧，并按 {@link #SKIP_LOG_INTERVAL} 节流打印汇总日志 */
    private void logSkippedSimilar() {
        skippedSinceLog++;
        long now = System.currentTimeMillis();
        if (now < nextSkipLogTime) {
            return;
        }
        nextSkipLogTime = now + SKIP_LOG_INTERVAL;
        log.info("画面与去重基准（capture/ + classify/ 全部 PNG）中某张的不一致像素点占比 ≤ {}%（须与每一张都 > 阈值才保存），本轮不保存；"
                        + "近 {} 秒内已丢弃 {} 张几乎重复的截图（自动截图去重阈值可用启动参数覆盖，"
                        + "如 --capture.diff-threshold-percent=10）",
                properties.getDiffThresholdPercent(),
                SKIP_LOG_INTERVAL / 1000, skippedSinceLog);
        skippedSinceLog = 0;
    }

    /** 截图尺寸长期不达标的降频告警（含可操作建议） */
    private void logSizeStuck(WindowInfo window, int imageW, int imageH, int targetW, int targetH) {
        long now = System.currentTimeMillis();
        if (now < nextSizeFailLogTime) {
            return;
        }
        nextSizeFailLogTime = now + SIZE_FAIL_LOG_INTERVAL;
        String detail = imageW > 0 && imageH > 0
                ? String.format("当前截图 %dx%d，目标 %dx%d", imageW, imageH, targetW, targetH)
                : String.format("目标 %dx%d", targetW, targetH);
        log.warn("窗口 [{}] {}，但自动调整后截图仍未达标：若反复调整后尺寸毫无变化（连续 {} 次）会立即自动暂停；"
                        + "尺寸在变化却始终不达标（连续 {} 轮）也会自动暂停并弹出提示。请检查：1) 窗口未被锁定最小/最大尺寸，"
                        + "且完整显示在屏幕内；2) 目标程序的显示分辨率/方向已配置为 %dx%d（如 MuMu 模拟器需在设置里把分辨率和方向设为 "
                        + "1280x720 横屏，否则即使截图尺寸合规，画面也可能带黑边/变形）",
                window.getTitle(), detail,
                SIZE_NO_CHANGE_AUTO_STOP_TIMES, SIZE_STUCK_AUTO_STOP_ROUNDS, targetW, targetH);
    }

    private void logFindFailure() {
        long now = System.currentTimeMillis();
        if (now >= nextFindFailLogTime) {
            nextFindFailLogTime = now + FIND_FAIL_LOG_INTERVAL;
            log.warn("未找到标题匹配的窗口（关键字: {}），模拟器是否已打开？",
                    properties.getWindowKeywords());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一次截图结果的 UI 通知：{@code kind} = saved（{@code name} 为新截图文件名）/ dup（差异
     *  {@code diffPercent}% ≤ {@code threshold}% 被丢弃，{@code name} 为重复的参考图文件名）；
     *  {@code seq} 与 {@code at}（毫秒时间戳）均单调递增，前端按 seq 增量取历史、按 at 去重 */
    public static final class ShotNotice {

        public final long seq;
        public final long at;
        public final String kind;
        public final String name;
        public final double diffPercent;
        /** dup：参考图所属分类（classify/ 已标注样本），capture/ 未标注参考图或 saved 为 null */
        public final String refState;
        /** dup：本次判重阈值（%），saved 为 0 */
        public final double threshold;

        ShotNotice(long seq, long at, String kind, String name, double diffPercent, String refState, double threshold) {
            this.seq = seq;
            this.at = at;
            this.kind = kind;
            this.name = name;
            this.diffPercent = diffPercent;
            this.refState = refState;
            this.threshold = threshold;
        }
    }

}
