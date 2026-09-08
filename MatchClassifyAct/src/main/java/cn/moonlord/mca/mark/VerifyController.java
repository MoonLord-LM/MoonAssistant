package cn.moonlord.mca.mark;

import cn.moonlord.mca.act.VerifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 特征验证（汇总图算法自回归评分）：列出各 kind 的 A/B 评分与分类级明细、启动/轮询后台验证任务。
 */
@RestController
@RequestMapping("/api/verify")
@RequiredArgsConstructor
public class VerifyController {

    private final VerifyService verify;

    /** 全部 kind 的评分状态 + 任务进度（供左侧算法列表与右侧分值区轮询）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return verify.status();
    }

    /** 启动一次完整验证（全部 kind 顺序计算）；已有一个任务在跑时拒绝。 */
    @PostMapping("/start")
    public Map<String, Object> start() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", verify.start());
        return out;
    }

    /** 某 kind 的分类级明细（未计算过返回 null；fresh 标识结果是否与当前样本/产物一致）。 */
    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam(required = false) String kind) {
        return verify.detail(kind);
    }
}
