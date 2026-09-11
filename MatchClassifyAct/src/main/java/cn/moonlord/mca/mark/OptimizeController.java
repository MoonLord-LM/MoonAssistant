package cn.moonlord.mca.mark;

import cn.moonlord.mca.act.OptimizeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 算法调优（自动寻优匹配算法参数——五族权重）：状态 / 启动后台搜索 / 应用结果 / 恢复默认。
 */
@RestController
@RequestMapping("/api/optimize")
@RequiredArgsConstructor
public class OptimizeController {

    private final OptimizeService optimize;

    /** 状态总览：任务进度 + 当前/默认权重 + 最近一次结果 + 各 kind 生效范围（供界面轮询）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return optimize.status();
    }

    /** 启动一次寻优：step = 权重粗搜步长（默认 10），seeds = 随机起点数（默认 24）。 */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody(required = false) Map<String, Object> body) {
        int step = intOf(body == null ? null : body.get("step"), 10);
        int seeds = intOf(body == null ? null : body.get("seeds"), 24);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", optimize.start(step, seeds));
        return out;
    }

    /** 应用一组权重到运行时并持久化（重启后继续生效）。 */
    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody(required = false) Map<String, Object> body) {
        List<?> raw = body == null ? null
                : body.get("weights") instanceof List<?> l ? l : null;
        if (raw == null || raw.size() != OptimizeService.FAMILY_LABELS.size()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "weights 须为 5 个非负整数");
            return out;
        }
        int[] w = new int[raw.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = Integer.parseInt(String.valueOf(raw.get(i)));
        }
        return optimize.apply(w);
    }

    /** 恢复默认五族权重（运行时 + 删除持久化文件）。 */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        return optimize.reset();
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return def;
    }
}
