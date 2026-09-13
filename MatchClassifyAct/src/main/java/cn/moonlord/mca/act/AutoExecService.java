package cn.moonlord.mca.act;

import cn.moonlord.mca.mark.CaptureMark;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行模式的自动识别循环：整轮「截图识别 → 留观察时间 → 按所选方式点击 → 留游戏响应时间」全部在后端线程里跑，
 * 控制台页只按固定间隔轮询 {@link #status()} 取状态与最新画面做展示。
 *
 * <p><b>为什么放在后端</b>：页面最小化 / 切到别的窗口后，浏览器会节流隐藏页的定时器（先按秒对齐，随后降到分钟级，
 * 更久还会整页冻结），页面里的循环会因此停摆或慢到看不出在跑；后端线程不受此影响，
 * 于是页面降级为纯观测窗口 —— 它最小化、切走、甚至关掉都不影响循环继续执行，
 * 需要停止时由页面调 {@link #stop()}，或随进程退出（本线程是 daemon）而结束。</p>
 *
 * <p><b>口径不重复实现</b>：每轮只调一次 {@link ExecutionService#refreshNow()} 与 {@link ExecutionService#act()}，
 * 与页面「立即识别 / 执行动作」完全同一套代码，故点击方式、截图对齐、去重与错误文案都只有一份。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoExecService {

    /** 阶段：正在截图识别本轮（页面显示「正在截图识别」）。 */
    public static final String PHASE_SCAN = "scan";
    /** 阶段：本轮没有可执行动作，倒计时后进下一轮（原因见 {@link State#why()}）。 */
    public static final String PHASE_WAIT_NEXT = "waitNext";
    /** 阶段：已识别到点击动作，倒计时后执行点击（坐标见 {@link State#left()} / {@link State#top()}）。 */
    public static final String PHASE_WAIT_CLICK = "waitClick";
    /** 阶段：正在执行点击。 */
    public static final String PHASE_CLICKING = "clicking";
    /** 阶段：点击已发出（或未能发出），倒计时后进下一轮。 */
    public static final String PHASE_CLICKED = "clicked";
    /** 阶段：已停止（页面据 {@link State#stopAt()} 只提示一次「已停止自动识别」）。 */
    public static final String PHASE_STOPPED = "stopped";
    /** 阶段：本进程从未跑过循环（页面不动状态行）。 */
    public static final String PHASE_IDLE = "idle";

    /** 阶段之间的等待时长（毫秒）：识别后留观察 / 确认时间，点击后留游戏响应时间。 */
    private static final long STEP_WAIT_MS = 3000;
    /** 一秒（毫秒）：等待按它切片推进 {@link State#secs()}。 */
    private static final long SECOND_MS = 1000;
    /** 等待时的检查粒度（毫秒）：停止请求最多延迟一个分片生效。 */
    private static final long WAIT_SLICE_MS = 100;

    /** 循环开关：页面 stop 时置 false，循环在下一个检查点收手退出。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最近状态（页面每轮轮询读它）。 */
    private volatile State state = State.idle();

    /** 执行服务：循环内的「截图识别一轮」与「按最近结果点击一次」都走它，与页面按钮同一套实现。 */
    private final ExecutionService executionService;

    /* ================================================================ 阶段状态 ========= */

    /**
     * 循环状态（页面按自己的轮询间隔读它来拼状态行与按钮态；秒数由后端按 {@link #STEP_WAIT_MS} 逐秒推进，
     * 页面不做任何本地倒计时，故页面被节流也不影响展示口径）。
     *
     * @param on      循环是否在跑
     * @param phase   当前阶段（见本类的 PHASE_* 常量）
     * @param round   已进入的轮次（0 = 还没开始）
     * @param secs    当前阶段剩余秒数（非倒计时阶段为 0）
     * @param why     {@link #PHASE_WAIT_NEXT} 的原因（直接展示给用户：未识别 / 无需动作 / 分类缺坐标 / 错误文案）
     * @param left    {@link #PHASE_WAIT_CLICK} 即将点击的横坐标
     * @param top     {@link #PHASE_WAIT_CLICK} 即将点击的纵坐标
     * @param mode    本轮点击方式（页面换算成中文名展示）
     * @param clickOk {@link #PHASE_CLICKED} 时本次点击是否成功
     * @param message {@link #PHASE_CLICKED} 且失败时的原因
     * @param stopAt  停止时刻（毫秒；0 = 没停止过）—— 页面据此只展示一次「已停止自动识别」
     */
    public record State(
            boolean on,
            String phase,
            int round,
            int secs,
            String why,
            Integer left,
            Integer top,
            String mode,
            Boolean clickOk,
            String message,
            long stopAt
    ) {

        /** 本进程从未跑过循环。 */
        static State idle() {
            return new State(false, PHASE_IDLE, 0, 0, null, null, null, null, null, null, 0);
        }

        /** 一个倒计时阶段的原型（秒数由 {@link #withSecs(int)} 逐秒填）。 */
        static State of(String phase, int round, String why, Integer left, Integer top,
                        String mode, Boolean clickOk, String message) {
            return new State(true, phase, round, 0, why, left, top, mode, clickOk, message, 0);
        }

        State withSecs(int secs) {
            return new State(on, phase, round, secs, why, left, top, mode, clickOk, message, stopAt);
        }
    }

    /* ================================================================ 对外控制 ========= */

    /** 最近状态（页面轮询 `/api/execute/poll` 时读它）。 */
    public State status() {
        return state;
    }

    /** 开启循环（已在跑则原样返回当前状态）。启动立即返回，整轮在后端线程里跑。 */
    public synchronized State start() {
        if (running.get()) {
            return state;
        }
        running.set(true);
        state = State.of(PHASE_SCAN, 1, null, null, null, executionService.getClickMode(), null, null);
        Thread t = new Thread(this::loop, "mca-exec-auto");
        t.setDaemon(true);
        t.start();
        log.info("执行模式自动识别：后端循环已开启（页面只轮询展示，最小化 / 切走都不影响）");
        return state;
    }

    /** 停止循环（没在跑则原样返回当前状态，不重复刷「已停止」提示）。 */
    public synchronized State stop() {
        if (!running.getAndSet(false)) {
            return state;
        }
        state = new State(false, PHASE_STOPPED, state.round(), 0, null, null, null,
                state.mode(), null, null, System.currentTimeMillis());
        log.info("执行模式自动识别：已请求停止（停在第 {} 轮）", state.round());
        return state;
    }

    /* ================================================================ 循环本体 ========= */

    private void loop() {
        int round = 0;
        try {
            while (running.get()) {
                round++;
                // 1) 截图 + 识别一轮：后端直接调服务（不经过 HTTP），返回即「识别完成」
                state = State.of(PHASE_SCAN, round, null, null, null, executionService.getClickMode(), null, null);
                ExecutionService.Snapshot snap = executionService.refreshNow();
                if (!running.get()) {
                    break;
                }
                String why = noActionReason(snap);
                if (why != null) {
                    // 2a) 本轮没有可执行动作：确认时间后直接下一轮（没有动作也就没有「游戏响应」等待）
                    if (!wait(State.of(PHASE_WAIT_NEXT, round, why, null, null,
                            executionService.getClickMode(), null, null))) {
                        break;
                    }
                    continue;
                }
                // 2b) 已识别到可点击动作：留确认时间（期间页面可看画面与结果，也可随时停止）
                if (!wait(State.of(PHASE_WAIT_CLICK, round, null, snap.left(), snap.top(),
                        executionService.getClickMode(), null, null))) {
                    break;
                }
                // 3) 按页面所选点击方式执行本轮已识别结果（不重复截图识别）
                String mode = executionService.getClickMode();
                state = State.of(PHASE_CLICKING, round, null, snap.left(), snap.top(), mode, null, null);
                Map<String, Object> r = executionService.act();
                if (!running.get()) {
                    break;
                }
                boolean ok = Boolean.TRUE.equals(r.get("ok"));
                Object used = r.get("mode");
                String usedMode = used instanceof String m && !m.isBlank() ? m : mode;
                // 4) 点击已发出：留游戏响应时间再进下一轮
                String msg = ok ? null : String.valueOf(r.get("message"));
                if (!wait(State.of(PHASE_CLICKED, round, null, null, null, usedMode, ok, msg))) {
                    break;
                }
            }
        } catch (Exception e) {
            // 单轮异常已由 ExecutionService 兜成错误快照（会走「原因」分支），这里只兜循环外壳本身
            log.warn("执行模式自动识别循环异常退出：{}", e.toString());
        } finally {
            if (running.getAndSet(false)) {
                // 循环自己退出（异常 / 停止请求落在循环外）也留一句「已停止」，让页面把按钮与锁定态复位
                state = new State(false, PHASE_STOPPED, state.round(), 0, null, null, null,
                        state.mode(), null, null, System.currentTimeMillis());
            }
        }
    }

    /**
     * 倒计时等待：逐秒把剩余秒数写进状态（页面直接展示，不做本地倒计时），期间被停止则返回 false。
     *
     * @param proto 待推进的阶段状态（秒数由本方法填）
     * @return true = 等待正常走完；false = 期间被停止，循环应立即退出
     */
    private boolean wait(State proto) {
        int total = (int) (STEP_WAIT_MS / SECOND_MS);
        for (int secs = total; secs >= 1; secs--) {
            if (!running.get()) {
                return false;
            }
            state = proto.withSecs(secs);
            if (!sleepSlices(SECOND_MS)) {
                return false;
            }
        }
        return running.get();
    }

    /** 分片睡眠：期间被停止则返回 false（停止请求最多延迟一个 {@link #WAIT_SLICE_MS} 生效）。 */
    private boolean sleepSlices(long ms) {
        for (long waited = 0; waited < ms; waited += WAIT_SLICE_MS) {
            if (!running.get()) {
                return false;
            }
            try {
                Thread.sleep(WAIT_SLICE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return running.get();
    }

    /* ================================================================ 判定 ============= */

    /** 本轮是否可执行点击（与页面右栏「识别结果」同一口径：识别成功 + 分类动作为鼠标点击 + 有坐标）。 */
    private static boolean isClickable(ExecutionService.Snapshot s) {
        return s != null && s.recognized() && CaptureMark.ACTION_CLICK.equals(s.action())
                && s.left() != null && s.top() != null;
    }

    /**
     * 本轮不可点击时的原因文案（文案在此统一给出，页面直接展示）。
     *
     * @return null = 可以点击；否则 = 进下一轮前展示的原因
     */
    private static String noActionReason(ExecutionService.Snapshot s) {
        if (isClickable(s)) {
            return null;
        }
        if (s == null || (s.imageWidth() <= 0 && s.error() != null)) {
            // 无画面：把后端给出的具体原因（未找到窗口 / 已最小化 / 截图失败 / 没有运行时算法…）如实转达
            return s == null ? "识别失败：没有取得本轮画面" : s.error();
        }
        if (s.state() != null) {
            return CaptureMark.ACTION_CLICK.equals(s.action()) ? "该分类尚无点击坐标" : "无需动作";
        }
        return "未识别出已标注分类（可能尚无同尺寸样本），不动作";
    }
}
