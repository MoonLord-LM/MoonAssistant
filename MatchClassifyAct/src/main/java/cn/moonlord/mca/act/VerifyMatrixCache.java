package cn.moonlord.mca.act;

import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 逐图比对结果缓存（{@value #CACHE_FILE}）：把特征验证算出的「每张原图 × 每个分类同类产物」的不匹配占比
 * 按<b>原图 + 产物各自的名称 / 大小 / 修改时间</b>记账，算法调优与下一次验证直接取用，不必再把同一批图重比一遍。
 *
 * <p>特征验证的 B/C/D/E 与算法调优的特征矩阵都由这份 [样本][分类] 矩阵派生，而一张矩阵要逐点比对
 * sn × gn 次（几分钟）。所以这里按两层键记账：
 * <ul>
 * <li><b>列</b>（{@link ColKey}）= 该特征在每个分类目录下的产物文件与 info.json 的「大小 + 修改时间」
 * （外加尺寸、注意点、点击点）：这些没变，就说明产物像素与裁剪框心没变，整张表的列都能用；</li>
 * <li><b>行</b>（{@link Row}）= 原图文件名 + 大小 + 修改时间 + 该原图所属分类的宽高：对得上的行直接取用，
 * 连原图 PNG 都不解码。</li>
 * </ul>
 * 增删改任意一张图只会让受影响的表 / 行重算，其余照旧复用；内存里也一直是同一份（同进程内
 * 特征验证跑完紧接着调优，矩阵直接在内存里命中，一次都不重算）。<b>比对口径改了</b>
 * （{@link FrameClassifier} 的 compareKind / cropPixels 等）要 bump {@link #VERSION}，否则旧数值会被
 * 当成新口径复用；文件可随时手删（删了只是重算一遍）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyMatrixCache {

    private final StoragePaths storage;

    /** 缓存文件名（放 resource/summary/ 根，与产物目录并列：可手删、随 *.json 一并被 git 忽略）。 */
    public static final String CACHE_FILE = "verify-matrix.json";

    /** 比对口径版本：算分逻辑（compareKind / 裁剪 / 是否跳过等）变了要 +1，否则旧数值会被当新口径复用。 */
    private static final int VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** kind → 该特征的整表（列键 + 每列解码 / 有效产物标记 + 每个样本的分值行）。 */
    private final Map<String, Entry> kinds = new ConcurrentHashMap<>();

    private volatile boolean loaded;
    private volatile boolean restored;   // 本进程的表来自磁盘缓存（界面靠进度行的「已缓存 N 个特征 / M 条样本比对行」体现）
    private volatile boolean dirty;      // 本进程算过新行 / 换过列（落盘时才写文件）
    private volatile long savedMs;

    /** 一列的键：该特征在某分类目录下的产物 + info.json（尺寸与裁剪框心都在 info.json 里）。 */
    public record ColKey(String state, int w, int h,
                         Integer attnLeft, Integer attnTop, Integer actLeft, Integer actTop,
                         long artMtime, long artSize, long infoMtime, long infoSize) {

        /** 该分类连产物文件都没有（artMtime < 0）：与「产物存在但全透明」同口径判不匹配，但明细里要区分展示。 */
        public boolean missing() {
            return artMtime < 0;
        }
    }

    /** 一行（原图）的键与值：s = 与各列产物的不匹配占比（<0 = 比不了，如分辨率不符）。 */
    public record Row(long mtime, long size, int w, int h, double[] s) {
    }

    private static final class Entry {
        final List<ColKey> cols;
        final boolean[] decoded;
        final boolean[] valid;
        final Map<String, Row> rows = new ConcurrentHashMap<>();

        Entry(List<ColKey> cols, boolean[] decoded, boolean[] valid) {
            this.cols = cols;
            this.decoded = decoded;
            this.valid = valid;
        }
    }

    /** 一个特征的整表视图：列键已经对得上才拿得到；取行 / 记行走它（并行流里也会用，内部是并发容器）。 */
    public final class Table {
        private final Entry entry;

        private Table(Entry entry) {
            this.entry = entry;
        }

        /** 列数（分类个数）。 */
        public int cols() {
            return entry.cols.size();
        }

        /** 该分类此特征是否解出了产物（文件在且解得开）；没有产物 / 解码失败 = false。 */
        public boolean decoded(int j) {
            return entry.decoded[j];
        }

        /** 该分类此特征是否生成了有效产物（存在不透明像素）：与特征验证 {@code Cand.valid} 同口径。 */
        public boolean valid(int j) {
            return entry.valid[j];
        }

        /** 复用某张原图算过的整行（原图与所属分类尺寸都对得上才给，否则 null）→ 命中就不必再解码这张原图。
         *  返回的是缓存里的数组本身，调用方只读。 */
        public double[] row(String file, long mtime, long size, int w, int h) {
            Row r = entry.rows.get(file);
            if (r == null || r.mtime() != mtime || r.size() != size || r.w() != w || r.h() != h
                    || r.s().length != entry.cols.size()) {
                return null;
            }
            return r.s();
        }

        /** 记下刚算出来的整行（键里任何一项变了都算新行；长度不符 = 过期结果，直接丢弃）。 */
        public void fill(String file, long mtime, long size, int w, int h, double[] s) {
            if (s == null || s.length != entry.cols.size()) {
                return;
            }
            Row old = entry.rows.get(file);
            if (old != null && old.mtime() == mtime && old.size() == size && old.w() == w && old.h() == h) {
                return;
            }
            entry.rows.put(file, new Row(mtime, size, w, h, s));
            dirty = true;
        }
    }

    // ---------------------------------------------------------------- 对外

    /** 缓存文件：resource/summary/verify-matrix.json。 */
    public Path file() {
        return storage.summary().resolve(CACHE_FILE);
    }

    /** 取某 kind 的整表（列键全对得上才复用；null = 该特征要从头算）。 */
    public Table table(String kind, List<ColKey> cols) {
        ensureLoaded();
        Entry e = kinds.get(kind);
        if (e == null || !e.cols.equals(cols)) {
            return null;
        }
        return new Table(e);
    }

    /** 从头算某 kind：用新的列键与「解码成功 / 有效产物」标记建表（旧行一律作废，算出来的行再 {@link Table#fill} 回去）。 */
    public Table begin(String kind, List<ColKey> cols, boolean[] decoded, boolean[] valid) {
        ensureLoaded();
        Entry e = new Entry(cols, decoded, valid);
        kinds.put(kind, e);
        dirty = true;
        return new Table(e);
    }

    /** 读产物 / info.json 的「大小 + 修改时间」建列键（产物不存在 → artMtime = -1）。 */
    public static ColKey colKey(String state, int w, int h, Integer attnLeft, Integer attnTop,
                               Integer actLeft, Integer actTop, Path art, Path info) {
        long[] a = stat(art);
        long[] i = stat(info);
        return new ColKey(state, w, h, attnLeft, attnTop, actLeft, actTop, a[0], a[1], i[0], i[1]);
    }

    private static long[] stat(Path p) {
        try {
            BasicFileAttributes at = Files.readAttributes(p, BasicFileAttributes.class);
            return new long[]{at.lastModifiedTime().toMillis(), at.size()};
        } catch (IOException e) {
            return new long[]{-1, 0};   // 不存在 / 读不到 → 与「没有产物」同键（内容变了必然会被识别出来）
        }
    }

    /** 把内存里的全部表完整落盘（一次完整跑完后调用；先写 .tmp 再原子改名）。没有新算出来的行就不写。 */
    public synchronized void save(String fp) {
        if (!loaded || !dirty) {
            return;
        }
        Path f = file();
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            Files.createDirectories(f.getParent());
            try (OutputStream os = Files.newOutputStream(tmp);
                 JsonGenerator g = JSON.getFactory().createGenerator(os)) {
                writeJson(g, fp);
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            savedMs = System.currentTimeMillis();
            log.info("逐图比对结果已缓存：{}（{} 种特征 · {} 条样本行）", f, kinds.size(), rows());
        } catch (IOException e) {
            log.warn("逐图比对结果缓存写入失败 {}：{}", f, e.toString());
        }
    }

    /** 缓存概况（供状态接口 / 界面提示）：文件名、已载入的特征与样本行数、文件在不在。 */
    public Map<String, Object> status() {
        Path f = file();
        long bytes = 0;
        try {
            bytes = Files.isRegularFile(f) ? Files.size(f) : 0;
        } catch (IOException ignored) {
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("matrixFile", CACHE_FILE);
        o.put("matrixBytes", bytes);              // 文件在不在 / 多大（没载入也能显示）
        o.put("matrixLoaded", loaded);
        o.put("matrixRestored", restored);
        o.put("matrixKinds", loaded ? kinds.size() : 0);
        o.put("matrixRows", loaded ? rows() : 0);
        o.put("matrixSavedMs", savedMs);
        return o;
    }

    private int rows() {
        int n = 0;
        for (Entry e : kinds.values()) {
            n += e.rows.size();
        }
        return n;
    }

    // ---------------------------------------------------------------- 落盘 / 载入（流式读写：几十 MB 的矩阵不适合整棵读进内存）

    private void writeJson(JsonGenerator g, String fp) throws IOException {
        g.writeStartObject();
        g.writeNumberField("version", VERSION);
        g.writeStringField("fp", fp == null ? "" : fp);   // 计算时的样本/产物指纹（仅供对照，复用判据看每张图的键）
        g.writeNumberField("savedMs", System.currentTimeMillis());
        g.writeObjectFieldStart("kinds");
        Set<String> order = new LinkedHashSet<>(ArtifactKind.kinds());
        order.addAll(kinds.keySet());
        for (String kind : order) {
            Entry e = kinds.get(kind);
            if (e == null) {
                continue;
            }
            g.writeObjectFieldStart(kind);
            g.writeArrayFieldStart("cols");
            for (ColKey c : e.cols) {
                g.writeStartArray();
                g.writeString(c.state());
                g.writeNumber(c.w());
                g.writeNumber(c.h());
                writeIntOrNull(g, c.attnLeft());
                writeIntOrNull(g, c.attnTop());
                writeIntOrNull(g, c.actLeft());
                writeIntOrNull(g, c.actTop());
                g.writeNumber(c.artMtime());
                g.writeNumber(c.artSize());
                g.writeNumber(c.infoMtime());
                g.writeNumber(c.infoSize());
                g.writeEndArray();
            }
            g.writeEndArray();
            g.writeArrayFieldStart("decoded");
            for (boolean b : e.decoded) {
                g.writeBoolean(b);
            }
            g.writeEndArray();
            g.writeArrayFieldStart("valid");
            for (boolean b : e.valid) {
                g.writeBoolean(b);
            }
            g.writeEndArray();
            g.writeObjectFieldStart("rows");
            for (Map.Entry<String, Row> r : e.rows.entrySet()) {
                g.writeArrayFieldStart(r.getKey());
                g.writeNumber(r.getValue().mtime());
                g.writeNumber(r.getValue().size());
                g.writeNumber(r.getValue().w());
                g.writeNumber(r.getValue().h());
                g.writeStartArray();
                for (double v : r.getValue().s()) {
                    g.writeNumber(v);   // 原样写双精度（可无损读回，保证「并列」判定与重算完全一致）
                }
                g.writeEndArray();
                g.writeEndArray();
            }
            g.writeEndObject();
            g.writeEndObject();
        }
        g.writeEndObject();
        g.writeEndObject();
    }

    private static void writeIntOrNull(JsonGenerator g, Integer v) throws IOException {
        if (v == null) {
            g.writeNull();
        } else {
            g.writeNumber(v.intValue());
        }
    }

    /** 首次用到时从缓存文件载入（幂等；文件不存在 / 版本或解析不对只记日志并把整份丢弃）。 */
    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try (JsonParser p = JSON.getFactory().createParser(f.toFile())) {
            int ver = -1;
            if (p.nextToken() == JsonToken.START_OBJECT) {
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    p.nextToken();
                    if ("version".equals(name)) {
                        ver = p.getIntValue();
                    } else if ("savedMs".equals(name)) {
                        savedMs = p.getLongValue();
                    } else if ("kinds".equals(name)) {
                        if (ver == VERSION) {
                            readKinds(p);
                        } else {
                            p.skipChildren();
                        }
                    } else {
                        p.skipChildren();
                    }
                }
            }
            if (ver != VERSION) {
                kinds.clear();
                log.warn("逐图比对缓存的比对口径版本不符（文件 {} ≠ 当前 {}）：整份丢弃重算 {}", ver, VERSION, f);
                return;
            }
            restored = !kinds.isEmpty();
            if (restored) {
                log.info("逐图比对结果已从缓存载入（{}）：{} 种特征 · {} 条样本行；对得上的图直接复用",
                        f, kinds.size(), rows());
            }
        } catch (Exception e) {
            kinds.clear();
            restored = false;
            log.warn("逐图比对缓存读取失败 {}：{}", f, e.toString());
        }
    }

    private void readKinds(JsonParser p) throws IOException {
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String kind = p.currentName();
            p.nextToken();
            if (p.currentToken() != JsonToken.START_OBJECT) {
                p.skipChildren();
                continue;
            }
            List<ColKey> cols = new ArrayList<>();
            boolean[] decoded = null;
            boolean[] valid = null;
            Map<String, Row> rows = new ConcurrentHashMap<>();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                p.nextToken();
                if ("cols".equals(name)) {
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        cols.add(readCol(p));
                    }
                } else if ("decoded".equals(name)) {
                    decoded = readBools(p);
                } else if ("valid".equals(name)) {
                    valid = readBools(p);
                } else if ("rows".equals(name)) {
                    readRows(p, rows);
                } else {
                    p.skipChildren();
                }
            }
            if (cols.isEmpty() || decoded == null || valid == null
                    || decoded.length != cols.size() || valid.length != cols.size()) {
                continue;
            }
            Entry e = new Entry(cols, decoded, valid);
            e.rows.putAll(rows);
            kinds.put(kind, e);
        }
    }

    private static ColKey readCol(JsonParser p) throws IOException {
        String state = "";
        int w = 0;
        int h = 0;
        Integer al = null;
        Integer at = null;
        Integer cl = null;
        Integer ct = null;
        long am = -1;
        long as = 0;
        long im = 0;
        long is = 0;
        int i = 0;
        while (p.nextToken() != JsonToken.END_ARRAY) {
            boolean nil = p.currentToken() == JsonToken.VALUE_NULL;
            switch (i) {
                case 0 -> state = nil ? "" : p.getText();
                case 1 -> w = p.getIntValue();
                case 2 -> h = p.getIntValue();
                case 3 -> al = nil ? null : p.getIntValue();
                case 4 -> at = nil ? null : p.getIntValue();
                case 5 -> cl = nil ? null : p.getIntValue();
                case 6 -> ct = nil ? null : p.getIntValue();
                case 7 -> am = p.getLongValue();
                case 8 -> as = p.getLongValue();
                case 9 -> im = p.getLongValue();
                case 10 -> is = p.getLongValue();
                default -> {
                }
            }
            i++;
        }
        return new ColKey(state, w, h, al, at, cl, ct, am, as, im, is);
    }

    private static boolean[] readBools(JsonParser p) throws IOException {
        List<Boolean> out = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            out.add(p.currentToken() == JsonToken.VALUE_TRUE);
        }
        boolean[] a = new boolean[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }

    private static double[] readDoubles(JsonParser p) throws IOException {
        List<Double> out = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            out.add(p.getDoubleValue());
        }
        double[] a = new double[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }

    private static void readRows(JsonParser p, Map<String, Row> out) throws IOException {
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String file = p.currentName();
            p.nextToken();
            if (p.currentToken() != JsonToken.START_ARRAY) {
                p.skipChildren();
                continue;
            }
            long mt = 0;
            long sz = 0;
            int w = 0;
            int h = 0;
            double[] s = null;
            int i = 0;
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (i == 0) {
                    mt = p.getLongValue();
                } else if (i == 1) {
                    sz = p.getLongValue();
                } else if (i == 2) {
                    w = p.getIntValue();
                } else if (i == 3) {
                    h = p.getIntValue();
                } else if (i == 4 && p.currentToken() == JsonToken.START_ARRAY) {
                    s = readDoubles(p);
                }
                i++;
            }
            if (s != null) {
                out.put(file, new Row(mt, sz, w, h, s));
            }
        }
    }
}
