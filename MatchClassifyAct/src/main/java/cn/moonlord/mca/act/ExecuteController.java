package cn.moonlord.mca.act;

import cn.moonlord.mca.config.ExecuteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行模式 API：实时画面识别 + 一键执行动作（供 index.html 的「执行模式」页驱动）。
 *
 * <ul>
 *   <li>{@code GET  /api/execute/status} —— 执行循环开关与参数；</li>
 *   <li>{@code POST /api/execute/start|stop} —— 开/关后台「截图→识别」循环；</li>
 *   <li>{@code POST /api/execute/refresh} —— 立即截图并识别一次；</li>
 *   <li>{@code GET  /api/execute/latest} —— 最近一次识别结果快照；</li>
 *   <li>{@code GET  /api/execute/frame} —— 最近快照对应的画面 PNG（供 <img> 展示）；</li>
 *   <li>{@code POST /api/execute/act} —— 触发执行：复核最新画面后向目标窗口发送鼠标点击；</li>
 *   <li>{@code POST /api/execute/save-to-capture} —— 把当前画面另存为 capture/ 的原始截图
 *       （未标注，供切回标注模式人工精确标注/修正坐标）。保存前会与 capture/ + classify/ 全部
 *       截图按手动另存阈值 {@code capture.diff-threshold-manual-percent}（默认 0.3%）做去重比对：
 *       与某张差异低于阈值判为重复画面，拒绝另存并返回 kind=dup。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/execute")
public class ExecuteController {

    private final ExecutionService executionService;
    private final ExecuteProperties executeProperties;

    /** 执行循环当前开关与可调参数。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", executionService.isRunning());
        m.put("intervalMs", executeProperties.getIntervalMs());
        m.put("thresholdPercent", executeProperties.getMatchThresholdPercent());
        m.put("clickMode", executionService.getClickMode());
        return m;
    }

    /** 开启后台执行循环（立即跑第一轮）。 */
    @PostMapping("/start")
    public Map<String, Object> start() {
        executionService.start();
        return status();
    }

    /** 停止后台执行循环（最后一次识别结果保留）。 */
    @PostMapping("/stop")
    public Map<String, Object> stop() {
        executionService.stop();
        return status();
    }

    /** 立即截图并识别一次（后台循环未开启时也能用）。 */
    @PostMapping("/refresh")
    public ExecutionService.Snapshot refresh() {
        return executionService.refreshNow();
    }

    /** 最近一次识别结果快照。 */
    @GetMapping("/latest")
    public ExecutionService.Snapshot latest() {
        return executionService.latestSnapshot();
    }

    /** 最近快照对应的画面 PNG。at 参数仅用于浏览器缓存去抖，未命中时返回最新画面。 */
    @GetMapping(value = "/frame", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> frame(@RequestParam(required = false) Long at) {
        byte[] png = executionService.pngOfLatest();
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    /** 触发执行：复核最新画面后向目标窗口发送鼠标左键点击。 */
    @PostMapping("/act")
    public Map<String, Object> act() {
        return executionService.act();
    }

    /** 实时切换鼠标点击方式（post=后台消息 / screen=前台点击）。 */
    @PostMapping("/click-mode")
    public Map<String, Object> clickMode(@RequestBody(required = false) Map<String, String> body) {
        executionService.setClickMode(body == null ? null : body.get("mode"));
        return status();
    }

    /** 快速标记：把最近一次识别画面另存为指定分类的新样本（识别错了 → 立即纠正，减少后续误判）。
     *  保存前按手动另存阈值（默认 0.3%）做与 capture/ + classify/ 全图的重复比对，几乎重复即拒绝（kind=dup）。 */
    @PostMapping("/mark")
    public Map<String, Object> mark(@RequestBody(required = false) Map<String, String> body) {
        return executionService.markFrameAsSample(body == null ? null : body.get("state"));
    }

    /** 把最近一次识别画面另存为 capture/ 下的原始截图（未标注）：需人工精确标注 / 修正坐标时，
     *  先放入截图区，再到「标注模式 → 未标注」按正常流程标注。 */
    @PostMapping("/save-to-capture")
    public Map<String, Object> saveToCapture() {
        return executionService.saveFrameToCapture();
    }
}
