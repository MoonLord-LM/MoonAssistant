package cn.moonlord.mca.mark;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 *   classify/  标注后截图 + 同名 .json（保存标注时整体从 capture/ 移入）
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

    /** 标注文件统一 UTF-8 + 缩进 + 忽略 null 字段（未点击就不落 left/top） */
    private static final ObjectMapper JSON = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT);

    /** 截图目录列表项（含已标注内容的摘要，前端列表可直接展示） */
    public record ImageItem(String name, long size, long lastModified,
                            boolean marked, String state, String action,
                            Integer left, Integer top) {
    }

    private final StoragePaths storage;
    private final ThinkService thinkService;

    public AnnotateController(StoragePaths storage, ThinkService thinkService) {
        this.storage = storage;
        this.thinkService = thinkService;
    }

    // ------------------------------------------------------------------ 列表

    @GetMapping("/images")
    public List<ImageItem> listImages() throws IOException {
        // 同一文件名可能跨目录，优先取 classify/（已标注）；capture/ 只在无同名时兜底
        Map<String, Path> byName = new TreeMap<>();
        for (Path p : listPngs(storage.classify(), false)) {
            byName.put(p.getFileName().toString(), p);
        }
        for (Path p : listPngs(storage.capture(), true)) {
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
            Path mark = companionJson(png);
            if (mark != null && Files.isRegularFile(mark)) {
                CaptureMark m = readMark(mark);
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

    /** 枚举某目录下 IMG_*.png；stable=true 时要求写入已完成（未满 800ms 的半文件不列） */
    private List<Path> listPngs(Path dir, boolean stable) {
        List<Path> pngs = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return pngs;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> pngShape(p) && (!stable || settled(p)))
             .forEach(pngs::add);
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

    /** 过滤条件：刚写入未写完的 PNG 不列出，避免前端拿到半个文件 */
    private boolean settled(Path p) {
        try {
            return Files.size(p) > 0
                && System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() > 800;
        } catch (IOException e) {
            return false;
        }
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
        Path mark = companionJson(png);
        CaptureMark result = (mark != null && Files.isRegularFile(mark)) ? readMark(mark) : null;
        return ResponseEntity.ok(result != null ? result : new CaptureMark());
    }

    /**
     * 保存标注：写入 classify/ 下的同名 .json（原子替换）；
     * 若截图还在 capture/（未标注），写入成功后整体移到 classify/（进入“已标注”数据集）。
     */
    @PutMapping("/mark/{name:.+}")
    public ResponseEntity<?> putMark(@PathVariable String name, @RequestBody CaptureMark mark) {
        Path png = safePng(name);
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        String error = validate(mark);
        if (error != null) {
            return ResponseEntity.badRequest().body(error);
        }
        Path classify = storage.classify();
        boolean alreadyClassified = png.startsWith(classify);
        try {
            Files.createDirectories(classify);
            Path target = alreadyClassified ? companionJson(png)
                : classify.resolve(jsonNameOf(png));
            atomicWriteMark(target, mark);
            if (!alreadyClassified) {
                // 标注已落盘，再把原始截图从 capture/ 移入 classify/
                try {
                    Files.move(png, classify.resolve(png.getFileName()));
                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(target);   // 移动失败回滚：删掉孤儿 json，截图留在 capture/ 未标注状态
                    } catch (IOException ignore) {
                    }
                    log.error("标注文件已写入但截图迁移失败，已回滚 {}: {}", png, e.toString());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("标注数据已写入但截图移入 classify/ 失败，已自动回滚，请重试：" + e.getMessage());
                }
                log.debug("标注完成，截图 {}/ → {}/", storage.capture(), classify);
            }
            return ResponseEntity.ok(mark);
        } catch (IOException e) { // JsonProcessingException 是 IOException 子类
            log.error("写标注文件失败 {}: {}", png, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("写标注文件失败: " + e.getMessage());
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
        Path mark = companionJson(png);
        Path movedBack = null;
        try {
            if (png.startsWith(storage.classify())) {
                Path capture = storage.capture();
                Files.createDirectories(capture);
                movedBack = capture.resolve(png.getFileName());
                Files.move(png, movedBack);
            }
            try {
                if (mark != null && Files.exists(mark)) {
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
     * 分类标注整体改名：把 classify/ 下所有 state=from 的标注 json 改为 to，
     * 并清理该分类旧的汇总分析产物目录（summary/&lt;from&gt;）。
     * 目标名称若已被其它图片使用则拒绝（合并请先处理，避免动作语义混乱）。
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
        Path dir = storage.classify();
        if (!Files.isDirectory(dir)) {
            return ResponseEntity.ok(Map.of("updated", 0, "purged", 0));
        }
        List<Path> targets = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!pngShape(p)) {
                    continue;
                }
                Path mark = companionJson(p);
                if (!Files.isRegularFile(mark)) {
                    continue;
                }
                CaptureMark m = readMark(mark);
                if (m == null) {
                    continue;
                }
                String st = m.getState() == null ? "" : m.getState().trim();
                if (from.equals(st)) {
                    targets.add(mark);
                } else if (to.equals(st)) {
                    return ResponseEntity.badRequest().body("新名称「" + to + "」已被其他图片使用，无法直接改名（如需合并请先自行处理）");
                }
            }
        } catch (IOException e) {
            log.error("改名时枚举标注目录失败: {}", e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("改名失败：" + e.getMessage());
        }
        if (targets.isEmpty()) {
            return ResponseEntity.badRequest().body("没有图片正在使用「" + from + "」，无需改名");
        }
        int updated = 0;
        for (Path mark : targets) {
            try {
                CaptureMark m = readMark(mark);
                if (m == null) {
                    continue;
                }
                m.setState(to);
                Files.writeString(mark, JSON.writeValueAsString(m));
                updated++;
            } catch (IOException e) {
                log.error("改名写标注失败 {}: {}", mark, e.toString());
            }
        }
        int purged = thinkService.purgeArtifactsOfState(from);
        log.info("分类标注改名「{}」→「{}」：更新 {} 张 json，清理旧产物目录 {} 个", from, to, updated, purged);
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
        // capture/ 下可能还在写入的原图不允许删除；classify/ 里都是保存标注时完整移入的完成文件，不受限
        if (!png.startsWith(storage.classify()) && !settled(png)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("截图尚未写入完成");
        }
        List<Path> targets = new ArrayList<>();
        targets.add(png);
        Path mark = companionJson(png);
        if (mark != null && Files.isRegularFile(mark)) {
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

    // ------------------------------------------------------------------ 校验与工具

    private String validate(CaptureMark m) {
        if (m.getAction() == null || !ACTIONS.contains(m.getAction())) {
            return "action 必须是 none / click 之一";
        }
        if (CaptureMark.ACTION_CLICK.equals(m.getAction())) {
            if (m.getLeft() == null || m.getTop() == null || m.getLeft() < 0 || m.getTop() < 0) {
                return "click 动作必须提供非负的窗口相对坐标 left/top";
            }
        } else {
            // none 不关心坐标，落盘前清空避免历史残留误导
            m.setLeft(null);
            m.setTop(null);
        }
        if (m.getState() == null) {
            m.setState("");
        }
        // 分类标注将作为汇总分析产物目录名（summary/<分类标注>/），
        // 不允许包含无法作为文件名的符号，也不允许以 . 结尾（Windows 会裁掉尾部点导致不一致）
        String labelError = invalidDirChar(m.getState());
        if (labelError != null) {
            return labelError;
        }
        return null;
    }

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

    private CaptureMark readMark(Path mark) {
        try {
            return JSON.readValue(mark.toFile(), CaptureMark.class);
        } catch (IOException e) {
            log.warn("读取标注文件 {} 失败，按未标注处理: {}", mark, e.toString());
            return null;
        }
    }

    private void atomicWriteMark(Path target, CaptureMark mark) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writeValueAsString(mark));
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
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

    /** 同名 .json（IMG_x.png → IMG_x.json）；png 未验证时可能返回 null */
    private Path companionJson(Path png) {
        return png.getParent().resolve(jsonNameOf(png)).normalize();
    }

    private String jsonNameOf(Path png) {
        String n = png.getFileName().toString();
        return n.substring(0, n.length() - 4) + ".json";
    }
}
