package cn.moonlord.mca.act;

import cn.moonlord.mca.capture.WindowInfo;
import cn.moonlord.mca.config.ExecuteProperties;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 鼠标点击执行器：把「识别出的点击点」（图片像素 = 窗口相对坐标 Left/Top）转成一次真实的鼠标左键点击。
 *
 * <p>五种执行方式（由 {@code execute.click-mode} 控制，控制台「执行模式」页可实时切换）：</p>
 * <ul>
 *   <li>{@code mumu}（默认）：MuMu 模拟器。不碰鼠标、不动窗口焦点，把点击交给
 *       {@code MuMuManager.exe adb -v <实例序号> -c "shell input tap x y"} 由模拟器自己的 adb 通道注入
 *       （模拟器在后台 / 窗口被遮挡也能点）。坐标分两步从「窗口截图坐标」换算成「模拟器内坐标」：
 *       先减掉模拟器边框占用得到「游戏画面内坐标」（{@code execute.mumu-click-offset-x/-y}），
 *       再按「游戏画面 → 模拟器实际分辨率」等比放大（见 {@link #MUMU_DEVICE_WIDTH}），见 {@link #mumuClick}；</li>
 *   <li>{@code screen}：前台点击。截图画面 = 窗口整窗外框（采集器按 GetWindowRect 裁取），
 *       因此用「窗口外框左上角 + 图片像素」得到屏幕坐标，再把窗口带到前台并用
 *       {@code SetCursorPos + mouse_event} 模拟一次真实左键点击；</li>
 *   <li>{@code rawinput}：RawInput 输入。<b>必须前台可见</b> —— 真实鼠标输入只投给「光标下的窗口」，
 *       目标点必须在屏幕上、且该点最上层就是目标窗口，否则输入会落到上层窗口上，所以它不能像
 *       {@code mumu} / {@code post} / {@code sendmessage} 那样完全后台运行。同样用「窗口外框左上角 + 图片像素」
 *       得到屏幕坐标，直接注入<b>系统级真实鼠标输入</b>（{@code SendInput}：绝对移动 → 左键按下 → 抬起）。
 *       注入的事件会进入系统输入链，认 RawInput / DirectInput（或轮询 {@code GetCursorPos}）的程序也能收到
 *       （{@code screen} 用的 {@code mouse_event} 是遗留接口，这类程序常常收不到）。目标点本来就可见时
 *       不切换前台、也不等待前台；被别的窗口压住时按「抬窗 → 抢前台 → 临时置顶 → 临时挪到光标处」
 *       逐级化解遮挡（见 {@link #ensureVisible}，可用 {@code execute.rawinput-auto-expose} 关闭），
 *       点击完成即还原窗口状态；全部失败才取消本次点击；</li>
 *   <li>{@code post}：后台消息。向目标窗口投递一条与真实鼠标路径一致的消息序列：先 3 次
 *       {@code WM_MOUSEMOVE} 模拟滑入轨迹、再 {@code WM_MOUSEACTIVATE} 声明点击意图（是否激活由窗口决定）、
 *       最后 {@code WM_LBUTTONDOWN}/{@code WM_LBUTTONUP}（客户区坐标 = 图片像素 − 标题栏 / 边框偏移）。
 *       不需要窗口在前台、不抢占用户鼠标，比只发「按下/抬起」更易被普通桌面程序接受；
 *       游戏 / 模拟器仍多数会忽略合成消息；</li>
 *   <li>{@code sendmessage}：后台消息（挪窗）。是 {@code post} 的「同步 + 挪窗对齐」版，对应 MaaFramework 的
 *       {@code SendMessageWithWindowPos}：发送前先把窗口临时挪一下、让目标点正好落在当前光标位置，再用
 *       {@code SendMessageTimeout} <b>同步</b>发送同一套点击消息序列，发完把窗口位置还原。
 *       不碰用户光标、不需要前台、不受遮挡影响；代价是窗口会短暂闪一下。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WindowClicker {

    public static final String MODE_MUMU = "mumu";
    public static final String MODE_SCREEN = "screen";
    public static final String MODE_RAW_INPUT = "rawinput";
    public static final String MODE_POST = "post";
    /** 同 {@code post} 的消息路径，但改为同步发送、且发送前把窗口挪到光标处对齐（Maa 的 {@code SendMessageWithWindowPos}）。 */
    public static final String MODE_SEND_MESSAGE = "sendmessage";

    /** MuMu 模拟器的实例序号（{@code MuMuManager.exe … adb -v <序号> …}，从 0 开始 = 第一个实例）。 */
    private static final String MUMU_INSTANCE_INDEX = "0";
    /** 单次 MuMuManager 命令的等待上限（毫秒）：超时即强杀，避免页面上的点击请求一直悬挂。 */
    private static final long MUMU_CMD_TIMEOUT_MS = 5000;
    /** 失败时回显 MuMuManager 输出的字符上限，避免把大段输出塞进页面提示。 */
    private static final int MUMU_OUTPUT_MAX = 200;

    /**
     * MuMu 模拟器（Android 侧）的<b>实际分辨率</b>：{@code shell input tap} 用的就是这套坐标空间。
     * 模拟器窗口会被 {@code capture.resize-width}/{@code capture.resize-height} 强制缩放到截图尺寸，
     * 但模拟器里的分辨率不跟着变，所以按窗口截图算出来的识别点必须放大回本尺寸才点得准。
     */
    private static final int MUMU_DEVICE_WIDTH = 2560;
    private static final int MUMU_DEVICE_HEIGHT = 1440;

    /**
     * 窗口截图里「游戏画面」占的像素尺寸：截图范围 = 模拟器窗口整窗外框，四周是模拟器自己的边框
     * （各让出多少见 {@code execute.mumu-click-offset-x/-y}），游戏画面只占中间这一块。
     */
    private static final int MUMU_GAME_WIDTH = 1170;
    private static final int MUMU_GAME_HEIGHT = 660;

    /** 是否是受支持的点击方式（非法值由调用方忽略并保留原值）。 */
    public static boolean isSupportedMode(String mode) {
        return MODE_MUMU.equals(mode) || MODE_SCREEN.equals(mode)
                || MODE_RAW_INPUT.equals(mode) || MODE_POST.equals(mode)
                || MODE_SEND_MESSAGE.equals(mode);
    }

    /** 点击方式的中文名（日志口径：「MuMu 模拟器」/「前台点击」/「RawInput 输入」/「后台消息·挪窗」/「后台消息」）。 */
    public static String modeLabel(String mode) {
        if (MODE_MUMU.equals(mode)) {
            return "MuMu 模拟器：走 MuMuManager.exe 的 adb 通道注入点击，不抢鼠标前台";
        }
        if (MODE_RAW_INPUT.equals(mode)) {
            return "RawInput 输入：必须前台可见（真实输入只投给光标下的窗口）；系统真实鼠标输入注入（SendInput），被遮挡时先自动抬窗 / 挪窗再点";
        }
        if (MODE_SEND_MESSAGE.equals(mode)) {
            return "后台消息·挪窗：先把窗口挪到光标处对齐，再同步发送完整点击消息序列";
        }
        if (MODE_POST.equals(mode)) {
            return "后台消息：完整点击消息序列，不抢鼠标焦点";
        }
        return "前台点击：真实鼠标输入，需要窗口可见、不被遮挡";
    }

    // Windows SDK 鼠标消息 / 事件常量（JNA 平台库未映射这些数值，直接按 SDK 定义）
    private static final int WM_MOUSEMOVE = 0x0200;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_LBUTTONUP = 0x0202;
    private static final int WM_MOUSEACTIVATE = 0x0021;
    private static final int MK_LBUTTON = 0x0001;
    private static final int HTCLIENT = 0x0001;   // 命中测试码：客户区（WM_MOUSEACTIVATE 的 LOWORD）
    private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    private static final int MOUSEEVENTF_LEFTUP = 0x0004;

    // SendInput 注入用的常量（JNA 平台库未映射 INPUT 结构相关的这些数值，直接按 SDK 定义）
    private static final int INPUT_MOUSE = 0;
    private static final int MOUSEEVENTF_MOVE = 0x0001;
    private static final int MOUSEEVENTF_VIRTUALDESK = 0x4000;
    private static final int MOUSEEVENTF_ABSOLUTE = 0x8000;
    /** 绝对坐标的归一化满量程：SDK 约定 dx/dy 取 0 = 虚拟桌面最左 / 最上，取本值 = 最右 / 最下 */
    private static final int ABSOLUTE_RANGE = 65535;
    // GetSystemMetrics 的虚拟桌面维度索引（绝对坐标要按整个虚拟桌面归一化，多显示器下才落得准）
    private static final int SM_XVIRTUALSCREEN = 76;
    private static final int SM_YVIRTUALSCREEN = 77;
    private static final int SM_CXVIRTUALSCREEN = 78;
    private static final int SM_CYVIRTUALSCREEN = 79;
    /** 读窗口标题的缓冲长度（只在「点到谁身上了」这类错误提示里用） */
    private static final int TITLE_MAX_CHARS = 256;

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

    // sendmessage（SendMessage + 挪窗）专用：① 同步消息的超时上限 —— SendMessage 会一直等到目标线程
    // 处理完，目标忙时会把调用方一起挂住，所以统一走 SendMessageTimeout；② 挪窗后等窗口真正到位的节奏 ——
    // SetWindowPos 跨进程是异步的（真正移动发生在窗口线程），到位判据允许几像素误差
    private static final int SEND_MESSAGE_TIMEOUT_MS = 1000;
    private static final int SMTO_NORMAL = 0x0000;
    private static final int SMTO_ABORTIFHUNG = 0x0002;
    private static final int WINDOW_MOVE_SETTLE_MS = 40;
    private static final int WINDOW_MOVE_RETRY_TIMES = 4;
    private static final int WINDOW_MOVE_RETRY_MS = 50;
    private static final int WINDOW_MOVE_TOLERANCE = 2;

    // RawInput 输入的节奏（毫秒）：注入移动后等目标窗口建立 hover 状态、按下与抬起之间、抬起后把光标移回原位前
    private static final int RAW_INPUT_MOVE_SETTLE_MS = 50;
    private static final int RAW_INPUT_CLICK_GAP_MS = 60;
    private static final int RAW_INPUT_BACK_MS = 20;

    // RawInput 输入化解遮挡用的 SetWindowPos 参数：z 序句柄用数值常量表示（同类窗口最前 / 置顶 / 取消置顶），
    // 以及 SWP_NOSIZE / SWP_NOMOVE / SWP_NOZORDER / SWP_NOACTIVATE / SWP_NOOWNERZORDER 五个标志
    private static final int HWND_TOP_VALUE = 0;
    private static final int HWND_TOPMOST_VALUE = -1;
    private static final int HWND_NOTOPMOST_VALUE = -2;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_NOOWNERZORDER = 0x0200;
    // 化解遮挡每一步之后的复核节奏（毫秒）：窗口的抬升 / 挪位是异步生效的，先稳定一小会儿再轮询几次
    private static final int EXPOSE_SETTLE_MS = 40;
    private static final int EXPOSE_RETRY_TIMES = 4;
    private static final int EXPOSE_RETRY_MS = 60;

    /**
     * user32.dll 中本次执行需要用到的函数。JNA 平台库 {@code User32} 对其中个别函数
     * 的签名与本工程用法不适配（如 PostMessage 声明为返回 LRESULT、未内置 ClientToScreen），
     * 因此统一在此按 SDK 语义自声明：BOOL → boolean、消息参数用 WinDef 结构。
     */
    private interface User32Mouse extends StdCallLibrary {
        User32Mouse INSTANCE = Native.load("user32", User32Mouse.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean PostMessage(WinDef.HWND hwnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam);

        /**
         * 同步发送窗口消息（带超时）：{@code SendMessage} 会一直等到目标线程处理完，目标忙时会把调用方一起挂住，
         * 因此统一用它限时。返回值 {@code 0} = 失败 / 超时未送达（可看 GetLastError），非 0 = 已被目标线程处理；
         * {@code lpdwResult} 可以传 {@code null}（不关心消息的 LRESULT）。
         */
        int SendMessageTimeoutW(WinDef.HWND hwnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam,
                                int fuFlags, int uTimeout, PointerByReference lpdwResult);

        boolean ClientToScreen(WinDef.HWND hwnd, WinDef.POINT pt);

        boolean GetWindowRect(WinDef.HWND hwnd, WinDef.RECT rect);

        boolean SetWindowPos(WinDef.HWND hwnd, WinDef.HWND insertAfter, int x, int y, int cx, int cy, int flags);

        boolean SetForegroundWindow(WinDef.HWND hwnd);

        boolean SetCursorPos(int x, int y);

        boolean GetCursorPos(WinDef.POINT point);

        void mouse_event(int dwFlags, int dx, int dy, int dwData, Pointer dwExtraInfo);

        WinDef.HWND GetForegroundWindow();

        WinDef.HWND GetAncestor(WinDef.HWND hwnd, int gaFlags);

        void keybd_event(byte bVk, byte bScan, int dwFlags, Pointer dwExtraInfo);

        int GetWindowThreadProcessId(WinDef.HWND hwnd, IntByReference pid);

        int SendInput(int cInputs, Input[] pInputs, int cbSize);

        /** POINT 在 SDK 里是按值传参，JNA 默认按引用传，这里用 {@code ByValue} 版本对齐 ABI。 */
        WinDef.HWND WindowFromPoint(PointByValue point);

        int GetSystemMetrics(int nIndex);

        int GetWindowText(WinDef.HWND hwnd, char[] text, int maxCount);
    }

    /**
     * {@code SendInput} 的 {@code INPUT} 结构：只用到鼠标分支，因此直接按「type 字段 + MOUSEINPUT」
     * 的布局声明，不另建联合体（键盘 / 硬件分支用不到）。字段顺序与对齐交给 JNA 按 ABI 计算，
     * {@link #sendInput} 把 {@code size()} 结果直接当 {@code cbSize} 传给系统 —— 与 SDK 的
     * {@code sizeof(INPUT)} 一致（系统会校验这个值，不符即拒绝注入）。
     */
    public static class Input extends Structure {
        public int type;
        public MouseInput mi = new MouseInput();

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("type", "mi");
        }
    }

    /** {@code INPUT} 里的鼠标分支 {@code MOUSEINPUT}。 */
    public static class MouseInput extends Structure {
        public int dx;
        public int dy;
        public int mouseData;
        public int dwFlags;
        public int time;
        public BaseTSD.ULONG_PTR dwExtraInfo = new BaseTSD.ULONG_PTR(0);

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("dx", "dy", "mouseData", "dwFlags", "time", "dwExtraInfo");
        }
    }

    /** {@code POINT} 的按值传参版本（{@code WindowFromPoint} 用；JNA 的字段顺序注解不会被子类继承，故重写）。 */
    public static class PointByValue extends WinDef.POINT implements Structure.ByValue {
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("x", "y");
        }
    }

    /** 点击结果（screenX/Y 为换算后的坐标：screen / rawinput 模式 = 屏幕坐标、mumu 模式 = 模拟器内坐标，
     *  都只作反馈展示；post / sendmessage 模式不依赖它们，恒 -1）。 */
    public record Result(boolean ok, String mode, String message,
                         int x, int y, int screenX, int screenY) {
    }

    /**
     * 在目标窗口的 (x, y)（图片像素 = 窗口相对坐标）处发送一次鼠标左键点击。
     *
     * @param window 目标窗口（需当前仍存在）
     * @param x      相对窗口内容左上角的 x
     * @param y      相对窗口内容左上角的 y
     * @param mode   {@link #MODE_MUMU} / {@link #MODE_SCREEN} / {@link #MODE_RAW_INPUT} / {@link #MODE_POST}
     *               / {@link #MODE_SEND_MESSAGE}
     */
    public Result click(WindowInfo window, int x, int y, String mode) {
        if (window == null || window.getHwnd() == null || window.getHwnd().getPointer() == null) {
            return new Result(false, mode, "目标窗口不存在或句柄已失效", x, y, -1, -1);
        }
        if (MODE_MUMU.equalsIgnoreCase(mode)) {
            return mumuClick(x, y);
        }
        if (MODE_RAW_INPUT.equalsIgnoreCase(mode)) {
            return rawInputClick(window, x, y);
        }
        if (MODE_SEND_MESSAGE.equalsIgnoreCase(mode)) {
            return sendMessageClick(window, x, y);
        }
        return MODE_POST.equalsIgnoreCase(mode) ? postClick(window, x, y) : screenClick(window, x, y);
    }

    /**
     * MuMu 模拟器模式：不动鼠标、不抢前台，直接让 {@code MuMuManager.exe} 通过模拟器自己的 adb 通道注入点击。
     *
     * <p>坐标换算（{@code shell input tap} 用的是模拟器内的实际分辨率坐标，不是窗口截图像素）：</p>
     * <ol>
     *   <li>先减掉模拟器边框占用 {@code execute.mumu-click-offset-x/-y} —— 识别点取自<b>窗口截图</b>，
     *       而游戏画面四周那一圈是模拟器自己的边框，减成负数按 0 处理，得到「游戏画面内坐标」；</li>
     *   <li>再按「游戏画面 {@link #MUMU_GAME_WIDTH}×{@link #MUMU_GAME_HEIGHT} → 模拟器实际分辨率
     *       {@link #MUMU_DEVICE_WIDTH}×{@link #MUMU_DEVICE_HEIGHT}」等比放大 —— 窗口是被强制缩小到
     *       截图尺寸的，模拟器里的画面仍是原始分辨率，不放大回去点出来的位置就会偏到左上方。</li>
     * </ol>
     */
    private Result mumuClick(int x, int y) {
        int offsetX = executeProperties.getMumuClickOffsetX();
        int offsetY = executeProperties.getMumuClickOffsetY();
        int gx = Math.max(0, x - offsetX);
        int gy = Math.max(0, y - offsetY);
        int tx = (int) Math.round(gx * (double) MUMU_DEVICE_WIDTH / MUMU_GAME_WIDTH);
        int ty = (int) Math.round(gy * (double) MUMU_DEVICE_HEIGHT / MUMU_GAME_HEIGHT);
        Path exe = Path.of(executeProperties.getMumuManagerPath());
        if (!Files.isRegularFile(exe)) {
            return new Result(false, MODE_MUMU,
                    "没找到 MuMuManager.exe：" + exe + "（MuMu 模拟器模式要靠它注入点击，请确认安装位置，"
                            + "或改配置 execute.mumu-manager-path）",
                    x, y, tx, ty);
        }
        List<String> command = List.of(exe.toString(), "adb", "-v", MUMU_INSTANCE_INDEX, "-c",
                "shell input tap " + tx + " " + ty);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(MUMU_CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // 超时后进程可能仍在写管道：先强杀使其退出再收尾，避免 readAllBytes 等 EOF 永久阻塞
                process.destroyForcibly();
                process.waitFor();
                log.warn("MuMuManager 执行超时，已强制结束：{}", String.join(" ", command));
                return new Result(false, MODE_MUMU,
                        "MuMuManager 执行超时（" + MUMU_CMD_TIMEOUT_MS + "ms），已强制结束", x, y, tx, ty);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.exitValue();
            if (exit != 0) {
                String detail = output.isEmpty() ? "" : "：" + tail(output);
                log.warn("MuMuManager 返回 {}: {}", exit, output);
                return new Result(false, MODE_MUMU,
                        "MuMuManager 返回非 0 退出码 " + exit + detail, x, y, tx, ty);
            }
            log.info("MuMu 模拟器点击已注入：shell input tap {} {}（识别点 {},{} 减边框偏移 {}/{} 得游戏画面内 {},{}，"
                            + "再按 {}x{} → {}x{} 放大）",
                    tx, ty, x, y, offsetX, offsetY, gx, gy,
                    MUMU_GAME_WIDTH, MUMU_GAME_HEIGHT, MUMU_DEVICE_WIDTH, MUMU_DEVICE_HEIGHT);
            return new Result(true, MODE_MUMU,
                    "已通过 MuMu 模拟器注入点击：shell input tap (" + tx + ", " + ty + ")"
                            + "（识别点 (" + x + ", " + y + ") 减去模拟器边框偏移 " + offsetX + "/" + offsetY
                            + " 得游戏画面内 (" + gx + ", " + gy + ")，再按 "
                            + MUMU_GAME_WIDTH + "x" + MUMU_GAME_HEIGHT + " → "
                            + MUMU_DEVICE_WIDTH + "x" + MUMU_DEVICE_HEIGHT + " 放大）",
                    x, y, tx, ty);
        } catch (IOException e) {
            log.warn("调用 MuMuManager 失败：{}", e.getMessage());
            return new Result(false, MODE_MUMU, "调用 MuMuManager.exe 失败：" + e.getMessage(), x, y, tx, ty);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, MODE_MUMU, "MuMuManager 调用被中断", x, y, tx, ty);
        }
    }

    /** MuMuManager 输出限长（只保留结尾，报错信息通常在最末几行）。 */
    private static String tail(String output) {
        return output.length() <= MUMU_OUTPUT_MAX ? output : "…" + output.substring(output.length() - MUMU_OUTPUT_MAX);
    }

    /** {@code post}（后台消息）：异步投递完整点击消息序列，不等窗口处理。 */
    private Result postClick(WindowInfo window, int x, int y) {
        return messageSequence(window, x, y, false, MODE_POST, "");
    }

    /**
     * {@code sendmessage}（后台消息 · 挪窗）：MaaFramework 的 {@code SendMessageWithWindowPos} 同款做法 ——
     * 发送前把<b>窗口</b>临时挪一下，让目标点正好落在<b>当前光标</b>位置上（发完立即把位置还原），
     * 再用 {@code SendMessageTimeout} <b>同步</b>发送与 {@code post} 同一套点击消息序列。
     *
     * <p>为什么除了发消息还要挪窗：有一类程序不认消息里带的坐标，而是自己去问 {@code GetCursorPos()}、
     * 或者对目标点做命中测试来判断「这次点击是不是真的落在我身上」，光发消息它就不理。把窗口挪到光标处之后，
     * 光标真的压在目标点上（消息里的客户区坐标照样一致），这类检查就都通过了。</p>
     *
     * <p>与 {@code rawinput} 的「临时挪到光标处」是同一手法，区别在注入的东西：那边注入的是真实输入
     * （{@code SendInput}），所以要事先算好遮挡；这边注入的是合成消息（{@code SendMessage}），
     * 因此不碰用户光标、不需要前台、也不受遮挡影响。代价是窗口会短暂闪一下（挪 + 还原），
     * 点击期间（约两百毫秒）鼠标停在目标点上。</p>
     */
    private Result sendMessageClick(WindowInfo window, int x, int y) {
        WinDef.HWND hwnd = window.getHwnd();
        User32Mouse u = User32Mouse.INSTANCE;
        WinDef.POINT cursor = new WinDef.POINT();
        WinDef.RECT origin = new WinDef.RECT();
        if (!u.GetCursorPos(cursor)) {
            return new Result(false, MODE_SEND_MESSAGE,
                    "读不到当前光标位置（挪窗对齐要用它），本次点击已取消", x, y, -1, -1);
        }
        if (!u.GetWindowRect(hwnd, origin)) {
            return new Result(false, MODE_SEND_MESSAGE, "无法读取窗口屏幕位置（窗口可能已销毁）", x, y, -1, -1);
        }
        // 挪窗算式：截图像素原点 = 窗口外框原点，所以把外框挪到「光标 − 目标点」= 目标点正好压在光标上
        int targetLeft = cursor.x - x, targetTop = cursor.y - y;
        // 只挪位置：不动 z 序 / 大小，也不激活窗口（点击完成后同样只把位置还原）
        int moveFlags = SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER;
        if (!u.SetWindowPos(hwnd, null, targetLeft, targetTop, 0, 0, moveFlags)) {
            return new Result(false, MODE_SEND_MESSAGE,
                    "SetWindowPos 失败（无法把窗口挪到光标处），本次点击已取消", x, y, -1, -1);
        }
        try {
            boolean at = awaitWindowAt(hwnd, targetLeft, targetTop);
            return messageSequence(window, x, y, true, MODE_SEND_MESSAGE,
                    at ? "" : "（提示：未能确认窗口挪到位 —— 可能已最大化或被系统限制移动，消息坐标不受影响）");
        } finally {
            u.SetWindowPos(hwnd, null, origin.left, origin.top, 0, 0, moveFlags);
        }
    }

    /**
     * 发完整点击消息序列（滑入移动 → 激活意图 → 左键按下 / 抬起），{@code post} 与 {@code sendmessage} 共用。
     *
     * @param sync  true = {@code SendMessageTimeout} 同步发送（能知道目标线程有没有处理，超时即停）；
     *              false = {@code PostMessage} 异步投递（只保证消息进了队列）
     * @param extra 追加到成功文案末尾的补充说明（不需要时传空串）
     */
    private Result messageSequence(WindowInfo window, int x, int y, boolean sync, String mode, String extra) {
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
        // 1) 滑入轨迹：从客户区左上角向目标点做 3 次 WM_MOUSEMOVE 插值。
        //    多数控件/自绘框架要先收到「光标进入」的移动消息建立 hover 状态，否则直接收到的按下会被忽略。
        for (int i = 1; i <= POST_MOVE_STEPS; i++) {
            int mx = (int) Math.round(cx * (double) i / POST_MOVE_STEPS);
            int my = (int) Math.round(cy * (double) i / POST_MOVE_STEPS);
            if (!sendMessage(hwnd, WM_MOUSEMOVE, new WinDef.WPARAM(0), new WinDef.LPARAM(mouseLParam(mx, my)), sync)) {
                return messageFail(sync, mode, x, y);
            }
            sleep(POST_MOVE_STEP_MS);
        }
        // 2) 激活意图探测：声明「本次按下本应激活该窗口」，窗口内部决定是否激活
        //    （自绘控件若返回 MA_NOACTIVATE 即保持后台、不抢前台；无法强制其返回值）。
        long top = Pointer.nativeValue(hwnd.getPointer());   // wParam = 顶层窗口句柄值
        if (!sendMessage(hwnd, WM_MOUSEACTIVATE, new WinDef.WPARAM(top), new WinDef.LPARAM(activateLParam()), sync)) {
            return messageFail(sync, mode, x, y);
        }
        sleep(POST_MOVE_STEP_MS);
        // 3) 左键按下 / 抬起（客户区坐标）
        if (!sendMessage(hwnd, WM_LBUTTONDOWN, new WinDef.WPARAM(MK_LBUTTON), new WinDef.LPARAM(mouseLParam(cx, cy)), sync)) {
            return messageFail(sync, mode, x, y);
        }
        sleep(POST_CLICK_GAP_MS);
        if (!sendMessage(hwnd, WM_LBUTTONUP, new WinDef.WPARAM(0), new WinDef.LPARAM(mouseLParam(cx, cy)), sync)) {
            return messageFail(sync, mode, x, y);
        }
        String what = sync ? "挪窗对齐后同步发送完整点击消息序列（移动→按下→抬起）"
                : "后台投递完整点击消息序列（移动→按下→抬起）";
        return new Result(true, mode,
                "已向窗口「" + window.getTitle() + "」" + what + "(" + x + ", " + y + ")" + extra,
                x, y, -1, -1);
    }

    /** 发一条窗口消息：{@code sync} = 同步（SendMessageTimeout 限时，超时 / 失败即 false）；否则异步投递（PostMessage）。 */
    private boolean sendMessage(WinDef.HWND hwnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam, boolean sync) {
        if (!sync) {
            return User32Mouse.INSTANCE.PostMessage(hwnd, msg, wParam, lParam);
        }
        // 返回值 0 = 失败 / 超时未送达（GetLastError 有值），非 0 = 已被目标线程处理
        int ok = User32Mouse.INSTANCE.SendMessageTimeoutW(hwnd, msg, wParam, lParam,
                SMTO_NORMAL | SMTO_ABORTIFHUNG, SEND_MESSAGE_TIMEOUT_MS, null);
        if (ok == 0) {
            log.warn("SendMessageTimeout 未确认送达：hwnd=0x{}, msg=0x{}, GetLastError={}",
                    Long.toHexString(nativeValue(hwnd)), Integer.toHexString(msg), Kernel32.INSTANCE.GetLastError());
        }
        return ok != 0;
    }

    /** 消息序列里某一条发不出去时的失败结果（同步 / 异步的原因分别说明）。 */
    private Result messageFail(boolean sync, String mode, int x, int y) {
        return new Result(false, mode, sync
                ? "同步消息未送达：目标窗口未在 " + SEND_MESSAGE_TIMEOUT_MS
                        + "ms 内处理该消息（线程忙或无响应），本次点击已取消"
                : "后台消息序列发送失败（PostMessage 返回 false）",
                x, y, -1, -1);
    }

    /** 等窗口真正挪到位：SetWindowPos 跨进程生效是异步的（真正移动发生在窗口线程），先稳定一小会儿再轮询几次。 */
    private boolean awaitWindowAt(WinDef.HWND hwnd, int expectLeft, int expectTop) {
        sleep(WINDOW_MOVE_SETTLE_MS);
        for (int i = 0; i <= WINDOW_MOVE_RETRY_TIMES; i++) {
            WinDef.RECT rect = new WinDef.RECT();
            if (User32Mouse.INSTANCE.GetWindowRect(hwnd, rect)
                    && Math.abs(rect.left - expectLeft) <= WINDOW_MOVE_TOLERANCE
                    && Math.abs(rect.top - expectTop) <= WINDOW_MOVE_TOLERANCE) {
                return true;
            }
            sleep(WINDOW_MOVE_RETRY_MS);
        }
        return false;
    }

    /** 鼠标消息 lParam：HIWORD=y、LOWORD=x（均为客户区坐标，单字 16 位）。 */
    private static int mouseLParam(int x, int y) {
        return ((y & 0xffff) << 16) | (x & 0xffff);
    }

    /** WM_MOUSEACTIVATE 的 lParam：HIWORD=产生这次点击的鼠标消息、LOWORD=命中测试码（客户区）。 */
    private static int activateLParam() {
        return (WM_LBUTTONDOWN << 16) | HTCLIENT;
    }

    /**
     * RawInput 输入模式（<b>必须前台可见</b>：真实鼠标输入只投给「光标下的窗口」，因此不能后台运行）：
     * 注入<b>系统级真实鼠标输入</b>（{@code SendInput}：绝对移动 → 左键按下 → 抬起），
     * 点完把光标移回原位；目标点被别的窗口压住时先自动化解遮挡（见 {@link #ensureVisible}）。
     *
     * <p>与 {@link #screenClick} 的两点不同：① 注入接口是 {@code SendInput} 而不是遗留的 {@code mouse_event}，
     * 事件会进入系统输入链，认 RawInput / DirectInput（或轮询 {@code GetCursorPos}）的程序也能收到；
     * ② 目标点本来就可见时不调 SetForegroundWindow、不做前台确认，不会因为系统前台锁定 / 被别的窗口抢占而失败或等待。</p>
     *
     * <p>Windows 的鼠标点击语义是「投给光标下的窗口」，所以注入前必须确认目标点仍属于目标窗口：可见就直接注入；
     * 被遮挡就先化解（抬窗 → 抢前台 → 临时置顶 → 临时挪到光标处），点击完成即还原窗口状态；全部失败才取消
     * 本次点击并说明挡着的是谁（不把真实鼠标输入发到别的窗口上）。</p>
     */
    private Result rawInputClick(WindowInfo window, int x, int y) {
        WinDef.HWND hwnd = window.getHwnd();
        WinDef.HWND targetRoot = topRoot(hwnd);
        Exposure exposure = ensureVisible(hwnd, x, y, targetRoot);
        if (!exposure.visible) {
            return new Result(false, MODE_RAW_INPUT,
                    "本次点击已取消：" + exposure.reason
                            + "。RawInput 输入是系统真实鼠标输入，Windows 只按「光标下的窗口」投递，看不见目标就只会"
                            + "点到上层窗口，所以这里不做乱点；可把目标窗口移到可见处，或改用「MuMu 模拟器」。",
                    x, y, -1, -1);
        }
        // 化解遮挡可能挪过窗口：屏幕坐标按「当前」外框重算（与 screen 模式同一套换算）
        WinDef.POINT outer = windowOuterOrigin(hwnd);
        if (outer == null) {
            restoreAfterExpose(hwnd, targetRoot, exposure);
            return new Result(false, MODE_RAW_INPUT, "无法读取窗口屏幕位置（窗口可能已销毁）", x, y, -1, -1);
        }
        int sx = outer.x + x, sy = outer.y + y;
        User32Mouse u = User32Mouse.INSTANCE;
        boolean foreground = sameWindow(topRoot(u.GetForegroundWindow()), targetRoot);
        WinDef.POINT origin = new WinDef.POINT();
        boolean originOk = u.GetCursorPos(origin);
        try {
            if (!rawInputMove(sx, sy)) {
                return new Result(false, MODE_RAW_INPUT, "SendInput 注入鼠标移动失败（无法把光标移到目标屏幕点）",
                        x, y, sx, sy);
            }
            String landNote = cursorLandNote(sx, sy);
            sleep(RAW_INPUT_MOVE_SETTLE_MS);
            if (!rawInputButton(MOUSEEVENTF_LEFTDOWN)) {
                return new Result(false, MODE_RAW_INPUT, "SendInput 注入左键按下失败（系统未接受该事件）", x, y, sx, sy);
            }
            sleep(RAW_INPUT_CLICK_GAP_MS);
            if (!rawInputButton(MOUSEEVENTF_LEFTUP)) {
                return new Result(false, MODE_RAW_INPUT, "SendInput 注入左键抬起失败（系统未接受该事件）", x, y, sx, sy);
            }
            String back = "";
            if (originOk) {
                sleep(RAW_INPUT_BACK_MS);   // 等目标窗口处理完「抬起」再移走光标，避免归位移动被它当成拖拽
                back = rawInputMove(origin.x, origin.y)
                        ? "，点击后鼠标已移回原位 (" + origin.x + ", " + origin.y + ")"
                        : "（提示：点击已生效，但鼠标移回原位 (" + origin.x + ", " + origin.y + ") 失败）";
            }
            return new Result(true, MODE_RAW_INPUT,
                    "已用系统真实鼠标输入（SendInput）在屏幕坐标 (" + sx + ", " + sy + ") 注入一次左键点击"
                            + (foreground ? "，点击时该窗口已在前台"
                            : "，点击前该窗口不在前台（Windows 会按鼠标点击语义把它激活）")
                            + exposure.how + landNote + back,
                    x, y, sx, sy);
        } finally {
            restoreAfterExpose(hwnd, targetRoot, exposure);
        }
    }

    /**
     * 让目标点重新可见（RawInput 输入的前提）：Windows 的鼠标输入按命中测试投递，目标点被别的窗口压住就点不到，
     * 这里按「先温和、后强硬」逐级化解遮挡，每级之后都重新确认「该屏幕点是否已归到目标窗口名下」：
     * <ol>
     *   <li>把窗口抬到同类窗口之前（不抢焦点、不激活）—— 应付「只是被普通窗口压住」；</li>
     *   <li>抢一次前台（复用 {@link #raiseToForeground}：F22 解锁 + SetForegroundWindow）—— 应付系统前台锁定；</li>
     *   <li>临时设为置顶窗口 —— 应付被置顶窗口（播放器 / 悬浮窗）压住；</li>
     *   <li>临时把窗口挪到光标所在处，让目标点正好落在光标下 —— 等价 MaaFramework 的 {@code *WithWindowPos} 思路。</li>
     * </ol>
     * 全程只在需要时动窗口，且 {@link #restoreAfterExpose} 会把置顶 / 位置还原；全部失败（或
     * {@code execute.rawinput-auto-expose} 关闭）时不留副作用地返回失败，交给调用方取消本次点击。
     *
     * @return 目标点是否已可见，以及点击完成后需要还原的窗口状态
     */
    private Exposure ensureVisible(WinDef.HWND hwnd, int x, int y, WinDef.HWND targetRoot) {
        User32Mouse u = User32Mouse.INSTANCE;
        WinDef.HWND blocker = blockerAt(hwnd, x, y);
        if (sameWindow(blocker, targetRoot)) {
            return Exposure.unchanged();
        }
        if (blocker == null) {
            return Exposure.blocked("目标屏幕点上没有任何窗口（窗口可能已移出屏幕、已最小化或已关闭）");
        }
        if (!executeProperties.isRawinputAutoExpose()) {
            return Exposure.blocked("目标屏幕点当前被窗口「" + windowTitle(blocker) + "」遮挡"
                    + "（execute.rawinput-auto-expose 已关闭，不做抬窗 / 挪窗尝试）");
        }
        String blockedBy = windowTitle(blocker);
        boolean topmost = false;
        WinDef.RECT movedFrom = null;
        u.SetWindowPos(targetRoot, zOrder(HWND_TOP_VALUE), 0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER);
        if (awaitVisible(hwnd, x, y, targetRoot)) {
            return Exposure.exposed(blockedBy, "把窗口抬到其他窗口之前（未抢焦点）", movedFrom, topmost);
        }
        raiseToForeground(targetRoot);
        if (awaitVisible(hwnd, x, y, targetRoot)) {
            return Exposure.exposed(blockedBy, "临时把窗口切到前台", movedFrom, topmost);
        }
        u.SetWindowPos(targetRoot, zOrder(HWND_TOPMOST_VALUE), 0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER);
        topmost = true;
        if (awaitVisible(hwnd, x, y, targetRoot)) {
            return Exposure.exposed(blockedBy, "临时把窗口设为置顶", movedFrom, topmost);
        }
        WinDef.POINT cursor = new WinDef.POINT();
        WinDef.RECT rect = new WinDef.RECT();
        if (u.GetCursorPos(cursor) && u.GetWindowRect(hwnd, rect)
                && u.SetWindowPos(hwnd, zOrder(HWND_TOP_VALUE), cursor.x - x, cursor.y - y, 0, 0,
                SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER)) {
            movedFrom = rect;
            if (awaitVisible(hwnd, x, y, targetRoot)) {
                return Exposure.exposed(blockedBy, "临时把窗口挪到光标处（该点上没有别的窗口）", movedFrom, topmost);
            }
        }
        restoreWindowState(hwnd, targetRoot, movedFrom, topmost);   // 全部失败：不留副作用
        return Exposure.blocked("目标屏幕点仍被窗口「" + windowTitle(blockerAt(hwnd, x, y)) + "」遮挡"
                + "（已依次尝试抬窗 / 抢前台 / 置顶 / 挪窗）");
    }

    /** 目标屏幕点当前压在最上面的顶层窗口（null = 该点上没有任何窗口，或读不到窗口位置）。 */
    private WinDef.HWND blockerAt(WinDef.HWND hwnd, int x, int y) {
        WinDef.POINT outer = windowOuterOrigin(hwnd);
        if (outer == null) {
            return null;
        }
        PointByValue point = new PointByValue();
        point.x = outer.x + x;
        point.y = outer.y + y;
        return topRoot(User32Mouse.INSTANCE.WindowFromPoint(point));
    }

    /** 复核「目标点是否已归到目标窗口名下」：窗口的抬升 / 挪位是异步生效的，先稳定一小会儿再轮询几次。 */
    private boolean awaitVisible(WinDef.HWND hwnd, int x, int y, WinDef.HWND targetRoot) {
        sleep(EXPOSE_SETTLE_MS);
        for (int i = 0; i < EXPOSE_RETRY_TIMES; i++) {
            if (sameWindow(blockerAt(hwnd, x, y), targetRoot)) {
                return true;
            }
            sleep(EXPOSE_RETRY_MS);
        }
        return false;
    }

    /** 还原为化解遮挡改动过的窗口状态（点击完成后调用；没动过就什么都不做）。 */
    private void restoreAfterExpose(WinDef.HWND hwnd, WinDef.HWND targetRoot, Exposure exposure) {
        restoreWindowState(hwnd, targetRoot, exposure.restoreRect, exposure.restoreTopmost);
    }

    /** 还原窗口位置（{@code restoreRect} 非 null 时）与置顶状态（{@code restoreTopmost} 为真时）：先移回原位再取消置顶。 */
    private void restoreWindowState(WinDef.HWND hwnd, WinDef.HWND targetRoot, WinDef.RECT restoreRect, boolean restoreTopmost) {
        User32Mouse u = User32Mouse.INSTANCE;
        if (restoreRect != null) {
            u.SetWindowPos(hwnd, zOrder(HWND_TOP_VALUE), restoreRect.left, restoreRect.top, 0, 0,
                    SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER);
        }
        if (restoreTopmost) {
            u.SetWindowPos(targetRoot, zOrder(HWND_NOTOPMOST_VALUE), 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER);
        }
    }

    /** {@code SetWindowPos} 的 z 序句柄（{@code HWND_TOP} / {@code HWND_TOPMOST} / {@code HWND_NOTOPMOST} 用数值常量表示）。 */
    private static WinDef.HWND zOrder(int value) {
        return new WinDef.HWND(Pointer.createConstant(value));
    }

    /**
     * RawInput 输入「化解遮挡」的结果：目标点是否已可见、用了什么手段、点击完成后要还原窗口的哪些状态。
     */
    private static final class Exposure {
        /** 目标点是否已确认归到目标窗口名下 */
        private final boolean visible;
        /** 可见且动过窗口时，补进结果消息的说明（没动窗口时为空串） */
        private final String how;
        /** 不可见时的原因（可见时为空串） */
        private final String reason;
        /** 需要把窗口移回这个外框位置（null = 没挪过） */
        private final WinDef.RECT restoreRect;
        /** 需要取消 {@code HWND_TOPMOST} 临时置顶 */
        private final boolean restoreTopmost;

        private Exposure(boolean visible, String how, String reason, WinDef.RECT restoreRect, boolean restoreTopmost) {
            this.visible = visible;
            this.how = how;
            this.reason = reason;
            this.restoreRect = restoreRect;
            this.restoreTopmost = restoreTopmost;
        }

        /** 目标点本来就在目标窗口上（不改窗口任何状态）。 */
        private static Exposure unchanged() {
            return new Exposure(true, "", "", null, false);
        }

        /** 化解遮挡成功：点完由调用方还原 {@code restoreRect} / 置顶状态。 */
        private static Exposure exposed(String blockedBy, String how, WinDef.RECT restoreRect, boolean restoreTopmost) {
            return new Exposure(true, "，目标点原被窗口「" + blockedBy + "」遮挡，已" + how + "（点完已还原）",
                    "", restoreRect, restoreTopmost);
        }

        private static Exposure blocked(String reason) {
            return new Exposure(false, "", reason, null, false);
        }
    }

    /** 注入一次绝对移动：dx/dy 按整个虚拟桌面归一化（多显示器下也能落到正确屏幕）。 */
    private boolean rawInputMove(int sx, int sy) {
        User32Mouse u = User32Mouse.INSTANCE;
        int vx = u.GetSystemMetrics(SM_XVIRTUALSCREEN);
        int vy = u.GetSystemMetrics(SM_YVIRTUALSCREEN);
        int vw = Math.max(1, u.GetSystemMetrics(SM_CXVIRTUALSCREEN) - 1);
        int vh = Math.max(1, u.GetSystemMetrics(SM_CYVIRTUALSCREEN) - 1);
        Input in = new Input();
        in.type = INPUT_MOUSE;
        in.mi.dx = clampAbsolute((int) Math.round((sx - vx) * (double) ABSOLUTE_RANGE / vw));
        in.mi.dy = clampAbsolute((int) Math.round((sy - vy) * (double) ABSOLUTE_RANGE / vh));
        in.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
        return sendInput(in);
    }

    /** 注入一次鼠标按键（按下或抬起）：不带 MOVE 标志 = 在注入后的光标位置生效。 */
    private boolean rawInputButton(int flag) {
        Input in = new Input();
        in.type = INPUT_MOUSE;
        in.mi.dwFlags = flag;
        return sendInput(in);
    }

    /** 注入一条鼠标 INPUT；系统拒绝时记日志（含 cbSize 与 GetLastError）并返回 false。 */
    private boolean sendInput(Input in) {
        int cbSize = in.size();
        int sent = User32Mouse.INSTANCE.SendInput(1, new Input[]{in}, cbSize);
        if (sent == 1) {
            return true;
        }
        log.warn("SendInput 注入失败：被接受 {} 条（cbSize={}, GetLastError={}）",
                sent, cbSize, Kernel32.INSTANCE.GetLastError());
        return false;
    }

    private static int clampAbsolute(int value) {
        return Math.max(0, Math.min(ABSOLUTE_RANGE, value));
    }

    /**
     * 回读注入后的光标位置并与目标点核对：不一致时给一句提示（多因显示器缩放 / DPI 缩放让 Java 侧读到的
     * 窗口坐标与实际像素对不上），一致则返回空串。
     */
    private String cursorLandNote(int sx, int sy) {
        WinDef.POINT p = new WinDef.POINT();
        if (!User32Mouse.INSTANCE.GetCursorPos(p) || (Math.abs(p.x - sx) <= 1 && Math.abs(p.y - sy) <= 1)) {
            return "";
        }
        log.warn("SendInput 移动后光标落在 ({}, {})，与目标屏幕点 ({}, {}) 不一致", p.x, p.y, sx, sy);
        return "（提示：注入后光标落在 (" + p.x + ", " + p.y + ")，与目标点相差 "
                + Math.abs(p.x - sx) + "/" + Math.abs(p.y - sy) + " 像素，多为显示器缩放 / DPI 缩放所致）";
    }

    /** 窗口标题（只在错误提示里说明「点到谁身上了」）；读不到时给占位文本。 */
    private static String windowTitle(WinDef.HWND hwnd) {
        if (hwnd == null || hwnd.getPointer() == null) {
            return "未知窗口";
        }
        char[] buffer = new char[TITLE_MAX_CHARS];
        int length = User32Mouse.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
        return length > 0 ? new String(buffer, 0, length) : "未命名窗口";
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
        // 记录点击前光标所在屏幕位置：点击完成后移回原处，避免把用户鼠标留在目标窗口画面上
        WinDef.POINT origin = new WinDef.POINT();
        boolean originOk = m.GetCursorPos(origin);
        boolean moved = m.SetCursorPos(sx, sy);
        if (!moved) {
            return new Result(false, MODE_SCREEN, "SetCursorPos 失败（无法移动鼠标到目标屏幕点）",
                    x, y, sx, sy);
        }
        sleep(50);
        m.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, null);
        sleep(60);
        m.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, null);
        String back = "";
        if (originOk) {
            sleep(20);   // 等目标窗口完整处理完「抬起」事件再把光标移走，避免归位移动被误判为拖拽
            back = m.SetCursorPos(origin.x, origin.y)
                    ? "，点击后鼠标已移回原位 (" + origin.x + ", " + origin.y + ")"
                    : "（提示：点击已生效，但鼠标移回原位 (" + origin.x + ", " + origin.y + ") 失败）";
        }
        return new Result(true, MODE_SCREEN,
                "前台焦点已确认归「" + window.getTitle() + "」，已在屏幕坐标 (" + sx + ", " + sy + ") 模拟鼠标左键点击" + back,
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
