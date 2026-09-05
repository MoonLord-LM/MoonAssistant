package cn.moonlord.mca.mark;

import cn.moonlord.mca.capture.WindowCaptureTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用版本探针：控制台前端周期性轮询 {@code /api/app/meta}，
 * 比对 {@code codeTs}——服务端重新打包部署（代码更新）后数值变化，
 * 前端据此自动刷新页面以加载新版界面。
 *
 * <p>codeTs 取「最能代表代码构建时间」的文件修改时间：
 * ① 以单个 jar / 目录运行时取其 mtime；② 否则取 classpath 中
 * static/annotate.html 所在部署根（可执行 jar 取其外层 jar 的 mtime）；</p>
 *
 * <p>除版本探针外，meta 还携带截图任务的「自动暂停原因」：
 * 截图 resize 持续无法达标自动停止时，前端据此弹出错误提示。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AppMetaController {

    private final WindowCaptureTask windowCaptureTask;

    private final long startedAt = System.currentTimeMillis();
    private final long codeTs = detectCodeTimestamp();

    @GetMapping("/api/app/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codeTs", codeTs);
        m.put("startedAt", startedAt);
        String reason = windowCaptureTask.getAutoStopReason();
        if (reason != null) {
            m.put("captureStopReason", reason);   // 非空 = 截图任务刚因 resize 持续不成功而自动暂停
        }
        return m;
    }

    private static long detectCodeTimestamp() {
        // 1) classpath 单条目（java -jar 可执行包 / IDE 运行 classes）：直接取该条目 mtime
        String cp = System.getProperty("java.class.path", "");
        if (!cp.isEmpty() && cp.indexOf(java.io.File.pathSeparatorChar) < 0) {
            long t = mtimeOf(cp);
            if (t > 0) return t;
        }
        // 2) 从 static/annotate.html 定位部署根
        try {
            URL u = AppMetaController.class.getClassLoader().getResource("static/annotate.html");
            if (u != null) {
                if ("file".equalsIgnoreCase(u.getProtocol())) {          // 开发目录直接运行
                    long t = mtimeOf(Path.of(u.toURI()).toString());
                    if (t > 0) return t;
                } else if ("jar".equalsIgnoreCase(u.getProtocol())) {    // Spring Boot 可执行包
                    String p = u.getPath();    // jar:file:/…/x.jar!/BOOT-INF/classes!/static/annotate.html
                    int b = p.indexOf("!/");
                    if (b > 0 && p.startsWith("file:")) {
                        long t = mtimeOf(p.substring(5, b));
                        if (t > 0) return t;
                    }
                }
            }
        } catch (Exception ignore) {
            // 回退到启动时刻
        }
        log.warn("无法定位代码部署时间，codeTs 回退为本次启动时刻（此后每次重启都会触发前端刷新）");
        return System.currentTimeMillis();
    }

    private static long mtimeOf(String path) {
        try {
            Path p = Path.of(path);
            if (Files.exists(p)) {
                return Files.getLastModifiedTime(p).toMillis();
            }
        } catch (Exception ignore) {
            // 非文件类路径（如 http classpath），忽略
        }
        return -1L;
    }
}
