package cn.moonlord.mca.mark;

import cn.moonlord.mca.act.ArtifactKind;
import cn.moonlord.mca.capture.ScreenCaptureService;
import cn.moonlord.mca.capture.WindowCaptureTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用版本探针：控制台前端周期性轮询 {@code /api/app/meta}，
 * 比对 {@code codeTs}——服务端重新打包部署（代码更新）后数值变化，
 * 前端据此自动刷新页面以加载新版界面。
 *
 * <p>codeTs 取「最能代表代码构建时间」的文件修改时间：
 * ① 以单个 jar / 目录运行时取其 mtime；② 否则取 classpath 中
 * static/index.html 所在部署根（可执行 jar 取其外层 jar 的 mtime）；</p>
 *
 * <p>除版本探针外，meta 还携带截图任务的动态提示事件：
 * <ul>
 *   <li>{@code captureStopReason}：截图 resize 持续无法达标自动停止时，前端据此弹出错误提示；</li>
 *   <li>{@code shotNotice}（{at, kind, name, pct?}）：最近一次截图结果——成功保存（kind=saved，
 *       name 为新图文件名）或画面与已保存参考图的不一致像素点占比 ≤ 阈值被丢弃（kind=dup，name 为参考图文件名，
 *       pct 为画面与该参考图的不一致像素点占比、必然 ≤ 阈值）。每轮完成都记录、不节流，
 *       前端每 2s 轮询取走（截图节拍默认约 1s、可能快于轮询，轮询间隙内连续多条只展示最新一条，属单条替换预期行为）并以右下角轻提示即时展示；</li>
 *   <li>{@code shotLog}（seq > 请求参数 shotAfter 的全部截图结果数组）：仅当请求带 {@code shotAfter}
 *       （上次已取的最大 seq；-1 = 从头全量取）时返回。shotNotice 只是最近一条，轮询间隙被节流的
 *       中间结果在这里补齐，供前端「历史日志」完整回溯（内存保存、随服务重启清空）。
 *       {@code shotMaxSeq} = 当前已用的最大 seq：前端若发现本页基线已越过它，说明后端重启（seq 归零重计），下一轮自动重置基线重新全量。</li>
 *   <li>{@code savedSeq}：已成功保存截图的总次数。前端每 2 秒轮询 meta，发现它比上次大，
 *       说明刚有新截图落盘，随即静默刷新截图列表（保证截图保存后约 2 秒内界面可见）；</li>
 *   <li>{@code startupDedupNotice}（{at, threshold, scanned, removed, costMs, compared, reused, minDiff}）：本次启动的历史重复清理结果
 *       （自动截图与手动去重任一开启时执行）——按两个启用阈值中较低者与保留图
 *       全尺寸逐像素比对，不一致像素点占比 ≤ 阈值即删（近似但不相同的画面一律保留）。不论是否删除了图片，
 *       前端都会据此在右下角提示一次清理完成
 *       （有删除：删除重复 N 张；无删除：检查完成、未发现重复图片），并附判定量
 *       （compared = 本次真正逐像素比对次数、reused = 复用 {@link ScreenCaptureService} 去重比对缓存
 *       {@code dedup-cache.json} 省掉的次数、minDiff = 其中最低的不一致像素点占比，-1 = 无可比对的对）。</li>
 *   <li>{@code startupDedup}（{at, done, total, current, compared, reused, removed, costMs, running}）：启动历史重复清理的**进行态**
 *       快照。清理可能持续很久（历史全量逐像素比对，第二轮起大量命中缓存会明显变快），前端据此先提示一条
 *       「开始检查」，并在 running=true 期间显示一条一直刷新的进度消息（已判定 / 待判定张数、当前文件、
 *       已耗时、逐像素比对 / 复用历史比对结果的个数 —— 前端按**逗号串联、不套括号**拼成
 *       「正在检查重复图片：849/877 张，当前文件名：IMG_xxx.png，已耗时 8 秒，复用历史比对结果 N 个」）；running 变 false 后
 *       撤掉进度消息，最终结果仍由 {@code startupDedupNotice} 给出。</li>
 * </ul></p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AppMetaController {

    private final WindowCaptureTask windowCaptureTask;
    private final ScreenCaptureService screenCaptureService;

    private final long startedAt = System.currentTimeMillis();
    private final long codeTs = detectCodeTimestamp();

    @GetMapping("/api/app/meta")
    public Map<String, Object> meta(@RequestParam(name = "shotAfter", defaultValue = "-2") long shotAfter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codeTs", codeTs);
        m.put("startedAt", startedAt);
        m.put("savedSeq", windowCaptureTask.getSavedSeq());
        m.put("shotMaxSeq", windowCaptureTask.getShotSeq());   // 当前已用的最大截图结果 seq（重启归零重计，供前端重置历史日志基线）
        String reason = windowCaptureTask.getAutoStopReason();
        if (reason != null) {
            m.put("captureStopReason", reason);   // 非空 = 截图任务刚因 resize 持续不成功而自动暂停
        }
        WindowCaptureTask.ShotNotice shot = windowCaptureTask.getShotNotice();
        if (shot != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("at", shot.at);
            r.put("kind", shot.kind);
            r.put("name", shot.name);
            if ("dup".equals(shot.kind)) {
                r.put("pct", shot.diffPercent);   // dup：画面与该参考图的不一致像素点占比（%），必然 ≤ 阈值；saved 不带
                r.put("refState", shot.refState); // dup：参考图所属分类（classify/ 已标注样本），capture/ 未标注图为 null
                r.put("threshold", shot.threshold); // dup：本次判重阈值（%）
            }
            m.put("shotNotice", r);               // 非空 = 最近一次截图结果（成功保存 / 差异过小丢弃），前端即时轻提示
        }
        // 历史日志增量：请求方带 shotAfter（上次已并入日志的最大 seq，-1 = 从第一条全量取），
        // 返回该序号之后新增的全部截图结果——shotNotice 只保留“最近一条”，轮询间隙被节流的中间条只能靠这里补齐
        if (shotAfter >= -1) {
            List<WindowCaptureTask.ShotNotice> log = windowCaptureTask.shotHistorySince(shotAfter);
            if (!log.isEmpty()) {
                List<Map<String, Object>> arr = new ArrayList<>(log.size());
                for (WindowCaptureTask.ShotNotice s : log) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("seq", s.seq);
                    r.put("at", s.at);
                    r.put("kind", s.kind);
                    r.put("name", s.name);
                    if ("dup".equals(s.kind)) {
                        r.put("pct", s.diffPercent);   // dup 才带：画面与该参考图的不一致像素点占比（%）
                        r.put("refState", s.refState); // dup 才带：参考图所属分类（capture/ 未标注图为 null）
                        r.put("threshold", s.threshold); // dup 才带：本次判重阈值（%）
                    }
                    arr.add(r);
                }
                m.put("shotLog", arr);
            }
        }
        // 启动历史重复清理结果：无论是否删除都提示一次，removed 供前端区分「删除重复 N 张 / 检查完成未发现重复」，
        // compared / reused / minDiff 供前端补一句「逐像素比对 N 个，复用历史比对结果 M 个，最低不一致 X%」（minDiff < 0 = 没有可比对的对、
        // reused > 0 = 这些比对里有多少是复用 dedup-cache.json 已存结果、没再逐像素重算）
        ScreenCaptureService.StartupDedupNotice dedup = screenCaptureService.getStartupDedupNotice();
        if (dedup != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("at", dedup.at());
            d.put("threshold", dedup.threshold());
            d.put("scanned", dedup.scanned());
            d.put("removed", dedup.removed());
            d.put("costMs", dedup.costMs());
            d.put("compared", dedup.compared());
            d.put("reused", dedup.reused());
            d.put("minDiff", dedup.minDiff());
            m.put("startupDedupNotice", d);
        }
        // 启动历史重复清理进行态：running=true 期间页面显示一条一直刷新的进度消息（含已耗时）；
        // 收尾后 running=false，页面撤掉进度消息，最终结果由上面的 startupDedupNotice 给出
        ScreenCaptureService.StartupDedupProgress dp = screenCaptureService.getStartupDedupProgress();
        if (dp != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("at", dp.at());
            d.put("done", dp.done());
            d.put("total", dp.total());
            d.put("current", dp.current());
            d.put("compared", dp.compared());
            d.put("reused", dp.reused());
            d.put("removed", dp.removed());
            d.put("costMs", dp.costMs());
            d.put("running", dp.running());
            m.put("startupDedup", d);
        }
        return m;
    }

    /**
     * 产物 kind 元数据：唯一来源 = {@link ArtifactKind}。前端据此动态生成对照图卡片与文案，
     * 加 / 改 / 删一个产物 kind 只需改注册表，前端零改动。
     *
     * <p>{@code kinds} = 各分类共用的 42/54 个识别比对维度；{@code all} = 固定第一条「全部」
     * 汇总组专用的 12 张产物（交集图六档 6 张 + 多数/均值/去重均值 3 族各「代表图 + 差异最大图」），
     * 两者分开下发。</p>
     */
    @GetMapping("/api/app/kinds")
    public Map<String, Object> kinds() {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (ArtifactKind.Def d : ArtifactKind.all()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", d.kind());
            k.put("file", d.file());
            k.put("label", ArtifactKind.label(d.kind()));   // 卡片标题（.tn）
            k.put("family", d.family().name());
            k.put("crop", d.crop().name());
            k.put("block", d.block());                      // 1 = 全幅
            k.put("div", d.cropDiv());                      // 方框图除数（1/8、1/32）；非方框图 1
            k.put("tier", d.tier());                        // 交集档位；非交集/方框类为 null
            k.put("unique", d.uniqueOf() != null);
            arr.add(k);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kinds", arr);
        m.put("primaryTier", ArtifactKind.PRIMARY_TIER);
        // 固定第一条「全部」汇总组的 12 张专用产物（交集图六档 6 张 + 多数/均值/去重均值 3 族各 2 张）：
        // 不属于 kinds()（不参与识别 / 验证 / 调优），前端单独一组卡片渲染
        List<Map<String, Object>> allArr = new ArrayList<>();
        for (String kind : ArtifactKind.allKinds()) {
            ArtifactKind.Def d = ArtifactKind.of(ArtifactKind.allBase(kind));
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", kind);
            k.put("file", ArtifactKind.allFile(kind));
            k.put("label", ArtifactKind.allLabel(kind));      // 卡片标题（.tn）
            k.put("base", ArtifactKind.allBase(kind));        // 所属族代表图 kind
            k.put("family", d == null ? null : d.family().name());   // 族名：INTERSECT = 角标为覆盖率，其余为平均差异
            k.put("diff", ArtifactKind.allMaxDiff(kind));     // true = 「与代表图差异最大的那张原图」
            allArr.add(k);
        }
        m.put("all", allArr);
        return m;
    }

    private static long detectCodeTimestamp() {
        // 1) classpath 单条目（java -jar 可执行包 / IDE 运行 classes）：直接取该条目 mtime
        String cp = System.getProperty("java.class.path", "");
        if (!cp.isEmpty() && cp.indexOf(java.io.File.pathSeparatorChar) < 0) {
            long t = mtimeOf(cp);
            if (t > 0) return t;
        }
        // 2) 从 static/index.html 定位部署根
        try {
            URL u = AppMetaController.class.getClassLoader().getResource("static/index.html");
            if (u != null) {
                if ("file".equalsIgnoreCase(u.getProtocol())) {          // 开发目录直接运行
                    long t = mtimeOf(Path.of(u.toURI()).toString());
                    if (t > 0) return t;
                } else if ("jar".equalsIgnoreCase(u.getProtocol())) {    // Spring Boot 可执行包
                    String p = u.getPath();    // jar:file:/…/x.jar!/BOOT-INF/classes!/static/index.html
                    int b = p.indexOf("!/");
                    if (b > 0 && p.startsWith("file:")) {
                        long t = mtimeOf(p.substring(5, b));
                        if (t > 0) return t;
                    }
                }
            }
        } catch (Exception ignore) {
            // 回退到启动时刻
        }
        log.warn("无法定位代码部署时间，codeTs 回退为本次启动时刻（此后每次重启都会触发前端刷新）");
        return System.currentTimeMillis();
    }

    private static long mtimeOf(String path) {
        try {
            Path p = Path.of(path);
            if (Files.exists(p)) {
                return Files.getLastModifiedTime(p).toMillis();
            }
        } catch (Exception ignore) {
            // 非文件类路径（如 http classpath），忽略
        }
        return -1L;
    }
}
