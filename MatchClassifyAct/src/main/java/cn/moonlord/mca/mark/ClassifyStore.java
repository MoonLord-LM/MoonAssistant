package cn.moonlord.mca.mark;

import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
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
 * <p><b>数据模型（schema=1）</b>：同一分类标注的动作与“鼠标点击点 / 注意点”是“分类级定义”，与具体样本无关，
 * 只在 <code>resource/classify/data.json</code> 保存一份；每张样本图旁的 json 只记它的分类归属，不再逐张复制坐标。
 * 两个点互相独立：left/top = 鼠标点击点（仅 click 分类，执行时真正点击的位置，也是点击区交集图框心）；
 * attnLeft/attnTop = 注意点（任意分类可选，注意区图/匹配裁剪以它为中心；<b>全部分类一致的默认值
 * = 屏幕中心</b>，不再回退点击点）。汇总分析分别以注意点、点击点为框心生成两套方框交集图：</p>
 * <pre>
 * resource/classify/data.json
 *   { "schema": 1, "states": {
 *       "登录页": { "action": "click", "left": 640, "top": 360, "attnLeft": 640, "attnTop": 360 },
 *       "加载中": { "action": "none", "attnLeft": 640, "attnTop": 360 } } }
 * resource/classify/IMG_x.png        样本截图
 * resource/classify/IMG_x.json       { "state": "登录页" }        // 仅归属，动作坐标查 data.json
 * </pre>
 *
 * <p><b>读取样本</b>：{@link #readSample(String)} 以“样本 json 的 state + 中心表定义”合成完整标注，
 * 因此对 API 与页面保持原来的字段形状（state/action/left/top/attnLeft/attnTop），只是数据不再逐图冗余。</p>
 *
 * <p><b>兼容与迁移</b>：历史版本是“每张图 json 自带 action/left/top”全量写法。首次访问本服务时
 * （懒迁移）会扫描 resource/classify/ 下的旧 json，按分类取<b>众数</b>动作/坐标归纳出 data.json
 * （与界面“智能带入多数点”的口径一致；同分类里个别不一致的历史异位点不会带偏），
 * 之后把旧样本 json 就地瘦身为仅 {state}。读取路径始终以 data.json 为准，
 * 若某分类未建定义则回退样本 json 自带字段（兼容归纳前 / 归纳遗漏的旧文件）。
 * 旧版只有单个“关注点”left/top：无动作分类该点实为注意点 → 启动迁移到 attn 字段并清空 left/top；
 * click 分类的 left/top 语义不变（注意点缺省 = 屏幕中心，见 {@link CaptureMark}）。</p>
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

    /** 单条分类定义：动作 + 鼠标点击点 + 注意点（均属于分类而非样本）。
     *  click 分类：left/top = 点击位置；无动作分类：left/top 恒为 null（无点击）。
     *  attnLeft/attnTop = 注意点（可选；未设 = 默认屏幕中心，全部分类一致，不再回退点击点）。 */
    @Data
    public static class ClassDef {
        private String action = CaptureMark.ACTION_NONE;
        private Integer left;
        private Integer top;
        private Integer attnLeft;
        private Integer attnTop;
    }

    /** resource/classify/data.json 的结构 */
    @Data
    public static class DataFile {
        private int schema = 1;
        private Map<String, ClassDef> states = new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------ 表路径 / IO

    private Path dataFile() {
        return storage.classify().resolve(DATA_FILE);
    }

    /** 样本 json 的固定位置：resource/classify/&lt;png 名去扩展名&gt;.json（与图是否仍在 resource/capture/ 无关） */
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
     * 首次使用时执行一次：resource/classify/data.json 不存在 → 从 resource/classify/ 旧样本 json 归纳众数定义并落盘，
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
                // 关注点坐标（click=点击点 / 无动作=画面关注区域）：任一动作都解析
                Integer left = node.path("left").isIntegralNumber() ? node.path("left").asInt() : null;
                Integer top = node.path("top").isIntegralNumber() ? node.path("top").asInt() : null;
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
                // 旧样本 json 的单点：click = 点击点存 left/top；none = 画面关注点（即新模型的注意点）
                if (CaptureMark.ACTION_CLICK.equals(best.action())) {
                    cd.setLeft(best.left());
                    cd.setTop(best.top());
                } else {
                    cd.setAttnLeft(best.left());
                    cd.setAttnTop(best.top());
                }
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

    /** resource/classify/ 下 IMG_*.png（已标注样本），按文件名排序 */
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
            log.warn("枚举 resource/classify/ 目录失败: {}", e.toString());
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
                m.setAttnLeft(cd.getAttnLeft());
                m.setAttnTop(cd.getAttnTop());
            }
        }
        return m;
    }

    /** 便捷：按 png 路径读取样本标注（路径目录不限，按文件名定位 resource/classify/ 下 json） */
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

    /** 某分类当前实际拥有的已标注样本数（样本 json 归属计数；与中心表定义是否残留无关） */
    public synchronized int sampleCount(String state) {
        ensureMigrated();
        String st = state == null ? "" : state.trim();
        if (st.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Path png : listClassifiedPngs()) {
            CaptureMark m = readSample(png.getFileName().toString());
            if (m != null && st.equals(trim(m.getState()))) {
                n++;
            }
        }
        return n;
    }

    /** 全部已定义分类（含尚无样本图的定义），按分类名排序，供列表 / 前端提示使用 */
    public synchronized List<CaptureMark> definitions() {
        ensureMigrated();
        List<CaptureMark> out = new ArrayList<>();
        table().getStates().forEach((st, cd) -> out.add(toMark(st, cd)));
        out.sort(Comparator.comparing(CaptureMark::getState, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    /** 一次性补齐历史「无动作」分类缺失的注意点坐标（幂等：仅处理 attn 为空的非 click 定义），
     *  按该分类首张样本图分辨率的屏幕中心补全，使点击区图可对全部分类统一生成
     *  （click=鼠标点击点 + 注意点；无动作=注意点，默认屏幕中心）。样本无可读尺寸时跳过并告警。
     *
     *  @return 本次补齐的分类数（未动任何数据时返回 0）
     */
    public synchronized int backfillNonePointsToCenter() throws IOException {
        ensureMigrated();
        DataFile d = table();
        int n = 0;
        for (Map.Entry<String, ClassDef> e : d.getStates().entrySet()) {
            ClassDef cd = e.getValue();
            if (cd == null || CaptureMark.ACTION_CLICK.equals(cd.getAction())
                || (cd.getAttnLeft() != null && cd.getAttnTop() != null)) {
                continue;
            }
            int[] wh = sampleDimOf(e.getKey());
            if (wh == null || wh[0] <= 0 || wh[1] <= 0) {
                log.warn("一次性补齐注意点：无动作分类「{}」没有可读尺寸的样本，无法推断屏幕中心，跳过", e.getKey());
                continue;
            }
            int cx = wh[0] / 2;
            int cy = wh[1] / 2;
            cd.setAttnLeft(cx);
            cd.setAttnTop(cy);
            cd.setLeft(null);   // 无动作分类没有点击点
            cd.setTop(null);
            n++;
            log.info("一次性补齐无动作分类「{}」注意点 → 屏幕中心 ({},{}，画幅 {}×{})", e.getKey(), cx, cy, wh[0], wh[1]);
        }
        if (n > 0) {
            saveData(d);
            log.info("一次性补齐注意点完成：为 {} 个无动作分类写入屏幕中心默认点", n);
        }
        return n;
    }

    /** 某分类任意一张已标注样本图的像素尺寸（PNG 宽高，只读文件头不整幅解码）；无样本或读失败返回 null */
    private int[] sampleDimOf(String state) {
        for (Path png : listClassifiedPngs()) {
            CaptureMark m = readSample(png.getFileName().toString());
            if (m == null || !state.equals(trim(m.getState()))) {
                continue;
            }
            int[] wh = pngSize(png);
            if (wh != null) {
                return wh;
            }
        }
        return null;
    }

    /** 读 PNG 文件头 IHDR 的宽高；非 PNG 或读失败返回 null */
    private static int[] pngSize(Path p) {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(p))) {
            byte[] sig = new byte[8];
            in.readFully(sig);
            if ((sig[0] & 0xff) != 0x89 || sig[1] != 'P' || sig[2] != 'N' || sig[3] != 'G') {
                return null;
            }
            in.readInt();   // IHDR 数据长度
            if (in.readInt() != 0x49484452) {
                return null;   // 块类型不是 "IHDR"
            }
            int w = in.readInt();
            int h = in.readInt();
            return w > 0 && h > 0 ? new int[] { w, h } : null;
        } catch (IOException e) {
            return null;
        }
    }

    private CaptureMark toMark(String state, ClassDef cd) {
        CaptureMark m = new CaptureMark();
        m.setState(state);
        m.setAction(cd.getAction());
        m.setLeft(cd.getLeft());
        m.setTop(cd.getTop());
        m.setAttnLeft(cd.getAttnLeft());
        m.setAttnTop(cd.getAttnTop());
        return m;
    }

    // ------------------------------------------------------------------ 写

    /** 保存一张样本图：样本 json 只写 {state}；动作/坐标以中心表定义为准，不写进单图 */
    public synchronized void saveSample(String imageFile, String state) throws IOException {
        ensureMigrated();
        atomicWrite(Map.of("state", state == null ? "" : state.trim()), sampleJson(imageFile));
    }

    /** 建立 / 覆盖某分类的定义（动作 + 鼠标点击点 + 注意点），返回落盘后的完整定义。
     *  调用方负责口径：click 分类必须给非负 left/top（注意点可选）；none 分类 left/top 传 null、须给 attn。 */
    public synchronized CaptureMark define(String state, String action,
        Integer left, Integer top, Integer attnLeft, Integer attnTop) throws IOException {
        ensureMigrated();
        String st = state.trim();
        ClassDef cd = new ClassDef();
        cd.setAction(action);
        cd.setLeft(left);
        cd.setTop(top);
        cd.setAttnLeft(attnLeft);
        cd.setAttnTop(attnTop);
        DataFile d = table();
        d.getStates().put(st, cd);
        saveData(d);
        log.debug("分类定义已写入 data.json：{} = {}/点击点 {},{}/注意点 {},{}", st, action, left, top, attnLeft, attnTop);
        return toMark(st, cd);
    }

    /** 语义规整（每次进程首访时执行，幂等）：无动作分类历史只存了单个关注点 left/top（实为注意点）
     *  → 迁到 attn 字段并清空 left/top；click 分类注意点未设即维持 null（回退点击点）。 */
    private synchronized void normalizeDefs() {
        DataFile d = table();
        boolean changed = false;
        for (Map.Entry<String, ClassDef> e : d.getStates().entrySet()) {
            ClassDef cd = e.getValue();
            if (cd == null) {
                continue;
            }
            if (!CaptureMark.ACTION_CLICK.equals(cd.getAction())) {
                if (cd.getAttnLeft() == null && cd.getAttnTop() == null
                    && cd.getLeft() != null && cd.getTop() != null) {
                    cd.setAttnLeft(cd.getLeft());
                    cd.setAttnTop(cd.getTop());
                    cd.setLeft(null);
                    cd.setTop(null);
                    changed = true;
                    log.info("分类「{}」迁移：无动作分类的旧关注点坐标 → 注意点 ({},{})", e.getKey(), cd.getAttnLeft(), cd.getAttnTop());
                }
            }
        }
        if (changed) {
            try {
                saveData(d);
            } catch (IOException ex) {
                log.error("写分类定义迁移失败（内存表仍生效，重启后重试）：{}", ex.toString());
            }
        }
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

    /** 某分类已无任何已标注样本时移除其在 data.json 的残留定义（空定义清理）；仍有样本则不动 */
    public synchronized boolean removeDefinitionIfVacant(String state) throws IOException {
        ensureMigrated();
        String st = trim(state);
        DataFile d = table();
        if (st.isEmpty() || !d.getStates().containsKey(st) || sampleCount(st) > 0) {
            return false;
        }
        d.getStates().remove(st);
        saveData(d);
        log.info("分类标注「{}」样本已清零，清理 data.json 空定义", st);
        return true;
    }

    /** 启动/首次访问前的幂等初始化（懒迁移 + 语义规整，内部自动执行一次，通常无需外部调用） */
    public synchronized void ensureMigrated() {
        if (migrated) {
            return;
        }
        migrateOnce();
        normalizeDefs();   // 旧单点模型 → 点击点/注意点双点语义
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
