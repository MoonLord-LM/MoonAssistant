package cn.moonlord.mca.capture;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 目标窗口尺寸强制：用 {@code SetWindowPos} 把窗口「整体缩放」到指定物理像素尺寸，
 * 用于保证截出来的 PNG 恰好是预定宽高（如 1280×720）。
 *
 * <p>核心思想（配合 {@link WindowCaptureTask} 的“截图尺寸校验”循环）：
 * 截图后若发现尺寸 ≠ 目标，就根据“当前窗口外框尺寸 + 截图与目标的尺寸差”反推出
 * 需要的窗口外框尺寸并立即 SetWindowPos 强制执行；等窗口完成重排后再次截图验证，
 * 不达标继续调，直到截出的 PNG 恰好为目标尺寸才保存。截图与窗口外框之间无论相差
 * 一个固定偏移（如含标题栏/边框），该迭代都会收敛，因此不依赖窗口具体样式。</p>
 *
 * <p>对比旧的“模拟拖动右缘”方案：拖动会让 MuMu 这类模拟器自行按内容比例适配
 * 高度，宽度达标但高度可能不受控（易出现黑边 / 尺寸不合规），且窗口较小时
 * 拖动可能被系统最小尺寸挡住而失效。改为 SetWindowPos 直接强制整窗外框尺寸后，
 * 无论窗口当前是大是小都能被一次设置到位，最大化窗口会自动先还原再设置。</p>
 *
 * <p>坐标说明：JVM 启动时（{@code MatchClassifyActApplication}）已调用
 * {@link #ensureProcessDpiAware()} 把本进程设为 Per-Monitor DPI 感知，
 * GetWindowRect / SetWindowPos 均使用物理像素坐标，与采集器抓帧尺寸语义一致。</p>
 */
@Slf4j
@Component
public class WindowResizer {

    /** 最大化窗口还原后等待其完成重新布局再操作的时长（毫秒） */
    private static final int RESTORE_SETTLE_MS = 400;

    /** SetWindowPos 后等待窗口完成重排/尺寸生效的时长（毫秒） */
    private static final int RESIZE_SETTLE_MS = 350;

    /** 设置后实测外框尺寸与目标的允许误差（像素）：个别窗口会自行微调 1~2px，不算失败 */
    private static final int SET_SIZE_TOLERANCE_PX = 2;

    // SetWindowPos 标志
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_NOZORDER = 0x0004;

    // GetSystemMetrics 虚拟屏幕范围索引
    private static final int SM_XVIRTUALSCREEN = 76;
    private static final int SM_YVIRTUALSCREEN = 77;
    private static final int SM_CXVIRTUALSCREEN = 78;
    private static final int SM_CYVIRTUALSCREEN = 79;

    // ShowWindow 命令（jna WinUser 未完整映射 SW_* 常量，直接使用数字并注释）
    private static final int SW_SHOWMINIMIZED = 2;
    private static final int SW_SHOWMAXIMIZED = 3;
    private static final int SW_RESTORE = 9;

    /** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2（伪句柄 -4，Win10 1607+） */
    private static final long DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4L;

    private final User32 user32 = User32.INSTANCE;

    /**
     * 依据最近一次截图尺寸，把窗口外框缩放成「截图达到目标尺寸」所需的大小并重新放回屏幕内。
     *
     * <p>设截图尺寸为 (pngW×pngH)、窗口当前外框为 (w×h)，则外框需要的新尺寸为
     * {@code w + (targetW - pngW)}、{@code h + (targetH - pngH)}：
     * 截图若是整个窗口外框（含标题栏/边框），一次即精确到位；截图若是窗口客户区，
     * 上式等价于把客户区扩到目标再补上边框差，同样收敛。窗口越大截图越大，恒线性，
     * 不会来回震荡。</p>
     *
     * @param hwnd    目标窗口句柄
     * @param title   目标窗口标题（仅日志）
     * @param pngW    最近一次截出的图像宽度（物理像素，&gt;0）
     * @param pngH    最近一次截出的图像高度（物理像素，&gt;0）
     * @param targetW 截图目标宽度（物理像素，&gt;0）
     * @param targetH 截图目标高度（物理像素，&gt;0）
     * @return {@code true} = 已执行一次强制缩放（调用方稍候应重截验证）；
     *         {@code false} = 暂无法操作（窗口最小化），或目标尺寸大于虚拟屏幕无法满足，
     *         调用方可延后重试
     */
    public boolean resizeWindowToPngSize(WinDef.HWND hwnd, String title,
                                         int pngW, int pngH, int targetW, int targetH) {
        if (hwnd == null || pngW <= 0 || pngH <= 0 || targetW <= 0 || targetH <= 0) {
            return false;
        }

        // 窗口可能刚被最小化：不打扰用户，交由调用方在下轮截图时重试
        if (placementShowCmd(hwnd) == SW_SHOWMINIMIZED) {
            log.debug("窗口 [{}] 已最小化，暂不强制调整尺寸", title);
            return false;
        }
        if (placementShowCmd(hwnd) == SW_SHOWMAXIMIZED) {
            // 最大化窗口无法被设置成任意尺寸：先还原，待重新布局后再设置
            log.info("窗口 [{}] 处于最大化，先还原为普通窗口再强制缩放到目标尺寸", title);
            user32.ShowWindow(hwnd, SW_RESTORE);
            sleep(RESTORE_SETTLE_MS);
        }

        WinDef.RECT outer = new WinDef.RECT();
        if (!user32.GetWindowRect(hwnd, outer)) {
            log.warn("窗口 [{}] 读取外框尺寸失败，跳过强制缩放", title);
            return false;
        }
        int curW = outer.right - outer.left;
        int curH = outer.bottom - outer.top;
        if (curW <= 0 || curH <= 0) {
            log.warn("窗口 [{}] 外框尺寸无效 ({}x{})，跳过强制缩放", title, curW, curH);
            return false;
        }

        // 由“截图与目标的差值”反推窗口外框应缩放到的尺寸（恒线性，一次或两次内收敛）
        int newW = curW + (targetW - pngW);
        int newH = curH + (targetH - pngH);
        if (newW <= 0 || newH <= 0) {
            log.warn("窗口 [{}] 推算出的目标尺寸非法 ({}x{})，跳过本次强制缩放", title, newW, newH);
            return false;
        }

        // 新外框必须整体落在虚拟屏幕内：分层窗口按显示器裁剪，超屏部分会被裁掉导致截图始终不达标
        int sx = user32.GetSystemMetrics(SM_XVIRTUALSCREEN);
        int sy = user32.GetSystemMetrics(SM_YVIRTUALSCREEN);
        int sw = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN);
        int sh = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN);
        if (sw <= 0 || sh <= 0) {
            log.warn("窗口 [{}] 读取虚拟屏幕范围失败，跳过强制缩放", title);
            return false;
        }
        if (newW > sw || newH > sh) {
            log.warn("窗口 [{}] 目标尺寸 {}x{} 超过当前屏幕可用范围 {}x{}，无法强制达到；"
                            + "请使用更大分辨率的显示器，或把目标程序的显示分辨率调小后再开启截图",
                    title, newW, newH, sw, sh);
            return false;
        }

        // 保持原左上角尽量不动，仅当放不下时平移回屏幕内
        int newX = clamp(outer.left, sx, sx + sw - newW);
        int newY = clamp(outer.top, sy, sy + sh - newH);

        boolean ok = user32.SetWindowPos(hwnd, null, newX, newY, newW, newH,
                SWP_NOZORDER | SWP_NOACTIVATE);
        if (!ok) {
            log.warn("窗口 [{}] SetWindowPos 设置尺寸失败，请检查窗口状态", title);
            return false;
        }
        sleep(RESIZE_SETTLE_MS);

        // 复核实测尺寸（个别窗口受自身最小尺寸约束会被钳制，此时返回 true 让调用方按实测迭代）
        WinDef.RECT after = new WinDef.RECT();
        if (user32.GetWindowRect(hwnd, after)) {
            int aw = after.right - after.left;
            int ah = after.bottom - after.top;
            if (Math.abs(aw - newW) > SET_SIZE_TOLERANCE_PX || Math.abs(ah - newH) > SET_SIZE_TOLERANCE_PX) {
                log.info("窗口 [{}] 强制缩放结果 {}x{}（请求 {}x{}，可能被窗口最小尺寸约束，"
                                + "下次截图后将继续按实测尺寸迭代）", title, aw, ah, newW, newH);
            } else {
                log.info("窗口 [{}] 已强制缩放至 {}x{}（目标截图 {}x{}），等待重截验证", title, aw, ah, targetW, targetH);
            }
        }
        return true;
    }

    /**
     * 把“程序自身的控制台窗口”强制置为指定外框尺寸，并按需在屏幕可用区域居中。
     *
     * <p>新窗口的初始尺寸与位置由 Chromium 的 {@code --window-size} /
     * {@code --window-position} 启动参数决定（创建即生效，不会闪跳）；
     * 该方法作为兜底：Chromium 应用窗口（--app）有时会沿用上次会话记忆的
     * 尺寸/位置，此时窗口出现后直接做一次 SetWindowPos 搬正即可。</p>
     *
     * <p>不做「先隐藏再显示」：隐藏再恢复会让窗口任务栏图标消失又出现、
     * 画面整窗闪烁，观感比直接搬位更差。这里保持窗口始终可见，仅用一次
     * SetWindowPos 把尺寸与位置设到位；若窗口已处于目标状态则不做任何操作。</p>
     *
     * @param hwnd    控制台窗口句柄
     * @param title   窗口标题（仅用于日志与调试）
     * @param width   目标外框宽度（物理像素，>0）
     * @param height  目标外框高度（物理像素，>0）
     * @param center  是否在屏幕（虚拟屏幕范围）中央居中；为 false 时保持原位置但保证不越出屏幕
     * @return 是否已设置到位（窗口最小化/尺寸超出屏幕等返回 false）
     */
    public boolean placeWindow(WinDef.HWND hwnd, String title, int width, int height, boolean center) {
        return doPlaceWindow(hwnd, title, width, height, center);
    }

    private boolean doPlaceWindow(WinDef.HWND hwnd, String title, int width, int height, boolean center) {
        if (hwnd == null || width <= 0 || height <= 0) {
            log.debug("placeWindow 参数无效：hwnd={}, {}x{}", hwnd, width, height);
            return false;
        }
        int show = placementShowCmd(hwnd);
        if (show == SW_SHOWMINIMIZED) {
            log.debug("控制台窗口 [{}] 已最小化，跳过强制调整尺寸与位置", title);
            return false;
        }
        if (show == SW_SHOWMAXIMIZED) {
            log.info("控制台窗口 [{}] 处于最大化，先还原为普通窗口再调整尺寸与位置", title);
            user32.ShowWindow(hwnd, SW_RESTORE);
            sleep(RESTORE_SETTLE_MS);
        }
        WinDef.RECT rect = new WinDef.RECT();
        if (!user32.GetWindowRect(hwnd, rect)) {
            log.warn("控制台窗口 [{}] 读取外框失败，跳过强制调整", title);
            return false;
        }
        int curW = rect.right - rect.left;
        int curH = rect.bottom - rect.top;
        if (curW <= 0 || curH <= 0) {
            log.warn("控制台窗口 [{}] 外框尺寸无效 ({}x{})，跳过强制调整", title, curW, curH);
            return false;
        }
        int sx = user32.GetSystemMetrics(SM_XVIRTUALSCREEN);
        int sy = user32.GetSystemMetrics(SM_YVIRTUALSCREEN);
        int sw = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN);
        int sh = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN);
        if (sw <= 0 || sh <= 0) {
            log.warn("控制台窗口 [{}] 读取虚拟屏幕范围失败，跳过强制调整", title);
            return false;
        }
        int w = Math.min(width, sw);
        int h = Math.min(height, sh);

        int newX;
        int newY;
        if (center) {
            // 居中于虚拟屏幕范围（跨多显示器时即整个桌面）中央
            newX = sx + Math.max(0, (sw - w) / 2);
            newY = sy + Math.max(0, (sh - h) / 2);
        } else {
            newX = clamp(rect.left, sx, sx + sw - w);
            newY = clamp(rect.top, sy, sy + sh - h);
        }

        if (w == curW && h == curH && newX == rect.left && newY == rect.top) {
            log.debug("控制台窗口 [{}] 已是目标尺寸与位置（{}x{} @ {},{}），无需调整", title, w, h, newX, newY);
            return true;
        }
        // 窗口保持可见，直接一次 SetWindowPos 把尺寸与位置设到位（不隐藏窗口，
        // 避免“消失→再出现”的整窗闪烁；SetWindowPos 本身是瞬间完成的搬位）
        boolean ok = user32.SetWindowPos(hwnd, null, newX, newY, w, h, SWP_NOZORDER | SWP_NOACTIVATE);
        if (!ok) {
            log.warn("控制台窗口 [{}] SetWindowPos 失败，请检查窗口状态", title);
            return false;
        }
        sleep(200);   // 等窗口完成重排/尺寸生效后再记录实测位置
        WinDef.RECT after = new WinDef.RECT();
        if (user32.GetWindowRect(hwnd, after)) {
            log.info("控制台窗口 [{}] 已强制置于 {}x{} @ ({}, {})（实测 {}x{}）",
                    title, w, h, newX, newY,
                    after.right - after.left, after.bottom - after.top);
        } else {
            log.info("控制台窗口 [{}] 已设置 {}x{} @ ({}, {})", title, w, h, newX, newY);
        }
        return true;
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int placementShowCmd(WinDef.HWND hwnd) {
        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        placement.length = placement.size();
        if (user32.GetWindowPlacement(hwnd, placement).booleanValue()) {
            return placement.showCmd;
        }
        return -1;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把本进程设为 Per-Monitor DPI 感知，使窗口 API 的坐标以物理像素为准。
     * 应在进程最早阶段调用（创建任何窗口前）。
     * 失败（系统低于 Win10 1607、权限/策略限制等）时静默忽略——坐标偏差只在
     * 系统显示缩放到非 100% 时才可能出现，多数个人场景不受影响。
     */
    public static void ensureProcessDpiAware() {
        try {
            User32Dpi.INSTANCE.SetProcessDpiAwarenessContext(
                    new WinNT.HANDLE(Pointer.createConstant(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)));
        } catch (Throwable ignored) {
            // 老系统无此 API 或进程已定 DPI 级别：忽略即可
        }
    }

    /** user32.dll 中 Win10 1607+ 才有的 SetProcessDpiAwarenessContext（jna 未映射，自行声明） */
    private interface User32Dpi extends StdCallLibrary {
        User32Dpi INSTANCE = Native.load("user32", User32Dpi.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean SetProcessDpiAwarenessContext(WinNT.HANDLE dpiContext);
    }

}
