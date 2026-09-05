package cn.moonlord.mca.ui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * 程序启动就绪后自动打开控制台网页。
 *
 * <p>优先以「应用窗口」方式启动本机的 Chromium 内核浏览器（Edge / Chrome），
 * 这种窗口里的页面允许脚本调用 {@code window.close()} 自关——
 * 配合页面在退出时自动关闭，实现「退出程序 → 网页自己关掉」。
 * 找不到 Edge/Chrome 时回退 {@link Desktop#browse} 用系统默认浏览器打开
 * （普通标签页受浏览器策略限制无法脚本自关，页面会显示手动关闭按钮兜底）。</p>
 */
@Slf4j
@Component
public class BrowserLauncher implements ApplicationListener<ApplicationReadyEvent> {

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
            return;
        }
        log.warn("未找到 Edge/Chrome，改用系统默认浏览器打开控制台：{}", url);
        openViaDesktop(url);
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
                new ProcessBuilder(exe, "--app=" + url)
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
