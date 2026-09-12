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
 * 生成对照图供人工目检与执行识别——交集图分六档：100% = 全部样本像素一致（最严格），其余按覆盖率
 * 阈值 >90/80/70/60/50% 分档，连同多数 max / 均值 avg / 去重均值 dedup-avg / major8·avg8·
 * dedup-avg8·major32·avg32·dedup-avg32 块降采样共 15 张基础合成图（全部参与识别比对）；
 * 每张基础图各对应一张 -unique 独有区图（same100-unique / same90-unique / same80-unique /
 * same70-unique / same60-unique / same50-unique / max-unique / avg-unique / dedup-avg-unique /
 * major8-unique·avg8-unique·dedup-avg8-unique·major32-unique·avg32-unique·dedup-avg32-unique），共 15 张，
 * 全部参与识别比对。
 * 每个分类以注意点（未设 = 屏幕中心）为中心另生成 12 张注意区交集图（1/8、1/32 方框交集小图 × 交集六档
 * attn8/32-same100/90/80/70/60/50，每个分类都有），鼠标点击分类再以鼠标点击点为中心生成 12 张点击区交集图
 * （click8/32-same100/90/80/70/60/50），全部参与识别比对；两套均无 -unique 版、不参与独有区互比。
 * 独有区图在基础图上剔除「其它分类同 kind 基础图同像素同色」的区域，
 * 是跨分类产物、等全部分组的基础图生成完后才统一计算，与该分类适用的全部对照图一起构成
 * 固定比对维度参与执行模式 / 智能分析的匹配：每个分类 15 基础 + 15 -unique + 12 张注意区交集图 = 42 张，
 * 鼠标点击分类另加 12 张点击区交集图 = 54 张
 * （见 {@link FrameClassifier}；历史旧目录缺图 → 待后台重算补齐后自动恢复）。
 *
 * <p>列表首位另有一个不属于任何分类标注的固定「全部」组（{@code all=true}）：取 resource/classify/ 全部已标注截图
 * 合成 12 张专用产物 = 交集图六档 6 张（100% 档公共部分 + 90/80/70/60/50 档样本间稳定区）+ 多数 / 均值 /
 * 去重均值 3 张代表图 + 这 3 族「与代表图差异最大的一张原图」，仅供整体目检，不做分组、不参与识别比对。</p>
 *
 * <pre>
 *   GET  /api/annotate/think/groups                         分类分组总览（样本数 / 是否已分析 / 覆盖率 / 产物目录名 dir / 注意区交集图齐全标记 hasAttn / 低档齐全 hasAttnLow / 点击区齐全 hasClick 与低档 hasClickLow；首位恒为固定「全部」组，带 all=true、dir=_all_、items=12 张专用产物）
 *   POST /api/annotate/think/analyze                        启动异步分析 {force?} → {taskId}（重算 resource/summary/&lt;分类标注&gt;/ 下各基础图与注意区/点击区图，随后补 -unique 独有区图）
 *   POST /api/annotate/think/rebuild                        一键重建：清空 resource/summary/ 全部产物后全量重算 → {taskId}
 *   GET  /api/annotate/think/task/{taskId}                  轮询进度（running/done/error）
 *   GET  /api/annotate/think/img/{kind}?dir=…               取对应分类产物目录（kind = 图之一：same100|same100-unique|same90|same90-unique|same80|same80-unique|same70|same70-unique|same60|same60-unique|same50|same50-unique|max|max-unique|avg|avg-unique|dedup-avg|dedup-avg-unique|major8|major8-unique|avg8|avg8-unique|dedup-avg8|dedup-avg8-unique|major32|major32-unique|avg32|avg32-unique|dedup-avg32|dedup-avg32-unique|attn8-same100|attn8-same90|attn8-same80|attn8-same70|attn8-same60|attn8-same50|attn32-same100|attn32-same90|attn32-same80|attn32-same70|attn32-same60|attn32-same50|click8-same100|click8-same90|click8-same80|click8-same70|click8-same60|click8-same50|click32-same100|click32-same90|click32-same80|click32-same70|click32-same60|click32-same50；「全部」组 dir=_all_ 另接受 same100|same90|same80|same70|same60|same50|max|avg|dedup-avg 及 max/avg/dedup-avg 的 -maxdiff；
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

    /** 一键重建：先清空 resource/summary/ 全部产物再全量重算（前端「重新生成全部对照图」按钮；清场动作在串行计算池内执行） */
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

    /** 智能建议：把一张未标注截图交给 resource/runtime/ 落地的「综合最佳算法」异步比对 → {taskId}，随后轮询 /suggest/task/{taskId} */
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

    /** 读取分析产物 PNG（kind = 全部对照图 kind 之一；dir = 产物目录名做 UTF-8 → Base64 后传入，避免容器字符集差异，固定「全部」组传 {@code _all_}） */
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
