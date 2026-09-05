package cn.moonlord.mca.mark;

import cn.moonlord.mca.config.StoragePaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 控制台标注接口。图片按三个阶段分目录存放：
 * <pre>
 *   capture/   原始截图（未标注）
 *   classify/  已标注样本：PNG + 归属 json（仅 {state}）；同目录 data.json = 分类定义中心表（动作/坐标，每分类一份）
 *   summary/   汇总分析产物（见 ThinkService）
 * </pre>
 * 列表接口合并 capture/ 与 classify/ 两处（同一文件名优先取 classify/，即已标注版本）。
 *
 * <p>页面入口：{@code http://localhost:8080/annotate}（静态页 <code>static/annotate.html</code>）。</p>
 *
 * <p>图片内容一旦输出即不再变更，故所有图片响应允许浏览器缓存；标注 .json 每次读写。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/annotate")
public class AnnotateController {

    private static final Set<String> ACTIONS =
        Set.of(CaptureMark.ACTION_NONE, CaptureMark.ACTION_CLICK);

    /** 截图目录列表项（含已标注内容的摘要，前端列表可直接展示） */
    public record ImageItem(String name, long size, long lastModified,
                            boolean marked, String state, String action,
                            Integer left, Integer top) {
    }

    private final StoragePaths storage;
    private final ThinkService thinkService;
    private final ClassifyStore classifyStore;

    public AnnotateController(StoragePaths storage, ThinkService thinkService, ClassifyStore classifyStore) {
        this.storage = storage;
        this.thinkService = thinkService;
        this.classifyStore = classifyStore;
    }

    // ------------------------------------------------------------------ 列表

    @GetMapping("/images")
    public List<ImageItem> listImages() throws IOException {
        // 同一文件名可能跨目录，优先取 classify/（已标注）；capture/ 只在无同名时兜底
        Map<String, Path> byName = new TreeMap<>();
        for (Path p : listPngs(storage.classify())) {
            byName.put(p.getFileName().toString(), p);
        }
        for (Path p : listPngs(storage.capture())) {
            byName.putIfAbsent(p.getFileName().toString(), p);
        }
        List<ImageItem> items = new ArrayList<>(byName.size());
        for (Map.Entry<String, Path> e : byName.entrySet()) {
            Path png = e.getValue();
            String state = null;
            String action = null;
            Integer left = null;
            Integer top = null;
            boolean marked = false;
            // classify/ 下的样本：动作与坐标以中心表定义为准（样本 json 只存 state 归属）
            if (png.startsWith(storage.classify())) {
                CaptureMark m = classifyStore.readSample(e.getKey());
                if (m != null) {
                    marked = true;
                    state = m.getState();
                    action = m.getAction();
                    left = m.getLeft();
                    top = m.getTop();
                }
            }
            try {
                items.add(new ImageItem(e.getKey(), Files.size(png),
                    Files.getLastModifiedTime(png).toMillis(),
                    marked, state, action, left, top));
            } catch (IOException ex) {
                log.debug("跳过不可读的截图 {}: {}", e.getKey(), ex.toString());
            }
        }
        items.sort(Comparator.comparing(ImageItem::name));
        return items;
    }

    /** 枚举某目录下 IMG_*.png。半成品过滤靠「后缀」而非时间窗：
     *  所有 PNG 均由写端「先写 .png.tmp → 原子改名 .png」产生，`.png` 一旦出现即完整落盘；
     *  仍在写入的 `.png.tmp` 不符合 img_*.png 命名，天然不会被列出来。 */
    private List<Path> listPngs(Path dir) {
        List<Path> pngs = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return pngs;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(this::pngShape).forEach(pngs::add);
        } catch (IOException e) {
            log.debug("枚举目录失败 {}: {}", dir, e.toString());
        }
        return pngs;
    }

    private boolean pngShape(Path p) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        String n = p.getFileName().toString().toLowerCase();
        return n.startsWith("img_") && n.endsWith(".png");
    }

    // ------------------------------------------------------------------ 图片

    @GetMapping(value = "/image/{name:.+}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<Resource> image(@PathVariable String name) {
        Path png = safePng(name);
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .body(new FileSystemResource(png));
    }

    // ------------------------------------------------------------------ 标注读写

    @GetMapping("/mark/{name:.+}")
    public ResponseEntity<CaptureMark> getMark(@PathVariable String name) {
        Path png = safePng(name);
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        // 合成读取：样本 json 的 state + 中心表动作坐标；capture/（未标注）无 json 时返回空标注
        CaptureMark result = classifyStore.readSample(name);
        return ResponseEntity.ok(result != null ? result : new CaptureMark());
    }

    /**
     * 保存标注（分类定义表驱动）：
     * <ul>
     *   <li>样本 json 只记录分类归属 {@code {state}}，动作与坐标收敛到 classify/data.json 中心表；</li>
     *   <li>该分类<b>尚无定义</b> → 以本次提交的 action/left/top 建立定义（首次固定，成为该分类唯一动作）；</li>
     *   <li>该分类<b>已有定义</b> → 本图只登记归属，动作坐标一律以定义为准（提交的动作/坐标会被定义覆盖）；</li>
     *   <li>对<b>已标注图</b>改自己的分类的动作/坐标（state 不变）→ 视为<b>重定义该分类</b>，同步到全组样本。</li>
     * </ul>
     * 若截图还在 capture/（未标注），写入成功后整体移到 classify/（进入“已标注”数据集）。
     */
    @PutMapping("/mark/{name:.+}")
    public ResponseEntity<?> putMark(@PathVariable String name, @RequestBody CaptureMark mark) {
        Path png = safePng(name);
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        if (mark == null) {
            mark = new CaptureMark();
        }
        if (mark.getState() == null) {
            mark.setState("");
        }
        String labelError = invalidDirChar(mark.getState());
        if (labelError != null) {
            return ResponseEntity.badRequest().body(labelError);
        }
        if (mark.getAction() == null || !ACTIONS.contains(mark.getAction())) {
            return ResponseEntity.badRequest().body("action 必须是 none / click 之一");
        }
        String state = mark.getState().trim();
        boolean alreadyClassified = png.startsWith(storage.classify());

        // 已标注图当前的分类归属（用于判断“原分类内重定义”）
        String oldState = null;
        if (alreadyClassified) {
            CaptureMark cur = classifyStore.readSample(name);
            oldState = cur == null ? null : (cur.getState() == null ? "" : cur.getState().trim());
        }
        boolean redef = alreadyClassified && oldState != null && !oldState.isEmpty()
            && oldState.equals(state);
        CaptureMark existingDef = classifyStore.definitionOf(state);

        CaptureMark adopted;
        if (existingDef == null || redef) {
            // 首次定义 / 原分类内重定义：以本次提交内容作为该分类的唯一动作
            String action = mark.getAction();
            Integer left = mark.getLeft();
            Integer top = mark.getTop();
            if (CaptureMark.ACTION_CLICK.equals(action)) {
                if (left == null || top == null || left < 0 || top < 0) {
                    return ResponseEntity.badRequest().body(
                        "「" + state + "」是首次使用（或重定义），click 动作必须提供非负的点击坐标 left/top");
                }
            } else {
                action = CaptureMark.ACTION_NONE;
                left = null;
                top = null;
            }
            try {
                adopted = classifyStore.define(state, action, left, top);
            } catch (IOException e) {
                log.error("写分类定义表失败 {}: {}", state, e.toString());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("写分类定义表失败: " + e.getMessage());
            }
        } else {
            // 已有统一定义：样本归属即保存，动作坐标一律以定义为准（保持同分类动作坐标唯一）
            adopted = existingDef;
            log.debug("分类「{}」已有统一定义，采纳定义保存样本 {}", state, name);
        }

        try {
            classifyStore.saveSample(name, state);
            if (!alreadyClassified) {
                // 样本 json 已落盘 classify/，再把原始截图从 capture/ 移入 classify/
                try {
                    Files.createDirectories(storage.classify());
                    Files.move(png, storage.classify().resolve(png.getFileName()));
                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(classifyStore.sampleJson(name));   // 移动失败回滚：删掉孤儿 json
                    } catch (IOException ignore) {
                    }
                    log.error("标注文件已写入但截图迁移失败，已回滚 {}: {}", png, e.toString());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("标注数据已写入但截图移入 classify/ 失败，已自动回滚，请重试：" + e.getMessage());
                }
                log.debug("标注完成，截图 {}/ → {}/", storage.capture(), storage.classify());
            }
            return ResponseEntity.ok(adopted);
        } catch (IOException e) { // JsonProcessingException 是 IOException 子类
            log.error("写样本标注失败 {}: {}", png, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("写样本标注失败: " + e.getMessage());
        }
    }

    /**
     * 清除标注：若截图在 classify/ 下先把 PNG 移回 capture/（位置还原为“未标注”数据集），
     * 再删除同名 .json。先移后删，移动失败时标注文件原样保留、整体保持原状，
     * 避免出现“标注已删、截图却仍留在 classify/”的半清除态。
     */
    @DeleteMapping("/mark/{name:.+}")
    public ResponseEntity<?> deleteMark(@PathVariable String name) {
        Path png = safePng(name);
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        Path mark = classifyStore.sampleJson(name);
        Path movedBack = null;
        try {
            if (png.startsWith(storage.classify())) {
                Path capture = storage.capture();
                Files.createDirectories(capture);
                movedBack = capture.resolve(png.getFileName());
                Files.move(png, movedBack);
            }
            try {
                if (Files.exists(mark)) {
                    Files.delete(mark);
                }
            } catch (IOException e) {
                // 标注文件删除失败：把已移走的截图挪回 classify/，与标注重新成对，保持清除前原状
                if (movedBack != null) {
                    try {
                        Files.move(movedBack, png);
                    } catch (IOException ignore) {
                    }
                }
                throw e;
            }
            if (movedBack != null) {
                log.debug("清除标注，截图 {}/ → {}/", storage.classify(), storage.capture());
            }
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            log.error("清除标注失败 {}: {}", png, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("清除标注失败: " + e.getMessage());
        }
    }

    /**
     * 分类标注整体改名：中心表 data.json 的 key 与全部使用该分类的样本 json 的 state 一并改为新名，
     * 并清理该分类旧的汇总分析产物目录（summary/&lt;from&gt;）。
     * 目标名称若已有分类定义或被其它图片使用则拒绝（合并请先处理，避免动作语义混乱）。
     */
    @PostMapping("/rename")
    public ResponseEntity<?> renameState(@RequestBody Map<String, String> body) {
        String from = body == null ? "" : String.valueOf(body.getOrDefault("from", "")).trim();
        String to = body == null ? "" : String.valueOf(body.getOrDefault("to", "")).trim();
        if (from.isEmpty()) {
            return ResponseEntity.badRequest().body("请指定要改名的分类标注");
        }
        if (to.isEmpty()) {
            return ResponseEntity.badRequest().body("新名称不能为空");
        }
        if (from.equals(to)) {
            return ResponseEntity.badRequest().body("新名称与原名称相同，无需改名");
        }
        String labelError = invalidDirChar(to);
        if (labelError != null) {
            return ResponseEntity.badRequest().body(labelError);
        }
        int updated;
        try {
            updated = classifyStore.renameState(from, to);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IOException e) {
            log.error("改名失败 [{} → {}]: {}", from, to, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("改名失败：" + e.getMessage());
        }
        int purged = thinkService.purgeArtifactsOfState(from);
        log.info("分类标注改名「{}」→「{}」：更新 {} 张样本 json，清理旧产物目录 {} 个", from, to, updated, purged);
        return ResponseEntity.ok(Map.of("updated", updated, "purged", purged));
    }

    // ------------------------------------------------------------------ 删除（移入回收站）

    /**
     * 把整张截图（PNG + 同名标注 .json）移入系统回收站，控制台不再显示该图。
     * 截图可能位于 capture/（未标注）或 classify/（已标注），两个目录都能删除。
     */
    @PostMapping("/delete")
    public ResponseEntity<?> deleteImage(@RequestBody Map<String, String> body) {
        String name = (body == null ? "" : String.valueOf(body.getOrDefault("name", ""))).trim();
        Path png = safePng(name);
        if (png == null || !pngShape(png)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("截图不存在");
        }
        // 文件命名 img_*.png 只会在「.tmp 写入完成、原子改名」后出现，因此能枚举/删除的都是完整文件，无需时间等待
        List<Path> targets = new ArrayList<>();
        targets.add(png);
        Path mark = classifyStore.sampleJson(name);
        if (Files.isRegularFile(mark)) {
            targets.add(mark);
        }
        try {
            List<Path> recycled = RecycleBin.recycle(targets);
            boolean pngOk = recycled.contains(png);
            boolean markOk = mark != null && recycled.contains(mark);
            log.info("截图移入回收站：{}（标注 {}）", png.getFileName(), markOk ? "一并移除" : "无/跳过");
            return ResponseEntity.ok(Map.of("name", name, "png", pngOk, "mark", markOk));
        } catch (IOException e) {
            log.error("截图移入回收站失败 {}: {}", png, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("移入回收站失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 分类定义（中心表）

    /**
     * 全部已定义分类（动作 + 坐标，含尚无样本图的定义），按分类名排序。
     * 前端用它在“填入分类标注”时直接带出统一定义的动作/坐标，无需逐张统计。
     */
    @GetMapping("/defs")
    public List<CaptureMark> listDefinitions() {
        return classifyStore.definitions();
    }

    // ------------------------------------------------------------------ 校验与工具

    /** 返回说明文字；null = 文本可安全用作产物目录名 */
    private String invalidDirChar(String label) {
        int len = label.length();
        for (int i = 0; i < len; i++) {
            char c = label.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                return "分类标注不能包含 \\ / : * ? \" < > | 等无法作为文件名的符号";
            }
        }
        if (len > 0 && label.charAt(len - 1) == '.') {
            return "分类标注不能以 . 结尾（它将用作汇总分析产物目录名）";
        }
        return null;
    }

    /** 校验图片名只落在 classify/ 或 capture/ 内且是 .png，返回其绝对路径；非法返回 null */
    private Path safePng(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        // 已标注优先（若两个目录意外出现同名，classify/ 版本为准）
        Path hit = within(storage.classify(), name);
        if (hit == null) {
            hit = within(storage.capture(), name);
        }
        return hit;
    }

    private Path within(Path base, String name) {
        Path p = base.resolve(name).normalize();
        if (!p.startsWith(base) || !Files.isRegularFile(p)) {
            return null;
        }
        String lower = p.getFileName().toString().toLowerCase();
        return lower.endsWith(".png") ? p : null;
    }
}
