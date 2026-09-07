package cn.moonlord.mca.mark;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 汇总分析（同「分类标注（state）+ 匹配动作」截图像素分析）展示接口：
 * 生成对照图供人工目检与执行识别——交集图按覆盖率阈值分 90/80/70/60/50 五档，连同
 * 多数 max / 均值 avg / 去重均值 dedup-avg / major8·avg8·dedup-avg8·major32·avg32·dedup-avg32
 * 块降采样共 14 张基础合成图（全部参与识别比对）；
 * 每张基础图各对应一张 -unique 独有区图（same90-unique / same80-unique / same70-unique /
 * same60-unique / same50-unique / max-unique / avg-unique / dedup-avg-unique / major8-unique·
 * avg8-unique·dedup-avg8-unique·major32-unique·avg32-unique·dedup-avg32-unique），共 14 张，
 * 全部参与识别比对。
 * 点击动作且有坐标的分类另生成 10 张点击区交集图（以点击坐标为中心的 1/8、1/32 方框交集小图 ×
 * 交集五档 click8/32-same90/80/70/60/50，全部参与识别比对；无 -unique 版、不参与独有区互比）。
 * 独有区图在基础图上剔除「其它分类同 kind 基础图同像素同色」的区域，
 * 是跨分类产物、等全部分组的基础图生成完后才统一计算，与该分类适用的全部对照图一起构成
 * 固定比对维度参与执行模式 / 智能分析的匹配：无点击坐标分类 14 基础 + 14 -unique = 28 张，
 * 点击动作分类另需 10 张点击区交集图 = 38 张（见 {@link FrameClassifier}；缺图目录待后台重算补齐后自动恢复）。
 *
 * <pre>
 *   GET  /api/annotate/think/groups                         分类分组总览（样本数 / 是否已分析 / 覆盖率 / 产物目录名 dir / 点击区交集图齐全标记 hasClick / 低档齐全 hasClickLow）
 *   POST /api/annotate/think/analyze                        启动异步分析 {force?} → {taskId}（重算 summary/&lt;分类标注&gt;/ 下各基础图与点击区图，随后补 -unique 独有区图）
 *   POST /api/annotate/think/rebuild                        一键重建：清空 summary/ 全部产物后全量重算 → {taskId}
 *   GET  /api/annotate/think/task/{taskId}                  轮询进度（running/done/error）
 *   GET  /api/annotate/think/img/{kind}?dir=…               取对应分类产物目录（kind = 图之一：same90|same90-unique|same80|same80-unique|same70|same70-unique|same60|same60-unique|same50|same50-unique|max|max-unique|avg|avg-unique|dedup-avg|dedup-avg-unique|major8|major8-unique|avg8|avg8-unique|dedup-avg8|dedup-avg8-unique|major32|major32-unique|avg32|avg32-unique|dedup-avg32|dedup-avg32-unique|click8-same90|click8-same80|click8-same70|click8-same60|click8-same50|click32-same90|click32-same80|click32-same70|click32-same60|click32-same50；
 *                                                            dir = 分类标注的 UTF-8 再 Base64，纯 ASCII）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/annotate/think")
public class ThinkController {

    private final ThinkService thinkService;

    public ThinkController(ThinkService thinkService) {
        this.thinkService = thinkService;
    }

    public record AnalyzeRequest(boolean force) {
    }

    /** 分类分组总览 */
    @GetMapping("/groups")
    public List<Map<String, Object>> groups() {
        return thinkService.groups();
    }

    /** 启动批量异步分析（未分析或样本有变的组合 → 全部补齐/重算；有 ≥1 张样本即可分析） */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody(required = false) AnalyzeRequest req) {
        boolean force = req != null && req.force();
        String taskId = thinkService.startAnalyze(force);
        return ResponseEntity.ok(Map.of("taskId", taskId, "force", force));
    }

    /** 一键重建：先清空 summary/ 全部产物再全量重算（前端「重新生成全部对照图」按钮；清场动作在串行计算池内执行） */
    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild() {
        String taskId = thinkService.startRebuild();
        return ResponseEntity.ok(Map.of("taskId", taskId));
    }

    /** 轮询分析任务 */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<?> task(@PathVariable String taskId) {
        ThinkService.Task t = thinkService.task(taskId);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "任务不存在"));
        }
        return ResponseEntity.ok(t);
    }

    /** 智能建议：把一张未标注截图与各分类标注的七张对照图异步逐像素比对 → {taskId}，随后轮询 /suggest/task/{taskId} */
    @PostMapping("/suggest")
    public ResponseEntity<?> suggest(@RequestBody(required = false) Map<String, String> body) {
        String file = (body == null ? "" : String.valueOf(body.getOrDefault("file", ""))).trim();
        String taskId = thinkService.startSuggest(file);
        return ResponseEntity.ok(Map.of("taskId", taskId, "file", file));
    }

    /** 轮询单图智能建议任务（done 时携带按相似度排序的候选组） */
    @GetMapping("/suggest/task/{taskId}")
    public ResponseEntity<?> suggestTask(@PathVariable String taskId) {
        ThinkService.SuggestTask t = thinkService.suggestTask(taskId);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "任务不存在"));
        }
        return ResponseEntity.ok(t);
    }

    /** 读取分析产物 PNG（kind = 类头 19 种图之一；dir = 分类标注产物目录名做 UTF-8 → Base64 后传入，避免容器字符集差异） */
    @GetMapping("/img/{kind}")
    public ResponseEntity<?> image(@PathVariable String kind, @RequestParam String dir) {
        String folder = decodeDir(dir);
        if (folder == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dir 参数非法"));
        }
        Path p = thinkService.resolveArtifact(kind, folder);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "图片不存在"));
        }
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
            .body(new FileSystemResource(p.toFile()));
    }

    private String decodeDir(String dir) {
        if (dir == null || dir.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(dir), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
