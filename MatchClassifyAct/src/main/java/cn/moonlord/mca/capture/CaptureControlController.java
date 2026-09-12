package cn.moonlord.mca.capture;

import cn.moonlord.mca.config.CaptureProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 截图任务开关：截图默认不开启，由控制台右上角「自动采集 / 暂停采集」手动控制
 * （开启=resume，暂停=pause）。
 *
 * <p>仅停/启 {@link WindowCaptureTask} 的保存动作，不影响控制台其它功能；
 * 退出整个服务仍走 {@code /api/system/shutdown}。
 * 另 {@code POST /manual} 可独立于截图开关立即手动采集一帧（标注模式「未标注」空列表的
 * 「手动采集」按钮），见 {@link #manual()}。</p>
 */
@RestController
@RequestMapping("/api/capture")
@RequiredArgsConstructor
public class CaptureControlController {

    private final WindowCaptureTask windowCaptureTask;
    private final CaptureProperties properties;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "paused", windowCaptureTask.isPaused(),
                "intervalMs", properties.getIntervalMs(),
                "diffThresholdPercent", properties.getDiffThresholdPercent());
    }

    @PostMapping("/pause")
    public Map<String, Object> pause() {
        windowCaptureTask.setPaused(true);
        return Map.of("ok", true, "paused", true);
    }

    @PostMapping("/resume")
    public Map<String, Object> resume() {
        windowCaptureTask.setPaused(false);
        return Map.of("ok", true, "paused", false);
    }

    /** 立即手动采集一帧：截取目标窗口画面，与 resource/capture/ + resource/classify/ 全部同尺寸 PNG 逐像素比对做去重
     *  （不一致像素占比须 &gt; {@code capture.diff-threshold-manual-percent}，同执行模式
     *  「存到待标注」），通过则存为 resource/capture/ 新截图返回文件名（供「未标注」列表直接标注）；被拦 /
     *  失败返回对应 kind 与 message。与右上角「自动采集」开关独立，暂停中也能点采一帧。 */
    @PostMapping("/manual")
    public Map<String, Object> manual() {
        WindowCaptureTask.ManualShotResult r = windowCaptureTask.manualShot();
        if (r == null) {
            return res(false, "busy", "上一轮截图仍在进行中，请稍候再试。");
        }
        switch (r.kind) {
            case "saved": {
                Map<String, Object> ok = res(true, "saved", null);
                ok.put("name", r.name);
                if (r.minDiffPercent >= 0) {
                    ok.put("minDiffPercent", r.minDiffPercent);   // 新画面与库内最接近一张的差异，供成功提示展示
                }
                return ok;
            }
            case "dup": {
                String who = r.refState != null
                        ? "分类「" + r.refState + "」的样本「" + r.name + "」"
                        : "截图「" + r.name + "」";
                Map<String, Object> m = res(false, "dup",
                        "当前画面与" + who + "仅 " + pct(r.diffPercent)
                                + "% 像素点不同（≤ " + pct(r.threshold) + "% 阈值，视为同一画面），本次未保存。");
                m.put("dupOf", r.name);
                m.put("diffPercent", r.diffPercent);
                m.put("threshold", r.threshold);
                return m;
            }
            case "window-not-found":
                return res(false, r.kind, "未找到目标窗口（关键字：" + properties.getWindowKeywords()
                        + "），请先打开模拟器/游戏窗口再试。");
            case "minimized":
                return res(false, r.kind, "目标窗口已最小化，无法截图。请先还原窗口再试。");
            case "capture-fail":
                return res(false, r.kind, "窗口截图失败（不可捕获或抓帧超时），请稍后重试。");
            case "resize-fail":
                return res(false, r.kind, "截图无法调整到目标尺寸 " + properties.getResizeWidth() + "x"
                        + properties.getResizeHeight() + "（窗口被锁定尺寸/最小化/未完整显示在屏幕内等），本次未保存。");
            case "save-fail":
                return res(false, r.kind, "截图保存失败，本次未保存。请重试或查看日志。");
            case "busy":
                return res(false, r.kind, "上一轮截图仍在进行中，请稍候再试。");
            default:
                return res(false, r.kind == null ? "error" : r.kind, "手动采集失败，请查看日志后重试。");
        }
    }

    private static Map<String, Object> res(boolean ok, String kind, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("kind", kind);
        if (message != null) {
            m.put("message", message);
        }
        return m;
    }

    /** 差异/阈值百分比展示文本：整数直显，非整数两位小数并去尾零（0.5 → "0.5"、0.98 → "0.98"） */
    private static String pct(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        String s = String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? String.valueOf((long) v) : s;
    }
}
