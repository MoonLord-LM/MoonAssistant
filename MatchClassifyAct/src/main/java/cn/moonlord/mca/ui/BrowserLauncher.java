package cn.moonlord.mca.ui;

import cn.moonlord.mca.capture.WindowFinder;
import cn.moonlord.mca.capture.WindowInfo;
import cn.moonlord.mca.capture.WindowResizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 程序启动就绪后自动打开控制台网页。
 *
 * <p>优先以「应用窗口」方式启动本机的 Chromium 内核浏览器（Edge / Chrome），
 * 这种窗口里的页面允许脚本调用 {@code window.close()} 自关——
 * 配合页面在退出时自动关闭，实现「退出程序 → 网页自己关掉」。
 * 找不到 Edge/Chrome 时回退 {@link Desktop#browse} 用系统默认浏览器打开
 * （普通标签页受浏览器策略限制无法脚本自关，页面会显示手动关闭按钮兜底）。</p>
 *
 * <p>应用窗口的初始尺寸与位置由 {@code ui.window-size} / {@code ui.center}
 * 控制（默认 1760×990 并居中展示），通过 Chromium 的
 * {@code --window-size} / {@code --window-position} 参数实现。</p>
 */
@Slf4j
@Component
public class BrowserLauncher implements ApplicationListener<ApplicationReadyEvent> {

    private final WindowFinder windowFinder;
    private final WindowResizer windowResizer;

    public BrowserLauncher(WindowFinder windowFinder, WindowResizer windowResizer) {
        this.windowFinder = windowFinder;
        this.windowResizer = windowResizer;
    }

    /** 控制台页面标题：Chromium 应用窗口的标题栏与页面 <title> 一致，用于启动后定位窗口 */
    private static final List<String> CONSOLE_WINDOW_KEYWORDS = List.of("MCA 控制台");

    /** 常见安装路径的 Edge / Chrome 可执行文件（应用窗口启动优先） */
    private static final String[] CHROMIUM_CANDIDATES = {
            env("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            env("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            env("LOCALAPPDATA") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            env("ProgramFiles") + "\\Google\\Chrome\\Application\\chrome.exe",
            env("ProgramFiles(x86)") + "\\Google\\Chrome\\Application\\chrome.exe",
            env("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
    };

    @Value("${ui.auto-open:true}")
    private boolean autoOpen;

    @Value("${ui.path:/annotate}")
    private String path;

    /** 控制台窗口尺寸，形如 {@code 宽x高}，例如 {@code 1760x990}；{@code 0x0} 表示不指定交给系统 */
    @Value("${ui.window-size:1760x990}")
    private String windowSize;

    /** 控制台窗口是否在屏幕可用区域居中展示 */
    @Value("${ui.center:true}")
    private boolean center;

    @Value("${server.port:8080}")
    private int port;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!autoOpen) {
            log.info("ui.auto-open=false：本次启动不自动打开网页，可手动访问 http://127.0.0.1:{}{}",
                    port, path());
            return;
        }
        String url = "http://127.0.0.1:" + port + path();
        if (openChromiumAppWindow(url)) {
            log.info("已用浏览器应用窗口打开控制台：{}", url);
            enforceConsoleWindowPlacement();   // 窗口出现后再次强制尺寸与居中（Chromium 会记忆上次位置）
            return;
        }
        log.warn("未找到 Edge/Chrome，改用系统默认浏览器打开控制台：{}", url);
        openViaDesktop(url);
    }

    /**
     * 每次启动后强制程序窗口按 ui.window-size / ui.center 摆放一次：
     * 新窗口的出生位置已由 --window-size / --window-position 参数决定（创建即居中）；
     * 这里只作为兜底——Chromium 应用窗口偶尔会沿用上次记忆的尺寸/位置，
     * 窗口出现后直接做一次 SetWindowPos 搬正（最长等待约 12 秒）。
     * 不做「先隐藏再显示」：保持窗口始终可见，避免任务栏图标消失/整窗闪烁。
     */
    private void enforceConsoleWindowPlacement() {
        int[] size = parseWindowSize();
        if (size == null) {
            return; // 0x0：不干预窗口尺寸
        }
        int w = size[0];
        int h = size[1];
        Rectangle work = workArea();
        if (work != null) {
            w = Math.min(w, work.width);
            h = Math.min(h, work.height);
        }
        final int targetW = w;
        final int targetH = h;
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 12_000;
            long delayMs = 10;   // 定位等待递增退避：10 → 20 → 40 → 80 … 封顶 1000ms（窗口未出现时尽快多试几次，之后放宽）
            while (System.currentTimeMillis() < deadline) {
                try {
                    WindowInfo win = windowFinder.findTarget(CONSOLE_WINDOW_KEYWORDS);
                    if (win != null && win.getHwnd() != null) {
                        windowResizer.placeWindow(win.getHwnd(), win.getTitle(), targetW, targetH, center);
                        return;
                    }
                } catch (Throwable e) {
                    log.debug("定位控制台窗口失败：{}", e.toString());
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delayMs = Math.min(delayMs * 2, 1000);
            }
            log.warn("启动后未在 12 秒内定位到控制台窗口「{}」，本次未强制调整尺寸与位置", CONSOLE_WINDOW_KEYWORDS);
        }, "console-window-place");
        t.setDaemon(true);
        t.start();
    }

    private String path() {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v;
    }

    private boolean openChromiumAppWindow(String url) {
        for (String exe : CHROMIUM_CANDIDATES) {
            if (exe.isEmpty() || !new File(exe).isFile()) {
                continue;
            }
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(exe);
                cmd.add("--app=" + url);
                applyWindowPlacement(cmd);
                new ProcessBuilder(cmd)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return true;
            } catch (IOException e) {
                log.debug("尝试用 {} 打开失败：{}", exe, e.getMessage());
            }
        }
        return false;
    }

    /** 把 ui.window-size / ui.center 转成 Chromium 的 --window-size / --window-position 参数 */
    private void applyWindowPlacement(List<String> cmd) {
        int[] size = parseWindowSize();
        if (size == null) {
            return; // 0x0：不干预窗口尺寸，交给系统
        }
        int w = size[0];
        int h = size[1];
        Rectangle work = workArea();
        if (work != null) {
            // 窗口不超出屏幕可用区域
            w = Math.min(w, work.width);
            h = Math.min(h, work.height);
            cmd.add("--window-size=" + w + "," + h);
            if (center) {
                int x = work.x + (work.width - w) / 2;
                int y = work.y + (work.height - h) / 2;
                cmd.add("--window-position=" + x + "," + y);
            }
        } else {
            // 拿不到屏幕信息（如无桌面会话）时仍按指定尺寸打开，位置交给系统
            cmd.add("--window-size=" + w + "," + h);
        }
    }

    /** 解析 ui.window-size（宽x高，大小写不敏感）；非法或 0x0 返回 null 表示不干预 */
    private int[] parseWindowSize() {
        if (windowSize == null) {
            return null;
        }
        String[] part = windowSize.trim().split("[xX]", 2);
        if (part.length != 2) {
            log.warn("ui.window-size 格式不正确（应为 宽x高）：{}，已忽略尺寸设置", windowSize);
            return null;
        }
        try {
            int w = Integer.parseInt(part[0].trim());
            int h = Integer.parseInt(part[1].trim());
            return (w > 0 && h > 0) ? new int[]{w, h} : null;
        } catch (NumberFormatException e) {
            log.warn("ui.window-size 格式不正确（应为 宽x高）：{}，已忽略尺寸设置", windowSize);
            return null;
        }
    }

    /** 屏幕可用区域（扣除任务栏等）；多显示器时取覆盖所有屏的联合区域，拿不到返回 null */
    private Rectangle workArea() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        } catch (Exception e) {
            log.debug("获取屏幕可用区域失败：{}", e.toString());
            return null;
        }
    }

    private void openViaDesktop(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                log.warn("当前环境不支持自动打开网页，请手动访问 {}", url);
            }
        } catch (Exception e) {
            log.warn("自动打开网页失败，请手动访问 {}（原因：{}）", url, e.getMessage());
        }
    }

}
