package cn.moonlord.mca.act;

import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 运行时算法（{@code resource/runtime/}）的生成与取用。
 *
 * <p>算法调优每跑完一轮（刷新算法特征 / 自动调整参数），由 {@link OptimizeService} 调 {@link #build}
 * 把它选出的「综合最佳算法」落地：把算法<b>生效特征</b>（权重 Y &gt; 0，见
 * {@link RuntimeAlgorithm#effective}）的对照图产物从 resource/summary/ <b>复制</b>一份到
 * {@code resource/runtime/&lt;分类&gt;/}（内容没变的不重复复制；Y = 0 的特征对匹配没有贡献，既不复制产物、
 * 也不写进算法定义），各分类多余 / 不再被引用的旧产物清掉，
 * 最后写 {@code resource/runtime/algorithm.json}（先复制图后写定义：定义出现即代表这一份是完整的）。
 * <b>复制而不是物理搬走</b>：resource/summary/ 那份还要留给汇总分析 / 特征验证 / 人工目检用，
 * 搬走会被判成「产物缺失」而让整库需重算、算法调优也没法再跑；
 * 于是 {@code resource/runtime/} 自成一份、执行与推荐只读它（resource/summary/ 改删都不影响执行）。
 *
 * <p>执行模式与「未标注」的单图智能推荐统一走 {@link #classify}：没有运行时算法（从没跑过算法调优、
 * 或整目录被删）返回 null，调用方据此提示「先到算法调优完成一轮」。
 *
 * <p>算法定义按 {@code algorithm.json} 的「大小 + 修改时间」缓存，重跑算法调优写完新文件即自动换新。
 */
@Slf4j
@Component
public class RuntimeService {

    /** 算法定义文件名（resource/runtime/ 下） */
    public static final String FILE_ALGO = "algorithm.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final StoragePaths storage;
    private final FrameClassifier classifier;

    private volatile RuntimeAlgorithm cached;
    private volatile String cachedKey = "";

    public RuntimeService(StoragePaths storage, FrameClassifier classifier) {
        this.storage = storage;
        this.classifier = classifier;
    }

    /** resource/runtime/ 目录（算法调优的落地目录，可整目录删除后重跑算法调优重建） */
    public Path dir() {
        return storage.runtime();
    }

    /** 有没有可用的运行时算法（没有 = 还没做过算法调优，页面提示先完成算法调优） */
    public boolean ready() {
        return current() != null;
    }

    /** 取当前运行时算法；目录 / 定义文件不存在或解析失败返回 null */
    public RuntimeAlgorithm current() {
        Path file = dir().resolve(FILE_ALGO);
        try {
            if (!Files.isRegularFile(file)) {
                cached = null;
                cachedKey = "";
                return null;
            }
            String key = Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis();
            RuntimeAlgorithm hit = cached;
            if (hit != null && key.equals(cachedKey)) {
                return hit;
            }
            RuntimeAlgorithm algo = read(file);
            cached = algo;
            cachedKey = key;
            return algo;
        } catch (Exception e) {
            log.warn("运行时算法读取失败 {}: {}", file, e.toString());
            return null;
        }
    }

    /** 按运行时算法识别一张画面；没有运行时算法（或画面为空）返回 null，调用方据此提示先完成算法调优 */
    public FrameClassifier.Outcome classify(BufferedImage frame) {
        RuntimeAlgorithm algo = current();
        if (algo == null || frame == null) {
            return null;
        }
        return classifier.classifyByAlgorithm(frame, dir(), algo);
    }

    /**
     * 生成 / 刷新 resource/runtime/（算法调优每跑完一轮调用）。
     *
     * @param source   这一轮是什么任务生成的（verify = 刷新算法特征 / tune = 自动调整参数）
     * @param costMs   这一轮算法调优的耗时（与统计行首行同一次）
     * @param meta     综合最佳算法的 id / 名称 / 两率 / 综合分（其余字段由本方法补）
     * @param features 算法特征（kind + 基础分 X + 调优后的权重 Y）；只有权重 Y &gt; 0 的<b>生效特征</b>会落地
     * @param states   各分类（files 留空，本方法按 kind 去 resource/summary/&lt;dir&gt;/ 找产物）
     * @return 落地后的算法；失败返回 null（resource/runtime/ 保持原样，执行与推荐仍用上一版）
     */
    public synchronized RuntimeAlgorithm build(String source, long costMs, RuntimeAlgorithm meta,
                                               List<RuntimeAlgorithm.Feature> features,
                                               List<RuntimeAlgorithm.State> states) {
        Path root = dir();
        Path sum = storage.summary();
        try {
            Files.createDirectories(root);
            // 只落地「生效特征」（权重 Y > 0，见 RuntimeAlgorithm#effective）：每个分类只复制这几个 kind 的产物、
            // algorithm.json 里也只写这几个特征 —— Y = 0 的特征在 Σ「X × Y」里恒为 0，对匹配值没有贡献，
            // 少一个特征就少复制 / 少解码一张产物；识别侧读到的也就只有生效特征，自然只按它们比对与加权
            List<RuntimeAlgorithm.Feature> eff = RuntimeAlgorithm.effective(features);
            List<RuntimeAlgorithm.State> out = new ArrayList<>(states.size());
            Set<String> keepDirs = new HashSet<>();
            int copied = 0, reused = 0, missing = 0;
            for (RuntimeAlgorithm.State st : states) {
                keepDirs.add(st.dir());
                Path srcDir = sum.resolve(st.dir());
                Path dstDir = root.resolve(st.dir());
                Map<String, String> files = new LinkedHashMap<>();
                for (RuntimeAlgorithm.Feature f : eff) {
                    if (files.containsKey(f.kind())) {
                        continue;
                    }
                    String name = ArtifactKind.file(f.kind());
                    Path src = srcDir.resolve(name);
                    if (!Files.isRegularFile(src)) {
                        missing++;   // 该分类没有此特征产物（判分时这个特征对它不参与，与算法调优的评价同口径）
                        continue;
                    }
                    Files.createDirectories(dstDir);
                    Path dst = dstDir.resolve(name);
                    if (same(src, dst)) {
                        reused++;
                    } else {
                        copy(src, dst);
                        copied++;
                    }
                    files.put(f.kind(), name);
                }
                out.add(new RuntimeAlgorithm.State(st.state(), st.dir(), st.action(), st.attnLeft(), st.attnTop(),
                        st.actLeft(), st.actTop(), st.width(), st.height(), files));
            }
            purge(root, keepDirs, eff);   // 只保留生效特征的产物：权重调到 0 的特征，下次调优后它的旧产物会被清掉
            RuntimeAlgorithm algo = new RuntimeAlgorithm(meta.algoId(), meta.algoName(), meta.accuracy(), meta.tieRate(),
                    meta.score(), System.currentTimeMillis(), costMs, source,
                    List.copyOf(eff), List.copyOf(out));
            write(root.resolve(FILE_ALGO), algo);
            cached = null;   // 让下一次读按新文件重新解析（大小 + mtime 变了）
            cachedKey = "";
            log.info("运行时算法已刷新（{}）：{} 综合分 {}%，{} 个分类 / 生效特征 {} 个（算法共 {} 个），复制对照图 {} 张（复用 {} 张、缺失 {} 张）",
                    source, algo.algoName(), String.format("%.2f", algo.score()), out.size(), eff.size(), features.size(),
                    copied, reused, missing);
            return algo;
        } catch (Exception e) {
            log.warn("运行时算法生成失败（resource/runtime/ 保持原样）: {}", e.toString());
            return null;
        }
    }

    /** 源产物与运行时的副本是否已一致（大小 + 修改时间都相同就跳过复制，避免调优一次搬几 GB） */
    private static boolean same(Path src, Path dst) throws IOException {
        if (!Files.isRegularFile(dst)) {
            return false;
        }
        return Files.size(src) == Files.size(dst)
                && Files.getLastModifiedTime(src).toMillis() == Files.getLastModifiedTime(dst).toMillis();
    }

    private static void copy(Path src, Path dst) throws IOException {
        try {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 清掉上一版遗留、已不再被引用的产物：不在本次算法里的分类子目录整体删除，留下的子目录里多余的 png 也删掉 */
    private static void purge(Path root, Set<String> keepDirs, List<RuntimeAlgorithm.Feature> features) {
        Set<String> keepFiles = new HashSet<>();
        for (RuntimeAlgorithm.Feature f : features) {
            keepFiles.add(ArtifactKind.file(f.kind()));
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                if (!keepDirs.contains(p.getFileName().toString())) {
                    deleteTree(p);
                    continue;
                }
                try (DirectoryStream<Path> fs = Files.newDirectoryStream(p)) {
                    for (Path f : fs) {
                        String name = f.getFileName().toString();
                        if (Files.isRegularFile(f) && name.toLowerCase(Locale.ROOT).endsWith(".png")
                                && !keepFiles.contains(name)) {
                            Files.deleteIfExists(f);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("resource/runtime/ 旧产物清理失败（不影响执行）: {}", e.toString());
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.debug("resource/runtime/ 旧分类目录删除失败 {}: {}", dir, e.toString());
        }
    }

    private static void write(Path file, RuntimeAlgorithm a) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1);
        m.put("algoId", a.algoId());
        m.put("algoName", a.algoName());
        m.put("accuracy", a.accuracy());
        m.put("tieRate", a.tieRate());
        m.put("score", a.score());
        m.put("builtMs", a.builtMs());
        m.put("costMs", a.costMs());
        m.put("source", a.source());
        List<Map<String, Object>> fs = new ArrayList<>(a.features().size());
        for (RuntimeAlgorithm.Feature f : a.features()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("kind", f.kind());
            fm.put("x", f.x());
            fm.put("y", f.y());
            fs.add(fm);
        }
        m.put("features", fs);
        List<Map<String, Object>> ss = new ArrayList<>(a.states().size());
        for (RuntimeAlgorithm.State s : a.states()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("state", s.state());
            sm.put("dir", s.dir());
            sm.put("action", s.action());
            sm.put("attnLeft", s.attnLeft());
            sm.put("attnTop", s.attnTop());
            sm.put("actLeft", s.actLeft());
            sm.put("actTop", s.actTop());
            sm.put("width", s.width());
            sm.put("height", s.height());
            sm.put("files", s.files());
            ss.add(sm);
        }
        m.put("states", ss);
        Path tmp = file.resolveSibling(FILE_ALGO + ".tmp");
        JSON.writeValue(tmp.toFile(), m);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 读 algorithm.json；缺特征 / 缺分类时返回 null（当作没有运行时算法处理） */
    private static RuntimeAlgorithm read(Path file) throws IOException {
        JsonNode r = JSON.readTree(file.toFile());
        if (r == null || !r.isObject()) {
            return null;
        }
        List<RuntimeAlgorithm.Feature> feats = new ArrayList<>();
        for (JsonNode f : r.path("features")) {
            String kind = f.path("kind").asText("");
            if (!kind.isEmpty()) {
                feats.add(new RuntimeAlgorithm.Feature(kind, f.path("x").asDouble(0), f.path("y").asDouble(0)));
            }
        }
        List<RuntimeAlgorithm.State> states = new ArrayList<>();
        for (JsonNode s : r.path("states")) {
            Map<String, String> files = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = s.path("files").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                files.put(e.getKey(), e.getValue().asText(""));
            }
            states.add(new RuntimeAlgorithm.State(s.path("state").asText(""), s.path("dir").asText(""),
                    s.path("action").asText(null), intOrNull(s.get("attnLeft")), intOrNull(s.get("attnTop")),
                    intOrNull(s.get("actLeft")), intOrNull(s.get("actTop")),
                    s.path("width").asInt(0), s.path("height").asInt(0), files));
        }
        if (feats.isEmpty() || states.isEmpty()) {
            return null;
        }
        // 兼容改动前写下的 algorithm.json（那时把权重 Y = 0 的特征也一并落地了）：读的时候同样只认生效特征，
        // 识别只按它们比对与加权；没生效的那几张产物留着不用，下次算法调优刷新时由 purge 清掉
        List<RuntimeAlgorithm.Feature> eff = RuntimeAlgorithm.effective(feats);
        return new RuntimeAlgorithm(r.path("algoId").asText(""), r.path("algoName").asText(""),
                r.path("accuracy").asDouble(0), r.path("tieRate").asDouble(0), r.path("score").asDouble(0),
                r.path("builtMs").asLong(0), r.path("costMs").asLong(0), r.path("source").asText(""),
                List.copyOf(eff), List.copyOf(states));
    }

    private static Integer intOrNull(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.intValue();
    }
}
