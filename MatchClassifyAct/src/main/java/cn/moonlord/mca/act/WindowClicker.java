package cn.moonlord.mca.act;

import cn.moonlord.mca.capture.WindowInfo;
import cn.moonlord.mca.config.ExecuteProperties;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 鼠标点击执行器：把「识别出的点击点」（图片像素 = 窗口相对坐标 Left/Top）转成一次真实的鼠标左键点击。
 *
 * <p>两种执行方式（由 execute.click-mode 控制，默认 {@code post}）：</p>
 * <ul>
 *   <li>{@code post}（默认）：后台消息。向目标窗口投递一条与真实鼠标路径一致的消息序列：先 3 次
 *       {@code WM_MOUSEMOVE} 模拟滑入轨迹、再 {@code WM_MOUSEACTIVATE} 声明点击意图（是否激活由窗口决定）、
 *       最后 {@code WM_LBUTTONDOWN}/{@code WM_LBUTTONUP}（客户区坐标 = 图片像素 − 标题栏 / 边框偏移）。
 *       不需要窗口在前台、不抢占用户鼠标，比只发「按下/抬起」更易被普通桌面程序接受；
 *       游戏 / 模拟器仍多数会忽略合成消息，此时切「前台点击」兜底；</li>
 *   <li>{@code screen}：前台点击。截图画面 = 窗口整窗外框（采集器按 GetWindowRect 裁取），
 *       因此用「窗口外框左上角 + 图片像素」得到屏幕坐标，再把窗口带到前台并用
 *       {@code SetCursorPos + mouse_event} 模拟一次真实左键点击。模拟器 / 游戏必须用此项。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WindowClicker {

    public static final String MODE_POST = "post";
    public static final String MODE_SCREEN = "screen";

    // Windows SDK 鼠标消息 / 事件常量（JNA 平台库未映射这些数值，直接按 SDK 定义）
    private static final int WM_MOUSEMOVE = 0x0200;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_LBUTTONUP = 0x0202;
    private static final int WM_MOUSEACTIVATE = 0x0021;
    private static final int MK_LBUTTON = 0x0001;
    private static final int HTCLIENT = 0x0001;   // 命中测试码：客户区（WM_MOUSEACTIVATE 的 LOWORD）
    private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    private static final int MOUSEEVENTF_LEFTUP = 0x0004;

    // GetAncestor 的检索标志：GA_ROOT = 返回指定窗口所属的顶层根窗口（自身已是顶层则返回自身）
    private static final int GA_ROOT = 2;
    // 前台切换轮询 GetForegroundWindow 的间隔（毫秒）
    private static final int FOREGROUND_POLL_MS = 40;
    // 前台抬窗失败后的重试节奏：每 300ms 再 SetForegroundWindow 一次（首次失效时偶有二次成功的窗口）
    private static final int FOREGROUND_RETRY_MS = 300;

    // keybd_event 虚拟键与标志：F22 为无副作用的保留键，用来让本进程取得 SetForegroundWindow 的调用许可；
    // KEYEVENTF_KEYUP = 0x0002
    private static final byte VK_F22 = (byte) 0x85;
    private static final int KEYEVENTF_KEYUP = 0x0002;

    // 后台消息序列的节奏（毫秒）与形态：移动插值消息的间隔、按下与抬起之间的间隔、滑入轨迹的移动次数
    private static final int POST_MOVE_STEP_MS = 30;
    private static final int POST_CLICK_GAP_MS = 60;
    private static final int POST_MOVE_STEPS = 3;

    /**
     * user32.dll 中本次执行需要用到的函数。JNA 平台库 {@code User32} 对其中个别函数
     * 的签名与本工程用法不适配（如 PostMessage 声明为返回 LRESULT、未内置 ClientToScreen），
     * 因此统一在此按 SDK 语义自声明：BOOL → boolean、消息参数用 WinDef 结构。
     */
    private interface User32Mouse extends StdCallLibrary {
        User32Mouse INSTANCE = Native.load("user32", User32Mouse.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean PostMessage(WinDef.HWND hwnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam);

        boolean ClientToScreen(WinDef.HWND hwnd, WinDef.POINT pt);

        boolean GetWindowRect(WinDef.HWND hwnd, WinDef.RECT rect);

        boolean SetForegroundWindow(WinDef.HWND hwnd);

        boolean SetCursorPos(int x, int y);

        void mouse_event(int dwFlags, int dx, int dy, int dwData, Pointer dwExtraInfo);

        WinDef.HWND GetForegroundWindow();

        WinDef.HWND GetAncestor(WinDef.HWND hwnd, int gaFlags);

        void keybd_event(byte bVk, byte bScan, int dwFlags, Pointer dwExtraInfo);

        int GetWindowThreadProcessId(WinDef.HWND hwnd, IntByReference pid);
    }

    /** 点击结果（screenX/Y 为换算后的屏幕坐标，仅作反馈展示；post 模式不依赖它们）。 */
    public record Result(boolean ok, String mode, String message,
                         int x, int y, int screenX, int screenY) {
    }

    /**
     * 在目标窗口的 (x, y)（图片像素 = 窗口相对坐标）处发送一次鼠标左键点击。
     *
     * @param window 目标窗口（需当前仍存在）
     * @param x      相对窗口内容左上角的 x
     * @param y      相对窗口内容左上角的 y
     * @param mode   {@link #MODE_POST} 或 {@link #MODE_SCREEN}
     */
    public Result click(WindowInfo window, int x, int y, String mode) {
        if (window == null || window.getHwnd() == null || window.getHwnd().getPointer() == null) {
            return new Result(false, mode, "目标窗口不存在或句柄已失效", x, y, -1, -1);
        }
        return MODE_SCREEN.equalsIgnoreCase(mode) ? screenClick(window, x, y) : postClick(window, x, y);
    }

    private Result postClick(WindowInfo window, int x, int y) {
        WinDef.HWND hwnd = window.getHwnd();
        // 窗口鼠标消息的 lParam 以「客户区」为基准：整窗截图含标题栏/边框时，先减去外框到客户区的偏移
        int cx = x, cy = y;
        WinDef.POINT outer = windowOuterOrigin(hwnd);
        if (outer != null) {
            WinDef.POINT client = windowClientOrigin(hwnd);
            if (client != null) {
                cx = Math.max(0, x - (client.x - outer.x));
                cy = Math.max(0, y - (client.y - outer.y));
            }
        }
        User32Mouse u = User32Mouse.INSTANCE;
        // 1) 滑入轨迹：从客户区左上角向目标点做 3 次 WM_MOUSEMOVE 插值。
        //    多数控件/自绘框架要先收到「光标进入」的移动消息建立 hover 状态，否则直接收到的按下会被忽略。
        for (int i = 1; i <= POST_MOVE_STEPS; i++) {
            int mx = (int) Math.round(cx * (double) i / POST_MOVE_STEPS);
            int my = (int) Math.round(cy * (double) i / POST_MOVE_STEPS);
            if (!u.PostMessage(hwnd, WM_MOUSEMOVE, new WinDef.WPARAM(0), new WinDef.LPARAM(mouseLParam(mx, my)))) {
                return sendFail(x, y);
            }
            sleep(POST_MOVE_STEP_MS);
        }
        // 2) 激活意图探测：声明「本次按下本应激活该窗口」，窗口内部决定是否激活
        //    （自绘控件若返回 MA_NOACTIVATE 即保持后台、不抢前台；无法强制其返回值）。
        long top = Pointer.nativeValue(hwnd.getPointer());   // wParam = 顶层窗口句柄值
        if (!u.PostMessage(hwnd, WM_MOUSEACTIVATE, new WinDef.WPARAM(top), new WinDef.LPARAM(activateLParam()))) {
            return sendFail(x, y);
        }
        sleep(POST_MOVE_STEP_MS);
        // 3) 左键按下 / 抬起（客户区坐标）
        if (!u.PostMessage(hwnd, WM_LBUTTONDOWN, new WinDef.WPARAM(MK_LBUTTON), new WinDef.LPARAM(mouseLParam(cx, cy)))) {
            return sendFail(x, y);
        }
        sleep(POST_CLICK_GAP_MS);
        if (!u.PostMessage(hwnd, WM_LBUTTONUP, new WinDef.WPARAM(0), new WinDef.LPARAM(mouseLParam(cx, cy)))) {
            return sendFail(x, y);
        }
        return new Result(true, MODE_POST,
                "已向窗口「" + window.getTitle() + "」后台投递完整点击消息序列（移动→按下→抬起）(" + x + ", " + y + ")",
                x, y, -1, -1);
    }

    private Result sendFail(int x, int y) {
        return new Result(false, MODE_POST, "后台消息序列发送失败（PostMessage 返回 false）", x, y, -1, -1);
    }

    /** 鼠标消息 lParam：HIWORD=y、LOWORD=x（均为客户区坐标，单字 16 位）。 */
    private static int mouseLParam(int x, int y) {
        return ((y & 0xffff) << 16) | (x & 0xffff);
    }

    /** WM_MOUSEACTIVATE 的 lParam：HIWORD=产生这次点击的鼠标消息、LOWORD=命中测试码（客户区）。 */
    private static int activateLParam() {
        return (WM_LBUTTONDOWN << 16) | HTCLIENT;
    }

    private final ExecuteProperties executeProperties;

    private Result screenClick(WindowInfo window, int x, int y) {
        WinDef.HWND hwnd = window.getHwnd();
        WinDef.POINT outer = windowOuterOrigin(hwnd);
        if (outer == null) {
            return new Result(false, MODE_SCREEN, "无法读取窗口屏幕位置（窗口可能已销毁）",
                    x, y, -1, -1);
        }
        // 截图像素原点 = 窗口外框左上角（采集器按 GetWindowRect 裁取整窗画面），屏幕坐标即「外框原点 + 图片像素」
        int sx = outer.x + x, sy = outer.y + y;
        User32Mouse m = User32Mouse.INSTANCE;
        // 前台切换不是同步生效：窗口要从前台线程切换到真正可接收输入的状态往往有明显延迟，
        // 且可能被系统前台锁定拒绝、被别的窗口抢占。这里不能“睡了就点”，而是先「抬窗 + 轮询
        // GetForegroundWindow」确认前台焦点已归属本窗口（按顶层根句柄核对），确认后再留一小段
        // 稳定时间让窗口完成激活处理；超时未就绪则取消点击，避免把真实鼠标输入发到错误的窗口上。
        if (!raiseAndAwaitForeground(hwnd, executeProperties.getForegroundWaitMs())) {
            return new Result(false, MODE_SCREEN,
                    "窗口「" + window.getTitle() + "」未能在 "
                            + (executeProperties.getForegroundWaitMs() / 1000.0)
                            + "s 内取得前台焦点（前台仍非本窗口，可能被其他窗口遮挡/抢占，或系统前台锁定未放行），"
                            + "已取消本次点击，避免误点。",
                    x, y, sx, sy);
        }
        sleep(executeProperties.getForegroundSettleMs());
        boolean moved = m.SetCursorPos(sx, sy);
        if (!moved) {
            return new Result(false, MODE_SCREEN, "SetCursorPos 失败（无法移动鼠标到目标屏幕点）",
                    x, y, sx, sy);
        }
        sleep(50);
        m.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, null);
        sleep(60);
        m.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, null);
        return new Result(true, MODE_SCREEN,
                "前台焦点已确认归「" + window.getTitle() + "」，已在屏幕坐标 (" + sx + ", " + sy + ") 模拟鼠标左键点击",
                x, y, sx, sy);
    }

    /**
     * 把窗口带到前台并等待确认：轮询 {@code GetForegroundWindow()}，直到前台窗口的顶层根窗口
     * 就是本窗口（与 {@code hwnd} 同根）。期间每隔 {@link #FOREGROUND_RETRY_MS} 用「F22 解锁 +
     * SetForegroundWindow」重试一次，弥补后台进程被系统前台锁定、或首次抬窗偶发被忽略的情况。
     *
     * @param waitMs 等待上限（毫秒）；若等待开始时前台已是本窗口，立即返回 true
     * @return 是否已确认前台焦点归本窗口
     */
    private boolean raiseAndAwaitForeground(WinDef.HWND hwnd, long waitMs) {
        User32Mouse u = User32Mouse.INSTANCE;
        WinDef.HWND targetRoot = topRoot(hwnd);
        if (targetRoot == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        long nextRaiseAt = 0;   // 首次立即抬窗
        while (true) {
            WinDef.HWND fg = u.GetForegroundWindow();
            if (fg != null && sameWindow(topRoot(fg), targetRoot)) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                IntByReference pid = new IntByReference();
                int pidOk = u.GetWindowThreadProcessId(hwnd, pid);
                log.warn("前台确认超时未就绪：目标 hwnd=0x{} (thread={}, pid={}), 当前前台 hwnd={}",
                        Long.toHexString(nativeValue(hwnd)), pidOk, pidOk != 0 ? pid.getValue() : -1,
                        fg == null ? "null" : "0x" + Long.toHexString(nativeValue(fg)));
                return false;
            }
            if (now >= nextRaiseAt) {
                raiseToForeground(hwnd);
                nextRaiseAt = now + FOREGROUND_RETRY_MS;
            }
            sleep(Math.max(5, Math.min(FOREGROUND_POLL_MS, deadline - now)));
        }
    }

    /**
     * 抬窗一次：先模拟一次无副作用的按键（F22 保留键）让系统把本进程记为「最近接收过输入」，
     * 从而获得 {@code SetForegroundWindow} 的调用许可，再真正把窗口带到前台。
     * 无窗口的后台服务进程若不这样做，直接 SetForegroundWindow 会被 Windows 的前台锁定策略
     * 直接拒绝（只闪任务栏、不切前台），这是此类进程切前台成功率低下的主因。
     */
    private void raiseToForeground(WinDef.HWND hwnd) {
        User32Mouse u = User32Mouse.INSTANCE;
        try {
            u.keybd_event(VK_F22, (byte) 0, 0, null);
            u.keybd_event(VK_F22, (byte) 0, KEYEVENTF_KEYUP, null);
            sleep(20);                                   // 等输入记录生效再抬窗
            u.SetForegroundWindow(hwnd);
        } catch (Throwable ignored) {
        }
    }

    /** 窗口所属的顶层根窗口（自身已是顶层窗口时 GetAncestor(GA_ROOT) 返回自身）。 */
    private static WinDef.HWND topRoot(WinDef.HWND hwnd) {
        try {
            WinDef.HWND root = User32Mouse.INSTANCE.GetAncestor(hwnd, GA_ROOT);
            return root != null ? root : hwnd;
        } catch (Throwable ignored) {
            return hwnd;
        }
    }

    private static boolean sameWindow(WinDef.HWND a, WinDef.HWND b) {
        if (a == null || b == null || a.getPointer() == null || b.getPointer() == null) {
            return false;
        }
        return nativeValue(a) == nativeValue(b);
    }

    private static long nativeValue(WinDef.HWND hwnd) {
        return hwnd == null || hwnd.getPointer() == null ? 0 : Pointer.nativeValue(hwnd.getPointer());
    }

    /** 窗口外框（GetWindowRect）左上角的屏幕坐标：采集器截图以整窗外框为区域，图像原点即该点。 */
    private WinDef.POINT windowOuterOrigin(WinDef.HWND hwnd) {
        WinDef.RECT rect = new WinDef.RECT();
        if (User32Mouse.INSTANCE.GetWindowRect(hwnd, rect)) {
            return new WinDef.POINT(rect.left, rect.top);
        }
        return null;
    }

    /** 窗口客户区左上角的屏幕坐标（= 外框原点 + 标题栏 / 边框的偏移）。 */
    private WinDef.POINT windowClientOrigin(WinDef.HWND hwnd) {
        WinDef.POINT pt = new WinDef.POINT(0, 0);
        return User32Mouse.INSTANCE.ClientToScreen(hwnd, pt) ? pt : null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
