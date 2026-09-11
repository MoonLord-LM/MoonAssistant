package cn.moonlord.mca.capture;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过 Windows API 查找并操作目标窗口。
 */
@Slf4j
@Component
public class WindowFinder {

    private final User32 user32 = User32.INSTANCE;

    /**
     * 在可见的顶层窗口中，查找标题命中任一关键字的窗口。
     *
     * <p>MuMu 模拟器会同时存在多个含 "MuMu" 的窗口：有的只是界面框架/悬浮窗
     * （通常带 {@code WS_EX_LAYERED} 分层样式，WGC 无法直接捕获其合成内容，会走
     * "显示器裁剪"兜底，且对部分分层窗口仍可能得到黑屏）；有的则是真正承载游戏画面的
     * 渲染窗口（非分层，WGC 可直接按窗口抓取，画面完整清晰、不依赖前台/遮挡）。</p>
     *
     * <p>因此优先选择 <b>非分层</b> 且面积最大的候选；仅当所有命中窗口都是分层窗口时，
     * 才退而选择其中面积最大者（交给采集器的分层兜底逻辑）。</p>
     *
     * @param keywords 标题关键字，命中任一即可视为候选
     * @return 命中最优窗口，未找到返回 null
     */
    public WindowInfo findTarget(List<String> keywords) {
        WindowInfo bestRegular = null; // 非 LAYERED：可被 WGC 直接按窗口捕获
        WindowInfo bestLayered = null; // LAYERED：仅作兜底
        for (WindowInfo window : listTopLevelWindows()) {
            if (!matchesAny(window.getTitle(), keywords)) {
                continue;
            }
            if (isLayered(window.getHwnd())) {
                if (bestLayered == null || area(window) > area(bestLayered)) {
                    bestLayered = window;
                }
            } else {
                if (bestRegular == null || area(window) > area(bestRegular)) {
                    bestRegular = window;
                }
            }
        }
        return bestRegular != null ? bestRegular : bestLayered;
    }

    /** 判断窗口是否带 {@code WS_EX_LAYERED}（分层透明）样式 */
    private boolean isLayered(WinDef.HWND hWnd) {
        if (hWnd == null) {
            return false;
        }
        final int GWL_EXSTYLE = -20;
        final int WS_EX_LAYERED = 0x00080000;
        int exstyle = user32.GetWindowLong(hWnd, GWL_EXSTYLE);
        return (exstyle & WS_EX_LAYERED) != 0;
    }

    private boolean matchesAny(String title, List<String> keywords) {
        for (String keyword : keywords) {
            if (title.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private long area(WindowInfo window) {
        Rectangle bounds = window.getBounds();
        return (long) bounds.width * bounds.height;
    }

    private List<WindowInfo> listTopLevelWindows() {
        List<WindowInfo> windows = new ArrayList<>();
        user32.EnumWindows(new WinUser.WNDENUMPROC() {

            @Override
            public boolean callback(WinDef.HWND hWnd, Pointer userData) {
                if (!user32.IsWindowVisible(hWnd)) {
                    return true; // 跳过不可见窗口
                }
                WindowInfo window = readWindowInfo(hWnd);
                if (window != null) {
                    windows.add(window);
                }
                return true; // 继续枚举
            }
        }, null);
        return windows;
    }

    private WindowInfo readWindowInfo(WinDef.HWND hWnd) {
        String title = readWindowTitle(hWnd);
        if (title == null || title.isEmpty()) {
            return null;
        }
        WinDef.RECT rect = new WinDef.RECT();
        if (!user32.GetWindowRect(hWnd, rect)) {
            return null;
        }
        Rectangle bounds = new Rectangle(rect.left, rect.top,
                rect.right - rect.left, rect.bottom - rect.top);
        return WindowInfo.builder()
                .hwnd(hWnd)
                .title(title)
                .bounds(bounds)
                .minimized(isMinimized(hWnd))
                .build();
    }

    private boolean isMinimized(WinDef.HWND hWnd) {
        // jna 的 User32 没有映射 IsIconic，改用 GetWindowPlacement 判断
        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        placement.length = placement.size();
        if (user32.GetWindowPlacement(hWnd, placement).booleanValue()) {
            return placement.showCmd == WinUser.SW_SHOWMINIMIZED;
        }
        return false;
    }

    private String readWindowTitle(WinDef.HWND hWnd) {
        char[] buffer = new char[512];
        int length = user32.GetWindowText(hWnd, buffer, buffer.length);
        return length > 0 ? new String(buffer, 0, length) : null;
    }

}
