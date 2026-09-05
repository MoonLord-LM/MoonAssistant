package cn.moonlord.mca.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 旧版单目录布局的一次性迁移（首次启动自动执行）：
 *
 * <pre>
 *   旧 captures/                      迁移到新布局
 *   ├─ IMG_x.png + IMG_x.json  →  classify/（已标注）
 *   ├─ IMG_y.png（无 json）     →  capture/（未标注）
 *   ├─ sum/&lt;分类标注&gt;/**         →  summary/&lt;分类标注&gt;/**
 *   ├─ think/**（旧版废弃产物） →  删除
 *   ├─ 孤儿 .json / *.tmp      →  删除
 *   └─ 其余未知内容            →  保留不动（并提示人工检查）
 * </pre>
 *
 * <p>幂等：若旧目录已不存在或迁移后已删除则直接跳过；新目录与旧目录指向同一路径时也跳过
 * （避免把自己的数据当旧版来回搬）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyStorageMigrator implements ApplicationRunner {

    private final CaptureProperties properties;
    private final StoragePaths storage;

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    void migrate() {
        Path legacy = Paths.get(properties.getOutputDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(legacy)) {
            return;
        }
        if (legacy.equals(storage.capture())
            || legacy.equals(storage.classify())
            || legacy.equals(storage.summary())) {
            log.info("旧版截图目录 {} 与新布局目录相同，跳过自动迁移", legacy);
            return;
        }
        log.info("检测到旧版单目录布局 {}，迁移到 {}/、{}/、{}/ 三阶段目录…",
            legacy, storage.capture(), storage.classify(), storage.summary());

        int moved = 0, marked = 0, raw = 0, sumDirs = 0;
        List<Path> leftovers = new ArrayList<>();
        List<Path> children;
        try (Stream<Path> s = Files.list(legacy)) {
            children = s.toList();
        } catch (IOException e) {
            log.warn("枚举旧版目录 {} 失败，跳过迁移: {}", legacy, e.toString());
            return;
        }
        for (Path child : children) {
            String n = child.getFileName().toString();
            if (Files.isDirectory(child)) {
                if ("sum".equalsIgnoreCase(n)) {
                    if (mergeDirInto(child, storage.summary())) {
                        sumDirs++;
                    }
                } else if ("think".equalsIgnoreCase(n)) {
                    try {
                        deleteTree(child);
                        log.info("已删除旧版废弃产物目录 {}", child);
                    } catch (IOException e) {
                        leftovers.add(child);
                    }
                } else {
                    leftovers.add(child);   // 未知子目录（如 recycle/）不动
                }
                continue;
            }
            String lower = n.toLowerCase();
            if (lower.startsWith("img_") && lower.endsWith(".png")) {
                Path json = child.resolveSibling(n.substring(0, n.length() - 4) + ".json");
                boolean hasMark = Files.isRegularFile(json);
                Path target = hasMark ? storage.classify() : storage.capture();
                try {
                    Files.createDirectories(target);
                    moveReplace(child, target.resolve(child.getFileName()));
                    if (hasMark) {
                        moveReplace(json, target.resolve(json.getFileName()));
                        marked++;
                    } else {
                        raw++;
                    }
                    moved++;
                } catch (IOException e) {
                    log.warn("迁移截图 {} 失败（可手工移动）: {}", child, e.toString());
                    leftovers.add(child);
                }
            } else if (lower.endsWith(".json") || lower.endsWith(".tmp")) {
                // 孤儿 json / 半成品临时文件：无对应已迁移截图，直接清理
                try {
                    Files.deleteIfExists(child);
                } catch (IOException e) {
                    leftovers.add(child);
                }
            } else {
                leftovers.add(child);
            }
        }
        try {
            deleteEmptyDirs(legacy);
        } catch (IOException e) {
            log.warn("清理旧目录 {} 失败: {}", legacy, e.toString());
        }
        if (Files.exists(legacy)) {
            log.warn("旧目录 {} 仍有未迁移内容，请人工核对后删除", legacy);
        }
        log.info("旧版目录迁移完成：已标注 {} 张 → {}/，未标注 {} 张 → {}/，汇总产物 {} 组 → {}/",
            marked, storage.classify(), raw, storage.capture(), sumDirs, storage.summary());
        if (!leftovers.isEmpty()) {
            log.warn("旧目录中保留未处理项 {} 个：{}", leftovers.size(), leftovers);
        }
    }

    /** 把旧 sum/ 整目录并入 summary/；子目录同名冲突时以旧内容覆盖（产物可随时由样本重算） */
    private boolean mergeDirInto(Path srcRoot, Path dstRoot) {
        try {
            Files.createDirectories(dstRoot);
            try (Stream<Path> s = Files.list(srcRoot)) {
                for (Path child : s.toList()) {
                    Path dst = dstRoot.resolve(child.getFileName());
                    if (Files.exists(dst)) {
                        deleteTree(dst);
                    }
                    moveReplace(child, dst);
                }
            }
            deleteTree(srcRoot);
            return true;
        } catch (IOException e) {
            log.warn("迁移汇总产物 {} → {} 失败: {}", srcRoot, dstRoot, e.toString());
            return false;
        }
    }

    /** 删除目录内所有内容（含嵌套）后删除目录本身；不存在时直接返回 */
    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** 自底向上删除 legacy 下已经变空的目录（含 legacy 本身） */
    private void deleteEmptyDirs(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.filter(Files::isDirectory)
                                   .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                                   .toList();
            for (Path p : paths) {
                try (Stream<Path> s = Files.list(p)) {
                    if (s.findAny().isEmpty()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
    }

    private void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
