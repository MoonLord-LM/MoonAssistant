package cn.moonlord.mca.mark;

import cn.moonlord.mca.act.OptimizeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 算法调优（把特征组合成匹配算法并验证分类准确率）：状态 / 启动一次「全部算法」验证 / 启动一次「自动调整参数」。
 */
@RestController
@RequestMapping("/api/optimize")
@RequiredArgsConstructor
public class OptimizeController {

    private final OptimizeService optimize;

    /** 状态总览：前置特征验证情况 + 算法及特征（基础分 X）+ 任务进度 + 最近结果（供界面轮询）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return optimize.status();
    }

    /** 启动一次验证：weights = {算法 id: [权重 Y...]}（与算法特征顺序一一对应，缺省 1）。 */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!optimize.ready()) {
            out.put("started", false);
            out.put("error", "请先在「特征验证」视图完成一次验证（算法由验证结果组合而来）");
            return out;
        }
        out.put("started", optimize.start(parseWeights(body)));
        return out;
    }

    /**
     * 启动一次「自动调整参数」：weights = 起点权重（与 /start 同格式，缺省 1）。
     * 只有「验证所有算法」跑完、且结果对得上当前样本 / 产物时才受理（单一特征算法不参与调整）。
     */
    @PostMapping("/auto")
    public Map<String, Object> auto(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!optimize.tunable()) {
            out.put("started", false);
            out.put("error", "请先点「验证所有算法」并等它跑完（结果要能对上当前的样本 / 产物）");
            return out;
        }
        out.put("started", optimize.autoStart(parseWeights(body)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Double>> parseWeights(Map<String, Object> body) {
        Map<String, List<Double>> out = new LinkedHashMap<>();
        if (body == null || !(body.get("weights") instanceof Map<?, ?> raw)) {
            return out;
        }
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (!(e.getValue() instanceof List<?> list)) {
                continue;
            }
            List<Double> ys = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Number n) {
                    ys.add(n.doubleValue());
                } else {
                    try {
                        ys.add(Double.parseDouble(String.valueOf(o).trim()));
                    } catch (NumberFormatException ex) {
                        ys.add(1.0);
                    }
                }
            }
            out.put(String.valueOf(e.getKey()), ys);
        }
        return out;
    }
}
