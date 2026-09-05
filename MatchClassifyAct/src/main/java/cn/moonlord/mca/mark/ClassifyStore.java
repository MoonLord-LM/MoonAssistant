package cn.moonlord.mca.mark;

import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 分类标注中心表 + 样本标注读写（分类 → 动作/坐标的单一事实来源）。
 *
 * <p><b>数据模型（schema=1）</b>：同一分类标注的动作与坐标是“分类级定义”，与具体样本无关，
 * 只在 <code>classify/data.json</code> 保存一份；每张样本图旁的 json 只记它的分类归属，不再逐张复制坐标：</p>
 * <pre>
 * classify/data.json
 *   { "schema": 1, "states": { "登录页": { "action": "click", "left": 640, "top": 360 } } }
 * classify/IMG_x.png        样本截图
 * classify/IMG_x.json       { "state": "登录页" }        // 仅归属，动作坐标查 data.json
 * </pre>
 *
 * <p><b>读取样本</b>：{@link #readSample(String)} 以“样本 json 的 state + 中心表定义”合成完整标注，
 * 因此对 API 与页面保持原来的字段形状（state/action/left/top），只是数据不再逐图冗余。</p>
 *
 * <p><b>兼容与迁移</b>：历史版本是“每张图 json 自带 action/left/top”全量写法。首次访问本服务时
 * （懒迁移）会扫描 classify/ 下的旧 json，按分类取<b>众数</b>动作/坐标归纳出 data.json
 * （与界面“智能带入多数点”的口径一致；同分类里个别不一致的历史异位点不会带偏），
 * 之后把旧样本 json 就地瘦身为仅 {state}。读取路径始终以 data.json 为准，
 * 若某分类未建定义则回退样本 json 自带字段（兼容归纳前 / 归纳遗漏的旧文件）。</p>
 */
@Slf4j
@Service
public class ClassifyStore {

    public static final String DATA_FILE = "data.json";

    private static final ObjectMapper JSON = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final StoragePaths storage;

    /** 表的内存缓存：任何写操作后随落盘刷新；外部手工改文件需重启后生效（本系统所有写都走这里） */
    private volatile DataFile table;
    private volatile boolean migrated = false;

    public ClassifyStore(StoragePaths storage) {
        this.storage = storage;
    }

    /** 单条分类定义：动作 + （click 时的）坐标；属于分类而非样本 */
    @Data
    public static class ClassDef {
        private String action = CaptureMark.ACTION_NONE;
        private Integer left;
        private Integer top;
    }

    /** classify/data.json 的结构 */
    @Data
    public static class DataFile {
        private int schema = 1;
        private Map<String, ClassDef> states = new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------ 表路径 / IO

    private Path dataFile() {
        return storage.classify().resolve(DATA_FILE);
    }

    /** 样本 json 的固定位置：classify/&lt;png 名去扩展名&gt;.json（与图是否仍在 capture/ 无关） */
    public Path sampleJson(String imageFile) {
        String n = imageFile == null ? "" : imageFile;
        if (n.toLowerCase().endsWith(".png")) {
            n = n.substring(0, n.length() - 4);
        }
        return storage.classify().resolve(n + ".json").normalize();
    }

    private void atomicWrite(Object value, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writeValueAsString(value));
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private DataFile load() {
        Path f = dataFile();
        if (!Files.isRegularFile(f)) {
            return new DataFile();
        }
        try {
            return JSON.readValue(f.toFile(), DataFile.class);
        } catch (IOException e) {
            // 损坏的核心数据不应被静默覆盖：保留原文件，仅本次按空表处理并显著告警
            log.error("读取分类定义表 {} 失败，本次按空定义处理（请不要删除/覆盖该文件以免丢失已标注坐标）：{}",
                f, e.toString());
            return new DataFile();
        }
    }

    private synchronized DataFile table() {
        if (table == null) {
            table = load();
        }
        return table;
    }

    private void saveData(DataFile d) throws IOException {
        atomicWrite(d, dataFile());
        table = d;
    }

    // ------------------------------------------------------------------ 懒迁移（旧版：每图 json 全量自带动作坐标）

    /**
     * 首次使用时执行一次：classify/data.json 不存在 → 从 classify/ 旧样本 json 归纳众数定义并落盘，
     * 再把旧 json 瘦身为 {state}；之后调用为空操作。
     */
    private synchronized void migrateOnce() {
        if (migrated) {
            return;
        }
        migrated = true;   // 先置位，避免重入；失败也只在日志暴露，不再重复扫描
        Path dir = storage.classify();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Path f = dataFile();
        if (Files.isRegularFile(f)) {
            table = load();
            return;
        }
        // key = 完整动作定义（动作 + 坐标），同一分类取出现次数最多者（并列取先出现）
        record DefKey(String action, Integer left, Integer top) {
        }
        Map<String, Map<DefKey, Integer>> votes = new LinkedHashMap<>();
        List<Path> pngs = listClassifiedPngs();
        for (Path png : pngs) {
            Path json = sampleJson(png.getFileName().toString());
            if (!Files.isRegularFile(json)) {
                continue;
            }
            JsonNode node;
            try {
                node = JSON.readTree(json.toFile());
            } catch (IOException e) {
                log.warn("归纳分类定义：跳过损坏标注 {}: {}", json, e.toString());
                continue;
            }
            String state = trim(node.path("state").asText());
            if (state.isEmpty()) {
                continue;
            }
            // 新格式（仅 {state}）无法推导动作坐标，不参与投票；data.json 缺失时这些样本的动作需重新定义
            if (node.has("action")) {
                String act = node.path("action").asText();
                if (!CaptureMark.ACTION_CLICK.equals(act) && !CaptureMark.ACTION_NONE.equals(act)) {
                    continue;
                }
                Integer left = null;
                Integer top = null;
                if (CaptureMark.ACTION_CLICK.equals(act)) {
                    left = node.path("left").isIntegralNumber() ? node.path("left").asInt() : null;
                    top = node.path("top").isIntegralNumber() ? node.path("top").asInt() : null;
                }
                votes.computeIfAbsent(state, k -> new LinkedHashMap<>())
                    .merge(new DefKey(act, left, top), 1, Integer::sum);
            }
        }
        DataFile d = new DataFile();
        int defs = 0;
        for (Map.Entry<String, Map<DefKey, Integer>> e : votes.entrySet()) {
            DefKey best = null;
            int bestN = 0;
            for (Map.Entry<DefKey, Integer> v : e.getValue().entrySet()) {
                if (v.getValue() > bestN) {   // 并列取先出现
                    bestN = v.getValue();
                    best = v.getKey();
                }
            }
            if (best != null) {
                ClassDef cd = new ClassDef();
                cd.setAction(best.action());
                cd.setLeft(best.left());
                cd.setTop(best.top());
                d.getStates().put(e.getKey(), cd);
                defs++;
            }
        }
        try {
            saveData(d);
        } catch (IOException ex) {
            log.error("写分类定义表失败（后续保存标注将按空表重新定义）：{}", ex.toString());
            return;
        }
        // 旧 json 就地瘦身：动作坐标已收敛进 data.json，样本 json 只保留归属
        int slimmed = 0, failed = 0, kept = 0;
        for (Path png : pngs) {
            Path json = sampleJson(png.getFileName().toString());
            if (!Files.isRegularFile(json)) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(json.toFile());
                String state = trim(node.path("state").asText());
                if (state.isEmpty() || (node.size() == 1 && node.has("state"))) {
                    kept++;    // 无效或已是新格式
                    continue;
                }
                atomicWrite(Map.of("state", state), json);
                slimmed++;
            } catch (IOException e) {
                failed++;
                log.warn("瘦身样本标注失败（读取仍兼容）：{}: {}", json, e.toString());
            }
        }
        log.info("分类定义中心表初始化完成：归纳 {} 个分类定义（众数），瘦身 {} 张旧标注 json（保留 {} / 失败 {}）",
            defs, slimmed, kept, failed);
    }

    /** classify/ 下 IMG_*.png（已标注样本），按文件名排序 */
    public List<Path> listClassifiedPngs() {
        List<Path> pngs = new ArrayList<>();
        Path dir = storage.classify();
        if (!Files.isDirectory(dir)) {
            return pngs;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return Files.isRegularFile(p) && n.startsWith("img_") && n.endsWith(".png");
            }).sorted(Comparator.comparing(p -> p.getFileName().toString()))
             .forEach(pngs::add);
        } catch (IOException e) {
            log.warn("枚举 classify/ 目录失败: {}", e.toString());
        }
        return pngs;
    }

    // ------------------------------------------------------------------ 读

    /** 读某张样本图的完整标注（样本 json 的 state + 中心表动作坐标合成）；json 缺失/损坏返回 null */
    public synchronized CaptureMark readSample(String imageFile) {
        ensureMigrated();
        Path json = sampleJson(imageFile);
        if (!Files.isRegularFile(json)) {
            return null;
        }
        CaptureMark m;
        try {
            m = JSON.readValue(json.toFile(), CaptureMark.class);
        } catch (IOException e) {
            log.warn("读取标注 {} 失败，按未标注处理: {}", json, e.toString());
            return null;
        }
        String st = trim(m.getState());
        if (!st.isEmpty()) {
            ClassDef cd = table().getStates().get(st);
            if (cd != null) {
                m.setState(st);
                m.setAction(cd.getAction());
                m.setLeft(cd.getLeft());
                m.setTop(cd.getTop());
            }
        }
        return m;
    }

    /** 便捷：按 png 路径读取样本标注（路径目录不限，按文件名定位 classify/ 下 json） */
    public CaptureMark sampleOf(Path png) {
        return png == null ? null : readSample(png.getFileName().toString());
    }

    /** 查某分类的定义；无定义返回 null */
    public synchronized CaptureMark definitionOf(String state) {
        ensureMigrated();
        if (state == null) {
            return null;
        }
        String st = state.trim();
        ClassDef cd = table().getStates().get(st);
        return cd == null ? null : toMark(st, cd);
    }

    /** 全部已定义分类（含尚无样本图的定义），按分类名排序，供列表 / 前端提示使用 */
    public synchronized List<CaptureMark> definitions() {
        ensureMigrated();
        List<CaptureMark> out = new ArrayList<>();
        table().getStates().forEach((st, cd) -> out.add(toMark(st, cd)));
        out.sort(Comparator.comparing(CaptureMark::getState, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    private CaptureMark toMark(String state, ClassDef cd) {
        CaptureMark m = new CaptureMark();
        m.setState(state);
        m.setAction(cd.getAction());
        m.setLeft(cd.getLeft());
        m.setTop(cd.getTop());
        return m;
    }

    // ------------------------------------------------------------------ 写

    /** 保存一张样本图：样本 json 只写 {state}；动作/坐标以中心表定义为准，不写进单图 */
    public synchronized void saveSample(String imageFile, String state) throws IOException {
        ensureMigrated();
        atomicWrite(Map.of("state", state == null ? "" : state.trim()), sampleJson(imageFile));
    }

    /** 建立 / 覆盖某分类的定义（动作 + 坐标），返回落盘后的完整定义 */
    public synchronized CaptureMark define(String state, String action, Integer left, Integer top)
        throws IOException {
        ensureMigrated();
        String st = state.trim();
        ClassDef cd = new ClassDef();
        cd.setAction(action);
        cd.setLeft(left);
        cd.setTop(top);
        DataFile d = table();
        d.getStates().put(st, cd);
        saveData(d);
        log.debug("分类定义已写入 data.json：{} = {}/{},{}/{}", st, action, left, top);
        return toMark(st, cd);
    }

    /**
     * 分类标注整体改名：中心表 key 改名 + 全部使用该分类的样本 json 的 state 改为新名。
     *
     * @return 实际改写的样本 json 数量
     * @throws IllegalStateException 业务冲突（无此分类 / 新名已被占用），消息可直接返回给页面
     */
    public synchronized int renameState(String from, String to) throws IOException {
        ensureMigrated();
        DataFile d = table();
        String f = from == null ? "" : from.trim();
        String t = to == null ? "" : to.trim();
        if (!d.getStates().containsKey(f)) {
            throw new IllegalStateException("没有分类标注「" + f + "」的定义，无需改名");
        }
        if (d.getStates().containsKey(t)) {
            throw new IllegalStateException("新名称「" + t + "」已有分类定义，无法直接改名（如需合并请先自行处理）");
        }
        List<String> toRewrite = new ArrayList<>();
        for (Path png : listClassifiedPngs()) {
            String name = png.getFileName().toString();
            CaptureMark m = readSample(name);
            String st = m == null ? "" : trim(m.getState());
            if (st.isEmpty()) {
                continue;
            }
            if (t.equals(st)) {
                throw new IllegalStateException("新名称「" + t + "」已被其他图片使用，无法直接改名（如需合并请先自行处理）");
            }
            if (f.equals(st)) {
                toRewrite.add(name);
            }
        }
        ClassDef cd = d.getStates().remove(f);
        d.getStates().put(t, cd);
        saveData(d);
        int updated = 0;
        for (String name : toRewrite) {
            saveSample(name, t);
            updated++;
        }
        return updated;
    }

    /** 启动/首次访问前的幂等初始化（懒迁移，内部自动执行一次，通常无需外部调用） */
    public synchronized void ensureMigrated() {
        if (migrated) {
            return;
        }
        migrateOnce();
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
