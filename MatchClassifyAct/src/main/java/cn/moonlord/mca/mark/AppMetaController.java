package cn.moonlord.mca.mark;

import cn.moonlord.mca.capture.ScreenCaptureService;
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
 * <p>除版本探针外，meta 还携带截图任务的动态提示事件：
 * <ul>
 *   <li>{@code captureStopReason}：截图 resize 持续无法达标自动停止时，前端据此弹出错误提示；</li>
 *   <li>{@code shotNotice}（{at, kind, name, pct?}）：最近一次截图结果——成功保存（kind=saved，
 *       name 为新图文件名）或画面与已保存参考图差异低于阈值被丢弃（kind=dup，name 为参考图文件名，
 *       pct 为画面与该参考图的实际平均像素差异百分比、必然低于阈值）。每轮完成都记录、不节流，
 *       前端每 2s 轮询取走（截图节拍默认约 1s、可能快于轮询，轮询间隙内连续多条只展示最新一条，属单条替换预期行为）并以右下角轻提示即时展示；</li>
 *   <li>{@code savedSeq}：已成功保存截图的总次数。前端每 2 秒轮询 meta，发现它比上次大，
 *       说明刚有新截图落盘，随即静默刷新截图列表（保证截图保存后约 2 秒内界面可见）；</li>
 *   <li>{@code startupDedupNotice}（{at, threshold, scanned, removed}）：本次启动的历史重复清理结果
 *       （自动截图与手动另存两个去重阈值均 ≤ 0 时不执行）——按两者中较大者
 *       （默认 max(3, 1) = 3%）重扫 capture/ + classify/ 全部截图删除重复。不论是否删除了图片，
 *       前端都会据此在右下角提示一次清理完成
 *       （有删除：删除重复 N 张；无删除：检查完成、未发现重复图片）。</li>
 * </ul></p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AppMetaController {

    private final WindowCaptureTask windowCaptureTask;
    private final ScreenCaptureService screenCaptureService;

    private final long startedAt = System.currentTimeMillis();
    private final long codeTs = detectCodeTimestamp();

    @GetMapping("/api/app/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codeTs", codeTs);
        m.put("startedAt", startedAt);
        m.put("savedSeq", windowCaptureTask.getSavedSeq());
        String reason = windowCaptureTask.getAutoStopReason();
        if (reason != null) {
            m.put("captureStopReason", reason);   // 非空 = 截图任务刚因 resize 持续不成功而自动暂停
        }
        WindowCaptureTask.ShotNotice shot = windowCaptureTask.getShotNotice();
        if (shot != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("at", shot.at);
            r.put("kind", shot.kind);
            r.put("name", shot.name);
            if ("dup".equals(shot.kind)) {
                r.put("pct", shot.diffPercent);   // dup：画面与该参考图的实际平均像素差异（%），必然低于阈值；saved 不带
            }
            m.put("shotNotice", r);               // 非空 = 最近一次截图结果（成功保存 / 差异过小丢弃），前端即时轻提示
        }
        // 启动历史重复清理结果：无论是否删除都提示一次，removed 供前端区分「删除重复 N 张 / 检查完成未发现重复」
        ScreenCaptureService.StartupDedupNotice dedup = screenCaptureService.getStartupDedupNotice();
        if (dedup != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("at", dedup.at());
            d.put("threshold", dedup.threshold());
            d.put("scanned", dedup.scanned());
            d.put("removed", dedup.removed());
            m.put("startupDedupNotice", d);
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
