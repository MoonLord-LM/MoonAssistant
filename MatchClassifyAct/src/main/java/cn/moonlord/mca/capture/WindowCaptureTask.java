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
 * 截图任务：截图默认不开启，需在控制台网页点击「自动采集」后才开始后台截取目标窗口像素并保存 PNG。
 *
 * <p>截图节拍不做「固定 3 秒一拍」：每处理完一帧（抓帧 → 尺寸校验/调窗 → 与去重基准全库
 * 匹配比对 → 保存或判重丢弃）后再等 {@code capture.interval-ms}（默认 1 秒）取下一帧
 * （Spring fixedDelay 语义）——帧处理耗时多少就顺延多少，绝不因固定节拍而与上一帧的匹配
 * 比对并发或排队积压；上一帧的匹配比对没完成，下一帧就继续顺延等待。
 *
 * <p>截图基于 Windows Graphics Capture，全程不切前台、不置顶，
 * 目标窗口在后台/被遮挡时也能正常抓到它自身的画面。</p>
 *
 * <p>尺寸强校验：当 {@code capture.resize-width}/{@code capture.resize-height} 都 &gt;0
 * （如 1280x720）时，<b>只有截出来恰好是该尺寸的 PNG 才会被保存</b>。截图一旦发现
 * 尺寸不符，本任务就用 {@link WindowResizer#resizeWindowToPngSize} 把窗口强制缩放并
 * 在当轮内重截验证；仍不达标则下一轮继续调整，直到截图尺寸合规为止。</p>
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

    /** 单轮内“截图 → 调窗 → 重截”的最多轮数（含首次），避免一帧反复调窗无限占用资源 */
    private static final int MAX_VERIFY_ATTEMPTS = 3;

    /** 找不到窗口时，至少间隔这么久才再次告警，避免日志刷屏 */
    private static final long FIND_FAIL_LOG_INTERVAL = 30 * 1000L;

    /** 持续无法让截图达到目标尺寸时，至少间隔这么久才再次告警 */
    private static final long SIZE_FAIL_LOG_INTERVAL = 30 * 1000L;

    /** 相似帧（画面差异低于去重阈值被丢弃）的汇总日志最小间隔，避免画面静止时每轮刷屏 */
    private static final long SKIP_LOG_INTERVAL = 30 * 1000L;

    /** “执行了强制缩放、但重截发现截图尺寸与调整前完全一样（窗口没被 resize 改变）”的连续次数：
     *  达到该次数即判定窗口无法被调整尺寸，立即自动暂停（每轮内最多出现 2 次验证，故约 1~2 轮内即可触发，无需等满多轮） */
    private static final int SIZE_NO_CHANGE_AUTO_STOP_TIMES = 3;

    /** 兜底阈值：窗口在变化但迟迟不达标 / resize 因最小化、超屏等无法执行时，按“轮”累计达到该轮数才自动暂停
     *  （每轮时长 = 单帧处理耗时 + capture.interval-ms，不固定，故轮数阈值只能近似“时长”） */
    private static final int SIZE_STUCK_AUTO_STOP_ROUNDS = 10;

    /** 截图任务是否未开启/已暂停。初始为 {@code true}：程序启动后不自动截图，
     *  由控制台网页「自动采集」按钮（{@code /api/capture/resume}）手动开启。 */
    private final AtomicBoolean paused = new AtomicBoolean(true);

    /** 防重入：手动首截线程与定时调度可能同时触发，只允许一个真正执行 */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private long nextFindFailLogTime = 0;
    private long nextSizeFailLogTime = 0;

    /** 距上次汇总日志以来，因画面与已保存参考图太像而被丢弃的帧数 */
    private long skippedSinceLog = 0;

    /** 下一轮允许打印相似帧丢弃汇总日志的时间点 */
    private long nextSkipLogTime = 0;

    /** 截图尺寸连续未达标的轮数（仅在截图线程递增；resume 时清零；自增与清零跨线程原子，防 resume 与截图线程交错丢计数） */
    private final AtomicInteger sizeStuckRounds = new AtomicInteger();

    /** “强制缩放被执行但重截尺寸毫无变化（窗口没被调整动）”的连续次数；resume / 尺寸有变化 / 尺寸达标时清零 */
    private final AtomicInteger noChangeResizes = new AtomicInteger();

    /** 最近一次自动暂停原因（resize 持续无法达标）。非空时前端 /api/app/meta 轮询会收到并弹窗提示；
     *  用户手动 resume（{@code setPaused(false)}）时清除，便于下次失败再次提示。 */
    private volatile String autoStopReason = null;

    /** 最近一次截图「本帧结果」：成功保存 或 画面与已保存参考截图差异过小被丢弃。
     *  每轮完成都更新、不节流；前端每 2s 轮询 /api/app/meta 取走（截图节拍可为每帧完成后约 1s，
     *  轮询间隙内连续产生的多条中间结果会被最新一条覆盖），以右下角轻提示即时展示。
     *  {@code at}（毫秒时间戳）单调递增供前端去重。 */
    private volatile ShotNotice shotNotice = null;

    /** 截图结果全局序号（从 1 起单调递增）：每条历史记录一个 seq，前端按 seq 增量拉取补齐——
     *  轮询间隙被节流掉的中间结果也不丢（历史日志回溯）。 */
    private final AtomicLong shotSeqGen = new AtomicLong();

    /** 每轮截图结果的完整历史（含被前端 2s 轮询节流覆盖的中间条），按发生顺序追加在末尾；
     *  内存保留、随服务重启清空，供「历史日志」回填与增量拉取。访问需在 {@code shotHistory} 上同步。 */
    private final List<ShotNotice> shotHistory = new ArrayList<>();

    /** 成功保存截图的总次数（单调递增）：前端 /api/app/meta 轮询看到它变化 = 刚有新截图落盘，立即静默刷新列表 */
    private volatile long savedSeq = 0;

    /** 当前已使用的最大截图结果 seq（服务重启归零重计）：meta 输出给前端识别“后端已重启”，据此重置增量基线后重新全量 */
    public long getShotSeq() {
        return shotSeqGen.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    public String getAutoStopReason() {
        return autoStopReason;
    }

    /** 供 /api/app/meta 读取最近一次「截图结果」；尚未完成任何一轮（保存或丢弃）时为 null */
    public ShotNotice getShotNotice() {
        return shotNotice;
    }

    /** 供 /api/app/meta 增量拉取截图结果历史：返回 {@code seq > afterSeq} 的全部记录（快照）。
     *  afterSeq = -1 表示从第一条开始全量返回；尚无任何记录时返回空列表。 */
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

    /** 手动暂停/开启与截图线程自动暂停、计数增减互斥，避免「开启清零」与「自动停置位/计数」交错导致状态错乱 */
    public synchronized void setPaused(boolean p) {
        paused.set(p);
        if (p) {
            log.info("截图任务已暂停：不再保存新截图（控制台仍可用）");
            return;
        }
        // 重新开启：清零连续失败计数与自动暂停原因（原因仅保留到下次手动开启为止）
        sizeStuckRounds.set(0);
        noChangeResizes.set(0);
        autoStopReason = null;
        log.info("截图任务已开启：每处理完一帧（截图 + 匹配比对）后等 {} ms 再取下一帧，"
                + "尺寸校验 = {}x{}", properties.getIntervalMs(),
                properties.getResizeWidth(), properties.getResizeHeight());
        // 异步立即执行一轮，让点按钮后尽快出图；若与定时轮撞车则交给定时轮
        Thread first = new Thread(this::tick, "capture-first-shot");
        first.setDaemon(true);
        first.start();
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("截图任务默认未开启：启动时不会自动截图。请在弹出的控制台网页点击「自动采集」手动开始；"
                + "若网页未自动打开，可手动访问 http://127.0.0.1:8080/annotate");
    }

    /**
     * 截图调度入口：Spring fixedDelay = 距上一轮<b>处理完成</b>后再等 {@code capture.interval-ms}
     * （默认 1000ms）取下一帧。帧内「抓帧 + 匹配比对 + 保存/丢弃」同步完成，比对耗时多长，
     * 下一帧就自然顺延多久——上一帧的匹配比对没完成，不会开始下一帧（绝不叠帧并发）。
     */
    @Scheduled(initialDelayString = "${capture.interval-ms:1000}",
            fixedDelayString = "${capture.interval-ms:1000}")
    public void captureTick() {
        tick();
    }

    /**
     * 单轮截图 = 截图 → 校验尺寸 →（不达标：强制调整窗口 → 重截）→ 达标才保存 PNG。
     * 开启截图（setPaused=false）时立即异步调用一次；之后由 fixedDelay 调度：每轮处理完成
     * 后再等 interval 取下一帧（单帧抓帧/比对超时时自动顺延，不与上一帧并发）。
     * WGC 方案无需把窗口切到前台或置顶。
     */
    private void tick() {
        if (paused.get()) {
            return;   // 未开启/暂停期间直接跳过，不查找窗口也不占资源
        }
        if (!busy.compareAndSet(false, true)) {
            return;   // 上一轮还在跑（如手动首截撞上定时轮）：跳过本轮
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

        int prevW = 0, prevH = 0;   // 最近一次强制缩放前截到的尺寸；用它判断缩放后窗口是否真的动了
        for (int attempt = 1; attempt <= MAX_VERIFY_ATTEMPTS; attempt++) {
            BufferedImage image = screenCaptureService.captureWindow(window);
            if (image == null) {
                return;   // 窗口不可捕获/超时：交给下一轮调度重试
            }
            int imageW = image.getWidth();
            int imageH = image.getHeight();

            // 本帧是上一次强制缩放后的重截验证帧：尺寸若仍与缩放前一模一样，说明 SetWindowPos 没能让窗口产生任何变化
            if (prevW > 0 && imageW == prevW && imageH == prevH) {
                if (noChangeResizes.incrementAndGet() >= SIZE_NO_CHANGE_AUTO_STOP_TIMES) {
                    // 连续 N 次“调了但窗口毫无变化”：直接判定无法调整，立即自动暂停（不必再等后续轮次）
                    stopByUnresizable(window, imageW, imageH, targetW, targetH);
                    return;
                }
                log.warn("窗口 [{}] 强制缩放后截图尺寸仍是 {}x{}，窗口未随调整变化（已连续 {}/{} 次无变化）",
                        window.getTitle(), imageW, imageH,
                        noChangeResizes.get(), SIZE_NO_CHANGE_AUTO_STOP_TIMES);
            } else if (prevW > 0) {
                noChangeResizes.set(0);   // 尺寸有变化 = resize 生效：清零“无变化”计数，继续按实测尺寸迭代
            }

            if (!enforce || (imageW == targetW && imageH == targetH)) {
                if (paused.get()) {
                    return;   // 截图期间用户点了暂停：放弃这帧
                }
                ScreenCaptureService.DuplicateMatch dup = screenCaptureService.duplicateReference(image);
                if (dup != null) {
                    // 画面与某张已保存图的不一致像素点占比 ≤ 阈值：视为重复帧，丢弃不保存
                    logSkippedSimilar();
                    recordShotResult("dup", dup.name(), dup.diffPercent(),
                            dup.refState(), dup.threshold());   // 右下角提示「与哪张参考图重复、未保存」（附分类与阈值）
                } else {
                    save(image, window);
                }
                sizeStuckRounds.set(0);   // 尺寸达标并已保存：清零连续失败计数
                noChangeResizes.set(0);
                return;
            }

            // 尺寸不符：丢弃这帧（不保存），强制调整窗口后当轮内重截
            log.warn("第 {}/{} 次截图尺寸为 {}x{}，不符合目标 {}x{}，已丢弃该帧并强制调整窗口",
                    attempt, MAX_VERIFY_ATTEMPTS, imageW, imageH, targetW, targetH);
            if (attempt == MAX_VERIFY_ATTEMPTS) {
                break;
            }

            boolean adjusted = windowResizer.resizeWindowToPngSize(
                    window.getHwnd(), window.getTitle(), imageW, imageH, targetW, targetH);
            if (!adjusted) {
                // 窗口最小化/目标超屏等暂不可调：本帧不保存，交给下一轮再试
                logSizeStuck(window, imageW, imageH, targetW, targetH);
                onSizeStuck(window, targetW, targetH);
                return;
            }
            // 记下调整前的尺寸：重截后若仍是它，说明这次强制缩放没有让窗口产生任何变化
            prevW = imageW;
            prevH = imageH;
            sleep(RESIZE_SETTLE_MS);

            // 刷新窗口信息（几何/最小化状态可能在缩放后变化）
            WindowInfo fresh = windowFinder.findTarget(properties.getWindowKeywords());
            if (fresh != null) {
                window = fresh;
            }
        }

        // 单轮内多次尝试仍未达标：不保存，交给下一轮再调
        logSizeStuck(window, -1, -1, targetW, targetH);
        onSizeStuck(window, targetW, targetH);
    }

    /**
     * 手动采集一次（标注模式「未标注」空列表的「手动采集」按钮 → {@code /api/capture/manual}）：
     * 立即做与自动截图「一轮」等价的处理——截图 → 尺寸校验（不符则强制调窗重截，最多
     * {@link #MAX_VERIFY_ATTEMPTS} 次）→ 去重检查 → 达标才存 capture/。与自动轮仅两处差异：
     * 去重套「手动保存」阈值（{@code capture.diff-threshold-manual-percent}，默认 0.5%，与执行模式
     * 「存到待标注」同一判定方法同一口径：与 capture/ + classify/ 全部同尺寸 PNG 逐像素比对，须与
     * 每一张的不一致像素占比都 &gt; 阈值才算新画面）；不触发自动暂停、不写右下角截图事件流
     * （由页面按钮按返回结果自行提示与刷新列表）。单次执行不受截图开关（paused）约束；
     * 与定时轮经 {@link #busy} 互斥，撞车时先等至多约 2 秒再试。
     *
     * @return 本次结果（{@link ManualShotResult}）
     */
    @SneakyThrows
    public ManualShotResult manualShot() {
        for (int i = 0; i < 20 && busy.get(); i++) {
            sleep(100);   // 定时轮/首截线程仍在处理：稍等其完成，避免两路截图同时抓帧互相干扰
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

    /** 手动采集实际执行体：结构与 {@link #doTick()} 一致（截图 → 尺寸不达标调窗重截 → 达标才保存），
     *  差异（手动阈值 / 不暂停 / 不计数 / 逐失败点返回结果）见 {@link #manualShot()} */
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
        // 与去重判定同口径：阈值先四舍五入到两位小数（默认 0.5，同执行模式「存到待标注」）
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
                // 去重判定与「提示用差异」一次扫描带出（minDiffPercent 取自本次扫描且落盘前算，故不含本张）
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

    /** 手动采集结果：kind = saved（已存 capture/，name 为文件名，minDiffPercent 为去重扫描中与任一已有图的
     *  最小不一致像素占比、-1 无可比参考）/ dup（与 name 这张已存图的不一致像素占比 diffPercent% ≤ 阈值 threshold% 被拦截，
     *  refState 为该图所属分类，capture/ 图为 null）/ window-not-found / minimized / capture-fail /
     *  resize-fail / save-fail / busy / error。 */
    public static final class ManualShotResult {

        public final String kind;
        public final String name;
        public final double diffPercent;
        public final String refState;
        public final double threshold;
        /** saved 时 = 本次去重扫描中与任一已有图的最小不一致像素占比（提示用）；-1 = 无可比参考（首张图等） */
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
     * 快速判死：强制缩放已执行但重截尺寸毫无变化，连续累计到 {@link #SIZE_NO_CHANGE_AUTO_STOP_TIMES} 次
     * 即判定窗口无法被调整，立即自动暂停（不等满 {@link #SIZE_STUCK_AUTO_STOP_ROUNDS} 轮）。
     * 暂停原因记录后，前端 /api/app/meta 轮询到即弹窗提示。
     */
    private synchronized void stopByUnresizable(WindowInfo window, int imageW, int imageH, int targetW, int targetH) {
        paused.set(true);   // 停止截图（暂停保存，需用户手动重新开启）
        // 文案用 \n 分段：前端弹窗按换行符多行展示，方便阅读
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
     * 兜底路径：resize 因最小化/超屏等无法执行（按“轮”累计），或窗口在变化但迟迟不达标，
     * 累计达到 {@link #SIZE_STUCK_AUTO_STOP_ROUNDS} 轮仍未达标，判定为“持续调整不成功”，
     * 自动暂停截图并记录原因（前端 /api/app/meta 轮询到后弹窗提示）。
     */
    private synchronized void onSizeStuck(WindowInfo window, int targetW, int targetH) {
        if (sizeStuckRounds.incrementAndGet() < SIZE_STUCK_AUTO_STOP_ROUNDS) {
            return;
        }
        paused.set(true);   // 停止截图（暂停保存，需用户手动重新开启）
        // 文案用 \n 分段：前端弹窗按换行符多行展示，方便阅读
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
        savedSeq++;   // 落盘成功：seq 递增，前端轮询到变化即立刻刷新列表（无需等 10s 后台轮询）
        recordShotResult("saved", file.getFileName().toString(), 0, null, 0);   // 右下角即时提示「已保存 xx」
        log.info("已截图并保存（{}x{}）: {}", image.getWidth(), image.getHeight(), file);
    }

    /** 记录一次已完成的截图结果（saved=成功保存 / dup=与参考图不一致像素点占比 ≤ 阈值被丢弃）：
     *  写入完整历史供「历史日志」回溯，并作为“最近一次”供 /api/app/meta 轮询即时提示 */
    private void recordShotResult(String kind, String name, double diffPercent, String refState, double threshold) {
        long seq = shotSeqGen.incrementAndGet();
        ShotNotice n = new ShotNotice(seq, System.currentTimeMillis(), kind, name, diffPercent, refState, threshold);
        synchronized (shotHistory) {
            shotHistory.add(n);
        }
        shotNotice = n;
    }

    /** 统计相似帧丢弃次数，并按 {@link #SKIP_LOG_INTERVAL} 节流打印汇总日志（不逐帧刷屏） */
    private void logSkippedSimilar() {
        skippedSinceLog++;
        long now = System.currentTimeMillis();
        if (now < nextSkipLogTime) {
            return;
        }
        nextSkipLogTime = now + SKIP_LOG_INTERVAL;
        log.info("画面与去重基准（capture/ + classify/ 全部 PNG）中某张的不一致像素点占比 ≤ {}%（须与每一张都 > 阈值才保存），本轮不保存；"
                        + "近 {} 秒内已丢弃 {} 张几乎重复的截图（自动截图去重阈值默认 5%，可用启动参数覆盖，"
                        + "如 --capture.diff-threshold-percent=10）",
                properties.getDiffThresholdPercent(),
                SKIP_LOG_INTERVAL / 1000, skippedSinceLog);
        skippedSinceLog = 0;
    }

    /** 截图尺寸长期无法达标时降频告警（每 30 秒最多一条），并给出可操作建议 */
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

    /** 一次截图结果的 UI 通知：
     *  {@code kind} = {@code "saved"}（成功保存，{@code name} 为新截图文件名）或
     *  {@code "dup"}（与已保存参考截图的不一致像素点占比 ≤ 阈值被丢弃，{@code name} 为重复的参考图文件名，
     *  {@code diffPercent} 为实际占比）；
     *  {@code seq}（从 1 起全局递增）与 {@code at}（毫秒时间戳）都单调递增，前端按 seq 增量取历史、按 at 判断新结果 */
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
