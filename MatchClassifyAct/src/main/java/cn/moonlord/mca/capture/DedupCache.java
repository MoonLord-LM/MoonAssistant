package cn.moonlord.mca.capture;

import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 去重比对结果持久缓存（JSON）：把「两张截图的全尺寸逐像素比对结果」按
 * <b>文件名 + 最后修改时间</b>四元组存盘，下次启动的历史重复清理直接复用，同样的两张图不再重复比对。
 *
 * <p>缓存身份 = 文件名 + mtime（毫秒），与所在目录、左右顺序都无关（比对本身是对称的），
 * 因此同一张图从 capture/ 移入 classify/（{@code Files.move} 保留 mtime）也不会失效。
 * 任一侧的<b>文件名或 mtime 变化</b>（内容被改过 / 被别的文件顶替）该条自动失效、重新比对。</p>
 *
 * <p>缓存值 = 两张图不一致像素点占比（%，与 {@link ScreenCaptureService} 判定同口径的两位舍入值），
 * 所以<b>阈值改动不需要重算</b>：判定时拿当前阈值与缓存值重新比较即可，结果与没缓存时完全一致。</p>
 *
 * <p>缓存文件存在 {@code classify/dedup-cache.json}（与已标注截图同目录，UTF-8，可随时删除、
 * 删了只是下次重算一遍；旧版放在运行目录根下的同名文件首次访问时自动搬进 classify/）。格式：</p>
 *
 * <pre>
 * {
 *   "version": 1,
 *   "pairs": {
 *     "IMG_a.png@1789161247895": { "IMG_b.png@1789161247895": 0.03, "IMG_c.png@1789161247896": 43.21 },
 *     "IMG_b.png@1789161247895": { "IMG_d.png@1789161247900": 1.25 }
 *   }
 * }
 * </pre>
 *
 * <p>即「互相比较过的文件组」：外层键 = 身份较小的一侧，内层 = 与之比对过的另一侧 → 不一致像素点占比；
 * 每对图只记一条（两侧按身份字典序），所以每个文件组独占一行、可直接查看与裁剪。版本号不认识则整份丢弃重算。</p>
 *
 * <p>条目不会无限增长：每次历史清理前用本次枚举到的截图身份集合 {@link #retainOnly} 丢掉
 * 已删除文件的条目。落盘用「.tmp → 原子改名」，中途被杀不会留下半截文件。</p>
 */
@Slf4j
@Component
public class DedupCache {

    /** 缓存文件名（存在 classify/ 下，与已标注截图同目录，可随时删除） */
    private static final String CACHE_FILE = "dedup-cache.json";

    /** 文件格式版本：与代码里的常量不一致（或不是 JSON）则整份丢弃重算，下次落盘即改写为新格式 */
    private static final int FORMAT_VERSION = 1;

    /** 攒够这么多条新结果就落一次盘：历史清理可能跑很久，中途被杀尽量少丢（写整份几十毫秒，相对逐张解码可忽略） */
    private static final int FLUSH_EVERY = 20000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 缓存文件完整路径 = classify/dedup-cache.json（落盘前确保 classify/ 已存在） */
    private final Path file;

    public DedupCache(StoragePaths storage) {
        this.file = storage.classify().resolve(CACHE_FILE);
    }

    /** 互相比较过的文件组：外层键「文件名@mtime」较小的一侧 → 内层「另一侧 → 不一致像素点占比（%）」 */
    private final Map<String, Map<String, Double>> pairs = new LinkedHashMap<>();

    /** 首次访问时懒加载（只在启动清理线程里用，不占启动时间）；dirty = 自上次落盘以来攒下的改动条数 */
    private boolean loaded;
    private int dirty;

    /** 一个文件的缓存身份：文件名 + 最后修改时间（毫秒）。
     *  文件刚被删 / stat 失败时 {@link #stampOf} 返回 null，调用方按「不参与缓存、每次现算」处理。 */
    public record Stamp(String name, long mtime) {

        /** 用 '@' 连接（Windows 文件名不含换行/制表符，mtime 是纯数字，反向拆分取最后一个 '@' 即可） */
        String key() {
            return name + "@" + mtime;
        }
    }

    /** 取一个文件的缓存身份（一次 stat）；拿不到返回 null */
    public static Stamp stampOf(Path p) {
        try {
            return new Stamp(p.getFileName().toString(), Files.getLastModifiedTime(p).toMillis());
        } catch (IOException | RuntimeException e) {
            return null;   // 文件刚被删 / 路径异常：该张本次不参与缓存，直接现算
        }
    }

    /** 查这一对图算过的比对结果（不一致像素点占比 %）；任一侧身份缺失、或这对没算过返回 null */
    public synchronized Double get(Stamp a, Stamp b) {
        if (a == null || b == null) {
            return null;
        }
        ensureLoaded();
        String ka = a.key();
        String kb = b.key();
        boolean aFirst = ka.compareTo(kb) <= 0;
        Map<String, Double> group = pairs.get(aFirst ? ka : kb);
        return group == null ? null : group.get(aFirst ? kb : ka);
    }

    /** 记下刚算出的比对结果（内存；落盘由 {@link #FLUSH_EVERY} 批量触发与 {@link #saveIfDirty()} 收尾） */
    public synchronized void put(Stamp a, Stamp b, double diffPercent) {
        if (a == null || b == null) {
            return;
        }
        ensureLoaded();
        String ka = a.key();
        String kb = b.key();
        boolean aFirst = ka.compareTo(kb) <= 0;
        pairs.computeIfAbsent(aFirst ? ka : kb, k -> new LinkedHashMap<>())
                .put(aFirst ? kb : ka, diffPercent);
        if (++dirty >= FLUSH_EVERY) {
            save();
        }
    }

    /**
     * 只保留身份在给定集合里的条目（每次历史清理枚举完截图目录后调用）：已删除 / 已移出基准目录的
     * 文件条目丢掉，避免缓存随历史无限增长。身份集合由调用方用 {@link #stampOf} 逐个构建。
     *
     * @return 丢弃的条数（供日志）
     */
    public synchronized int retainOnly(Set<String> identities) {
        if (identities.isEmpty()) {
            return 0;   // 一张截图都没有：不动缓存（枚举异常时不要误清）
        }
        ensureLoaded();
        int before = size();
        pairs.entrySet().removeIf(e -> !identities.contains(e.getKey()));   // 整个文件组都没了
        for (Map<String, Double> group : pairs.values()) {
            group.keySet().removeIf(k -> !identities.contains(k));          // 组内个别文件没了
        }
        pairs.values().removeIf(Map::isEmpty);
        int dropped = before - size();
        if (dropped > 0) {
            dirty++;   // 裁剪结果也要落盘（否则下次又白读一遍）
        }
        return dropped;
    }

    /** 有改动才落盘（历史清理结束 / 异常中断时调用）；写失败只告警，不影响本次清理 */
    public synchronized void saveIfDirty() {
        if (dirty > 0) {
            save();
        }
    }

    /** 缓存的比对对数（= 内层条目总数） */
    private int size() {
        int n = 0;
        for (Map<String, Double> group : pairs.values()) {
            n += group.size();
        }
        return n;
    }

    /** 原子落盘（.tmp → 原子改名）：中途被杀不会留下半截文件 */
    private void save() {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());   // 还没标注过任何图时 classify/ 可能不存在
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                // 结构本身很简单（键是「文件名@mtime」、值是数字），手写 JSON 反而能完全控制排版：
                // 每个文件组独占一行、便于直接查看与裁剪；键名统一走 jsonStr() 转义
                w.write("{");
                w.write("\n  \"version\": " + FORMAT_VERSION + ",");
                w.write("\n  \"pairs\": {");
                boolean firstGroup = true;
                for (Map.Entry<String, Map<String, Double>> e : pairs.entrySet()) {
                    if (e.getValue().isEmpty()) {
                        continue;
                    }
                    w.write(firstGroup ? "\n    " : ",\n    ");
                    firstGroup = false;
                    w.write(jsonStr(e.getKey()) + ": {");
                    boolean firstItem = true;
                    for (Map.Entry<String, Double> kv : e.getValue().entrySet()) {
                        if (!Double.isFinite(kv.getValue())) {
                            continue;   // 兜底：不该出现（比对结果都做过两位舍入）
                        }
                        w.write((firstItem ? " " : ", ") + jsonStr(kv.getKey()) + ": " + kv.getValue());
                        firstItem = false;
                    }
                    w.write(" }");
                }
                w.write("\n  }\n}\n");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = 0;
            log.debug("去重比对缓存已保存：{} 对 → {}", size(), file);
        } catch (IOException e) {
            log.warn("保存去重比对缓存 {} 失败（不影响本次清理）: {}", file, e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }

    /** 旧版把缓存写在运行目录根下：新位置（classify/）还没有、根下却有时搬进去，省掉一次全量重算 */
    private void migrateLegacyFile() {
        if (Files.isRegularFile(file)) {
            return;
        }
        Path legacy = Paths.get(CACHE_FILE).toAbsolutePath().normalize();
        if (legacy.equals(file) || !Files.isRegularFile(legacy)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.move(legacy, file);
            log.info("去重比对缓存已从 {} 移入 {}", legacy, file);
        } catch (IOException e) {
            log.warn("搬移旧去重比对缓存 {} 失败（本次按无缓存重算）: {}", legacy, e.toString());
        }
    }

    /** 懒加载缓存文件：格式不认识 / 版本不符 / 读取失败都按「无缓存」处理（只是下次要多算一遍，不影响判定结果） */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        migrateLegacyFile();
        if (!Files.isRegularFile(file)) {
            return;   // 首次运行：没有缓存文件是正常状态
        }
        int count;
        boolean versionOk = false;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             JsonParser p = JSON.getFactory().createParser(r)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("根节点不是对象");
            }
            count = 0;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                if ("version".equals(field)) {
                    versionOk = p.currentToken() == JsonToken.VALUE_NUMBER_INT
                            && p.getIntValue() == FORMAT_VERSION;
                } else if ("pairs".equals(field)) {
                    count = readPairs(p);
                } else {
                    p.skipChildren();
                }
            }
        } catch (IOException | RuntimeException e) {
            pairs.clear();
            log.warn("读取去重比对缓存 {} 失败，本次按无缓存重算: {}", file, e.toString());
            return;
        }
        if (!versionOk) {
            pairs.clear();
            log.info("去重比对缓存 {} 版本不认识（当前需要 v{}），忽略整份、本次重新比对", file.getFileName(), FORMAT_VERSION);
            return;
        }
        log.info("去重比对缓存已载入 {} 对（{}）", count, file);
    }

    /** 写 JSON 字符串字面量（只可能是文件名 + 数字，仍按规范转义，避免任何文件名踩到 JSON 语法） */
    private static String jsonStr(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** 读 {@code "pairs": { 外层: { 内层: 数字 } }}；结构不对直接抛（由 {@link #ensureLoaded} 整份丢弃） */
    private int readPairs(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw new IOException("pairs 不是对象");
        }
        int count = 0;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String outer = p.currentName();
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("文件组不是对象");
            }
            Map<String, Double> group = new LinkedHashMap<>();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String inner = p.currentName();
                p.nextToken();
                if (p.currentToken() != JsonToken.VALUE_NUMBER_FLOAT
                        && p.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                    throw new IOException("差异值不是数字");
                }
                group.put(inner, p.getDoubleValue());
                count++;
            }
            if (p.currentToken() != JsonToken.END_OBJECT) {
                throw new IOException("文件组未闭合");
            }
            if (!group.isEmpty()) {
                pairs.put(outer, group);
            }
        }
        return count;
    }
}
