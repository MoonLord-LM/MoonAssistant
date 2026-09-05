package cn.moonlord.mca.mark;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 进程级控制：控制台右上角「退出程序」→ 关闭整个服务。
 *
 * <p>周期截图（录屏）任务跑在同一个 Spring Boot 进程里，因此退出 = 结束 JVM。
 * 先让 HTTP 响应返回给页面，稍后 System.exit(0) 会触发注册在
 * ScreenCaptureService 上的关闭钩子，把正在抓帧的 WindowsCapture.exe 一并结束。</p>
 */
@Slf4j
@RestController
public class SystemController {

    /** 给 HTTP 响应留出足够回到页面的时间（毫秒） */
    private static final long RESPONSE_GRACE_MS = 600;
    /** 正常退出（System.exit 及其关闭钩子）超时仍未结束时，强制结束进程（毫秒） */
    private static final long FORCE_KILL_DELAY_MS = 8000;

    @PostMapping("/api/system/shutdown")
    public ResponseEntity<Map<String, Object>> shutdown() {
        log.warn("收到退出请求：控制台服务与周期截图任务即将停止……");

        // 兜底强杀：若某个关闭钩子卡住导致 JVM 迟迟不退出，到点后强制结束进程，保证“点了退出就一定停”。
        Thread force = new Thread(() -> {
            try {
                Thread.sleep(FORCE_KILL_DELAY_MS);
                if (ProcessHandle.current().isAlive()) {
                    log.warn("正常退出流程未在 {}ms 内结束进程，强制结束……", FORCE_KILL_DELAY_MS);
                    ProcessHandle.current().destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "app-exit-force");
        force.setDaemon(true);
        force.start();

        // 正常退出：先让 HTTP 响应返回页面，随后 System.exit 触发清理采集器进程的关闭钩子。
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(RESPONSE_GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.warn("执行退出：结束截图任务并关闭控制台服务");
            System.exit(0);
        }, "app-exit");
        t.setDaemon(true);
        t.start();
        return ResponseEntity.ok(Map.of("ok", true, "message", "服务即将退出"));
    }
}
