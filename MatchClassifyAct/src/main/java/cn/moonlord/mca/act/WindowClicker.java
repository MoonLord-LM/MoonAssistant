package cn.moonlord.mca.act;

import cn.moonlord.mca.capture.WindowInfo;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 鼠标点击执行器：把「识别出的点击点」（图片像素 = 窗口相对坐标 Left/Top）转成一次真实的鼠标左键点击。
 *
 * <p>两种执行方式（由 execute.click-mode 控制）：</p>
 * <ul>
 *   <li>{@code post}（默认）：后台点击。直接向目标窗口发送 {@code WM_LBUTTONDOWN}/{@code WM_LBUTTONUP}
 *       窗口消息（坐标 = 窗口客户区相对坐标，低16位 x、高16位 y），不需要窗口在前台，不抢占用户鼠标；</li>
 *   <li>{@code screen}：真实输入。先用 {@code ClientToScreen} 把窗口相对坐标换算成屏幕坐标，
 *       再把窗口带到前台并用 {@code SetCursorPos + mouse_event} 模拟一次真实左键点击。</li>
 * </ul>
 */
@Slf4j
@Component
public class WindowClicker {

    public static final String MODE_POST = "post";
    public static final String MODE_SCREEN = "screen";

    // Windows SDK 鼠标消息 / 事件常量（JNA 平台库未映射这些数值，直接按 SDK 定义）
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_LBUTTONUP = 0x0202;
    private static final int MK_LBUTTON = 0x0001;
    private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    private static final int MOUSEEVENTF_LEFTUP = 0x0004;

    /**
     * user32.dll 中本次执行需要用到的函数。JNA 平台库 {@code User32} 对其中个别函数
     * 的签名与本工程用法不适配（如 PostMessage 声明为返回 LRESULT、未内置 ClientToScreen），
     * 因此统一在此按 SDK 语义自声明：BOOL → boolean、消息参数用 WinDef 结构。
     */
    private interface User32Mouse extends StdCallLibrary {
        User32Mouse INSTANCE = Native.load("user32", User32Mouse.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean PostMessage(WinDef.HWND hwnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam);

        boolean ClientToScreen(WinDef.HWND hwnd, WinDef.POINT pt);

        boolean SetForegroundWindow(WinDef.HWND hwnd);

        boolean SetCursorPos(int x, int y);

        void mouse_event(int dwFlags, int dx, int dy, int dwData, Pointer dwExtraInfo);
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
        WinDef.POINT screen = toScreen(hwnd, x, y);
        int lParam = ((y & 0xffff) << 16) | (x & 0xffff);   // HIWORD=y, LOWORD=x（窗口相对坐标）
        User32Mouse u = User32Mouse.INSTANCE;
        boolean down = u.PostMessage(hwnd, WM_LBUTTONDOWN, new WinDef.WPARAM(MK_LBUTTON), new WinDef.LPARAM(lParam));
        sleep(50);
        boolean up = u.PostMessage(hwnd, WM_LBUTTONUP, new WinDef.WPARAM(0), new WinDef.LPARAM(lParam));
        if (!down || !up) {
            return new Result(false, MODE_POST, "向窗口发送鼠标消息失败（PostMessage 返回 false）",
                    x, y, screen == null ? -1 : screen.x, screen == null ? -1 : screen.y);
        }
        return new Result(true, MODE_POST,
                "已向窗口「" + window.getTitle() + "」后台发送鼠标左键点击 (" + x + ", " + y + ")",
                x, y, screen == null ? -1 : screen.x, screen == null ? -1 : screen.y);
    }

    private Result screenClick(WindowInfo window, int x, int y) {
        WinDef.HWND hwnd = window.getHwnd();
        WinDef.POINT screen = toScreen(hwnd, x, y);
        if (screen == null) {
            return new Result(false, MODE_SCREEN, "无法把窗口坐标换算成屏幕坐标（窗口可能已销毁）",
                    x, y, -1, -1);
        }
        User32Mouse m = User32Mouse.INSTANCE;
        try {
            m.SetForegroundWindow(hwnd);          // 尽力把窗口带到前台（前台锁定被拒也不影响后续真实输入）
        } catch (Throwable ignored) {
        }
        sleep(80);
        boolean moved = m.SetCursorPos(screen.x, screen.y);
        if (!moved) {
            return new Result(false, MODE_SCREEN, "SetCursorPos 失败（无法移动鼠标到目标屏幕点）",
                    x, y, screen.x, screen.y);
        }
        sleep(50);
        m.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, null);
        sleep(60);
        m.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, null);
        return new Result(true, MODE_SCREEN,
                "已在屏幕坐标 (" + screen.x + ", " + screen.y + ") 模拟鼠标左键点击",
                x, y, screen.x, screen.y);
    }

    /** 窗口客户区 (x,y) → 屏幕坐标（0,0 处即客户区左上角在屏幕上的位置）。 */
    private WinDef.POINT toScreen(WinDef.HWND hwnd, int x, int y) {
        WinDef.POINT pt = new WinDef.POINT(x, y);
        if (User32Mouse.INSTANCE.ClientToScreen(hwnd, pt)) {
            return pt;
        }
        return null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
