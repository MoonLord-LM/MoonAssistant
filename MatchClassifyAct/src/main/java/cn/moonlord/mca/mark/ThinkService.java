package cn.moonlord.mca.mark;

import cn.moonlord.mca.act.ArtifactKind;
import cn.moonlord.mca.act.FrameClassifier;
import cn.moonlord.mca.act.RuntimeService;
import cn.moonlord.mca.config.ExecuteProperties;
import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 汇总分析 · 同一「分类标注（state）」（动作一致）截图组的逐像素对照与记录。
 *
 * <p>界面语言里：分类标注 = 界面上的文本标签（数据字段 state），匹配动作 = none/click
 * （数据字段 action）；同一分类标注只允许一种匹配动作（控制台已按此规则约束）。
 * 本服务对每组截图逐像素分析，产出基础合成图与独有区图：交集图按覆盖率阈值分
 * 100/90/80/70/60/50 六档（100% = 样本完全一致，最严格；其余为覆盖 >90/80/70/60/50%），
 * 连同多数 / 均值 / 去重均值 / 8·32 块图族共 15 张基础合成图；
 * 每张基础图各合成一张 -unique 独有区图（15 张）；每个组合都带两个互相独立的点
 * （注意点：未设 = 屏幕中心；鼠标点击点：仅 click 分类有），据此另生成两套各 12 张方框交集图
 * （1/8、1/32 方框 × 交集六档）：以注意点为框心的 <b>注意区交集图</b>
 * （attn8/32-same100/90/80/70/60/50，每个分类都有）与以鼠标点击点为框心的 <b>点击区交集图</b>
 * （click8/32-same100/90/80/70/60/50，仅 click 分类）。
 * 基础图、独有区图与两套方框交集图全部作为执行模式 / 智能比对的比对维度参与识别。</p>
 * <ol>
 *   <li><b>交集图 100% 档</b>（same100.png）：要求样本在该像素完全同色（一致张数 == 样本数，
 *       最严格），任何截图间有差别的像素一律透明——只保留各张样本完全恒定的画面。
 *       另有交集图 90% 档（same90.png，覆盖>90%）与 same80 / same70 / same60 / same50 档：
 *       统计所有样本在该像素的颜色，覆盖率 = 该颜色出现的样本占比；覆盖 >90% / >80% / >70% /
 *       >60% / >50%（一致张数 > 样本数×对应阈值，严格大于）时以该主流颜色保留为不透明，否则透明。
 *       低一致门槛下保留更多公共像素，作为与最严格档互补的比对维度。主档口径（coverage、
 *       公共稳定区判定）仍以 90% 档为准。</li>
 *   <li><b>多数图</b>（major.png）：每个像素取“覆盖率最多”的颜色——逐像素统计所有样本的颜色，
 *       取出现次数最多（同票取样本顺序靠前）的颜色作为该点颜色。</li>
 *   <li><b>均值图</b>（avg.png）：每个像素对全部样本的 R、G、B 分别取平均，
 *       得到一张“各点平均颜色”的合成画面（透明通道统一视为不透明）。</li>
 *   <li><b>去重均值图</b>（dedup-avg.png）：每个像素先把全部样本在该点出现过的颜色<b>去重</b>，
 *       再对去重后的颜色逐通道等权平均——某张画面重复采样再多也只按一个颜色计，
 *       避免抽样不均把平均色拉偏（抽样不均指同一画面被截多次、颜色在平均里被重复加权）。</li>
 *   <li><b>1/8 多数图</b>（major8.png）：按 8×8 网格把所有样本对齐切块，将「同位置的块内全部
 *       像素」跨样本合并成一个集合，输出该集合中出现次数最多的颜色（覆盖率最多，同票取样本顺序
 *       靠前）；即一个输出像素 = 全部样本同一 8×8 块内所有原始像素的众数色，不分步压缩。</li>
 *   <li><b>1/32 多数图</b>（major32.png）：同上，块为 32×32。</li>
 *   <li><b>1/8 均值图</b>（avg8.png）：按 8×8 网格把所有样本对齐切块，将「同位置的块内全部
 *       像素」跨样本合并成一个集合，输出该集合全部像素 R/G/B 的总平均色。</li>
 *   <li><b>1/32 均值图</b>（avg32.png）：同上，块为 32×32。</li>
 *   <li><b>1/8 去重均值图</b>（dedup-avg8.png）：按 8×8 网格切块后，先把块内跨样本出现过的颜色
 *       去重，再对去重后的颜色逐通道等权平均；1/32 去重均值图（dedup-avg32.png）块为 32×32。</li>
 *   <li><b>注意区 1/8 交集图</b>（attn8-same100/90/80/70/60/50.png，每个分类都有）：以该组<b>注意点</b>
 *       （未设 = 屏幕中心）为框心的方框小图——框取整幅长宽的 1/8（1280×720 → 160×90），框内像素做与
 *       对应交集档相同的判定（100% = 全一致，其余为“覆盖>90/80/70/60/50%”），聚焦“要看的位置”；
 *       样本间不一致的框内像素透明；框中心固定，越出画幅的部分透明。各档框图一并参与识别比对。</li>
 *   <li><b>注意区 1/32 交集图</b>（attn32-same100/90/80/70/60/50.png）：同上，框取整幅长宽的 1/32
 *       （1280×720 → 40×22），按各交集档位判定。</li>
 *   <li><b>点击区 1/8 交集图</b>（click8-same100/90/80/70/60/50.png，仅 click 分类）：以该组<b>鼠标点击点</b>
 *       为框心的方框小图（框取整幅长宽的 1/8）——裁剪模板、透明度与交集判定口径与注意区图完全一致，
 *       只是框心不同，聚焦“要点/要点的位置”。</li>
 *   <li><b>点击区 1/32 交集图</b>（click32-same100/90/80/70/60/50.png，仅 click 分类）：同上，框取 1/32。</li>
 * </ol>
 *
 * <p>15 张基础图（交集六档与多数/均值/去重均值/8·32 块族）各额外合成一张对应的
 * <b>-unique 独有区图</b>（15 张，全部参与比对）：以该基础图为起点，把
 * 「其它分类标注（同尺寸的已汇总分组）的<b>同 kind 基础图</b>在同一像素位置
 * 颜色完全相同」的像素剔除（那些位置对“区分本分类”没有贡献），只保留本分类独有的画面区域，
 * 便于快速观察两两相近的分类到底差在哪里；独有区图只在本分类独有区域计分，
 * 作为与基础图互补的正式比对维度、专为拉开相近分类的差异度。
 * 独有区图是跨分类产物：
 * <b>要等全部分组的基础图都生成完才开始算</b>（全集门禁），且该分类适用的全部对照图必须齐全
 * （每个分类 15 基础 + 15 -unique + 12 张注意区交集图 = 42 张，click 分类另有 12 张点击区交集图 = 54 张；
 * 历史旧目录产物不全，重算后补齐）
 * 才参与汇总分析 / 特征验证 / 算法调优
 * （见 {@link cn.moonlord.mca.act.FrameClassifier}）；算法调优跑完会把综合最佳算法用到的那几种 kind
 * 复制一份到 {@code runtime/}，执行识别只认那份副本（见 {@link RuntimeService}）。两套方框交集图都不参与独有区互比，
 * 也没有 -unique 版本。</p>
 *
 * <p>产物统一放在 {@code summary/<分类标注>/} 目录下（capture/classify/summary 三阶段布局见
 * {@link StoragePaths}），基础图使用固定文件名：
 * {@code same100.png / same100-unique.png / same90.png / same90-unique.png / same80.png /
 * same80-unique.png / same70.png / same70-unique.png / same60.png / same60-unique.png / same50.png /
 * same50-unique.png / major.png / major-unique.png / avg.png / avg-unique.png / dedup-avg.png /
 * dedup-avg-unique.png / major8.png / major8-unique.png / avg8.png / avg8-unique.png / dedup-avg8.png /
 * dedup-avg8-unique.png / major32.png / major32-unique.png / avg32.png / avg32-unique.png /
 * dedup-avg32.png / dedup-avg32-unique.png}，
 * 各分类另生成两套方框交集图（框 = 整幅长宽 ÷8 / ÷32，1280×720 → 160×90 / 40×22；各六档交集口径）：
 * 以<b>注意点</b>（未设 = 屏幕中心，每个分类都有）为框心的
 * {@code attn8-same100/90/80/70/60/50.png / attn32-same100/90/80/70/60/50.png} 共 12 张注意区交集图，
 * 与以<b>鼠标点击点</b>（仅 click 分类有）为框心的
 * {@code click8-same100/90/80/70/60/50.png / click32-same100/90/80/70/60/50.png} 共 12 张点击区交集图；
 * 分析信息（样本数、覆盖率、注意点/点击点坐标等）
 * 写入同目录 {@code info.json}。产物只被汇总分析 / 特征验证 / 算法调优读取、不参与标注样本的修改，
 * 画面标签一律以控制台的人工标注为准。</p>
 *
 * <p>样本截图只读 classify/（已标注的截图 + .json）；单图智能建议的目标图（未标注）
 * 只读 capture/。</p>
 *
 * <p>截图来自同一窗口同一坐标系，画面位置固定，只需逐像素同位比较，无需平移匹配。</p>
 */
@Slf4j
@Service
public class ThinkService {

    /** 标注文件统一 UTF-8 + 缩进 + 忽略 null 字段（与 AnnotateController 一致） */
    private static final ObjectMapper JSON = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter TS_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /** 交集档位 kind（取自产物注册表 {@link ArtifactKind}）：同五档 same90/80/70/60/50 后接
     *  same100（全部样本一致才算达标，最严格）；顺序也决定逐 kind 刷新/进度顺序 */
    private static final List<String> SAME_TIERS = ArtifactKind.tierGenOrder();
    /** 与 SAME_TIERS 逐项对应的档位一致门槛（同取自注册表）：同档判定“一致张数 > 样本数 × 阈值”
     *  （严格大于），100% 档特判为“一致张数 == 样本数”（样本像素完全一致） */
    private static final double[] SAME_TIER_AGREE =
            SAME_TIERS.stream().mapToDouble(ArtifactKind::tierAgree).toArray();

    /* 每组合（一个分类标注目录）的固定文件名、kind 集合、展示顺序、方框除数全部取自唯一权威定义
     * {@link ArtifactKind}（与识别端 FrameClassifier 同源），本类不再各自维护 FILE_* / BASE_KINDS /
     * UNIQUE_BASE_KINDS / CLICK_ALL_KINDS / ATTN_ALL_KINDS / KIND_FILE / CLICK_DIVS。
     * 语义速览：15 张基础合成图 = 交集六档 same100/90/80/70/60/50 + max(major)/avg/dedup-avg 与
     * major8/avg8/dedup-avg8/major32/avg32/dedup-avg32（多数色 / 均值 / 去重均值，8/32 为块边长）；
     * 每张基础图配一张「kind + -unique」独有区图（基础图剔除「其它分类同 kind 基础图同像素同色」后的区域）；
     * 注意区交集图 attn8/32-* 每个分类都有（框心 = 注意点，未设 = 屏幕中心），点击区交集图 click8/32-*
     * 仅 click 分类有（框心 = 鼠标点击点）；两套方框 = 整幅长宽 ÷8 / ÷32，越界透明、无 -unique、
     * 不参与独有区互比。 */
    private static final String FILE_INFO = ArtifactKind.FILE_INFO;

    /** 方框交集图的除数（1/8、1/32），与注册表一致 */
    private static final int[] CLICK_DIVS = ArtifactKind.cropDivs();

    /** 全部 15 张基础图 kind（生成 -unique 用；顺序 = refreshUniqueClass 的逐 kind 计算/进度顺序） */
    private static final List<String> UNIQUE_BASE_KINDS = ArtifactKind.uniqueBaseOrder();

    /** 注意区交集图全部 kind（生成/比对用）：与点击区同规则，只是框心为注意点（未设 = 屏幕中心） */
    private static final List<String> ATTN_ALL_KINDS = ArtifactKind.attnKinds();

    /** 点击区交集图全部 kind（生成/比对用）：1/8、1/32 方框 × 六档，仅 click 分类生成 */
    private static final List<String> CLICK_ALL_KINDS = ArtifactKind.clickKinds();

    /** 旧版 kind 短码（uniqueCov 落盘键）→ 现 kind 全名：历史产物读取时迁移 */
    private static final Map<String, String> UNIQ_KIND_ALIAS = Map.of(
        "same-unique", "same90-unique",   // v8 及更早的交集独有区图键，v9 交集主档改名 same90 后迁移
        "m8-unique", "major8-unique",
        "a8-unique", "avg8-unique",
        "m32-unique", "major32-unique",
        "a32-unique", "avg32-unique");

    /** 逐行像素处理时的行带高，控制峰值内存 */
    private static final int BAND_H = 64;

    /** 产物生成规则版本：改动产物生成逻辑后递增，使旧产物自动判 stale 并重算
     *  （v7：新增点击区交集图；v8：点击区框改以坐标为中心固定不收敛，出界填透明；
     *    v9：交集图主档改名 same90，并新增 same80/70/60 展示档；
     *    v10：各交集档判定改严格大于（>90/80/70/60/50），新增 same50 档，交集五档各加 -unique 独有区图；
     *    v11：点击区交集图 kind 改带档位后缀（click8-same90…），并新增 same80/70/60/50 低档框图；
     *    v12：新增去重均值族 dedup-avg / dedup-avg8 / dedup-avg32 及各自 -unique，
     *    每像素先对样本该点出现过的颜色去重再逐通道等权平均，消除重复采样对平均的加权；
     *    v13：交集低档与点击区低档并入识别比对（产物文件不变，不触发重算）；
     *    v14：无有效像素的图按 0（完全匹配）计、不再当不可比跳过（产物文件不变，不触发重算）；
     *    v15：新增 100% 交集档 same100/same100-unique 与点击区 click8/32-same100（判定 = 全部样本
     *    像素一致，主档口径不变仍为 same90），产物文件变化触发全量重算；
     *    v16：产物空图（无任何有效像素：独有区图无独有点 / 基础图公共区全空等）没有可判别的点＝无法
     *    做区分，不再按 0（完全匹配）计而是判完全不匹配、按不匹配占比满值计入，照常参与聚合/验证
     *    （产物文件不变，不触发重算）；
     *    v17：info.json 新增各点击区交集图「非透明像素占比」clickCov（汇总分析卡片右上角改展示该数值，
     *    与 -unique 独有区覆盖率同口径；产物内容不变但分析记录文件变化 → 旧分组判 stale 全量重算补齐）；
     *    v18：注意点与鼠标点击点分离 —— info.json 的 clickLeft/clickTop 改存「有效注意点」（点击区图/
     *    匹配裁剪中心，click 分类未设注意点则回退点击点、无动作分类=注意点默认中心），新增
     *    actLeft/actTop（仅 click 分类：执行模式真实点击坐标，可独立于注意点）与 attnLeft/attnTop，
     *    并新增各 kind「生效范围」eff（kind → 非透明像素占比 0..100，0 = 该分类该特征无任何有效数据））；
     *    v19：点击区图框心改用<b>鼠标点击点</b>（act，仅 click 分类生成 12 张）；新增以<b>注意点</b>为
     *    框心、每个分类都生成的 12 张注意区交集图 attn8/32-same100/90/80/70/60/50 与
     *    info 的 attnCov，注意点未设 = 屏幕中心（所有分类一致，不再回退点击点），
     *    click 分类不再生成以注意点为心的点击区图（识别端 clickLeft/clickTop 语义 = 注意点、框心不变）；
     *    同时把 info.json 的 attnLeft/attnTop 明确为<b>裁剪中心权威键</b>（= 有效注意点，恒有值），
     *    历史键名 clickLeft/clickTop 与其同值保留做兼容读（识别/验证/调优端先读新键、缺失再回退旧键，
     *    旧分组无需重算，待一两版删旧键） */
    private static final int ART_RULE_VERSION = 19;

    /** -unique 独有区图全量刷新时，同一尺寸类单一 kind 基础图文件总量上限：超过则本轮跳过，避免瞬时内存过高 */
    private static final long UNIQUE_CLASS_BYTES_LIMIT = 250L * 1024 * 1024;

    /** 汇总分析列表固定第一条「全部」的标题（不是分类标注，不参与识别比对，仅供整体目检） */
    private static final String ALL_TITLE = "全部";

    /** 「全部」汇总组的固定产物目录名；{@link #dirBaseName} 对该名回避，分类标注与之同名时自动加 {@code _cls} 后缀 */
    private static final String ALL_DIR = "_all_";

    /** 计算池：像素比对较重，串行避免并发打满 CPU（手动批量分析任务 / 自动重算共用） */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mca-think");
        t.setDaemon(true);
        return t;
    });

    /** 提交到串行计算池（单线程串行：像素比对较重，避免并发打满 CPU）。label 只用于日志 */
    private void submitPool(String label, Runnable body) {
        pool.submit(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                log.warn("汇总分析任务 [{}] 异常退出：{}", label, e.toString());
            }
        });
    }

    /** 智能建议独立单线程池：与批量分析/自动重算隔开，长汇总分析不会阻塞「停留 1 秒」的单图建议即时出结果 */
    private final ExecutorService suggestPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mca-suggest");
        t.setDaemon(true);
        return t;
    });

    /* ------------------------------------------------- 自动重算（标注样本变化后后台补齐/刷新产物） */

    /** 汇总分析任务代次：任何一次新请求（自动重算 / 开始分析 / 一键重建）都 +1。正在跑的旧任务在检查点
     *  发现线程被打断即刻作废退出 —— 它算出来的是变动前的旧数据、没有价值；新任务紧接着按最新状态重跑。
     *  所以既没有防抖延迟、也没有排队等待（池里已过期代次的排队项启动时自检，直接不跑）。 */
    private final AtomicLong runGen = new AtomicLong();
    /** 当前正在跑的分析线程：新请求直接打断它（阻塞 IO 立刻抛错、计算循环在下个检查点退出） */
    private final AtomicReference<Thread> runningThread = new AtomicReference<>();

    private final StoragePaths storage;
    private final ClassifyStore classifyStore;
    /** 运行时算法（runtime/，算法调优选出的「综合最佳算法」）：智能建议直接走它做比对，
     *  保证与执行模式同算法、同口径、同缓存 */
    private final RuntimeService runtimeService;
    private final ExecuteProperties executeProperties;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /** 单图智能建议任务 */
    private final Map<String, SuggestTask> suggestTasks = new ConcurrentHashMap<>();
    /** 最新一次建议请求：新请求一进来就把旧建议当场作废（它算的是上一张图 / 上一版产物） */
    private final AtomicReference<SuggestTask> latestSuggest = new AtomicReference<>();
    /** 正在跑的建议线程：新请求直接打断它（原图逐像素直比可能读上千张图） */
    private final AtomicReference<Thread> suggestThread = new AtomicReference<>();
    /** 建议结果缓存：key = 目标图|产物签名，避免同一张图反复重算；取访问序淘汰（再命中=用户还在来回比对该图，应续命留驻），上限 60 条 */
    private final Map<String, SuggestPack> suggestCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SuggestPack> eldest) {
            return size() > 60;
        }
    };

    public ThinkService(StoragePaths storage, ClassifyStore classifyStore,
                        RuntimeService runtimeService, ExecuteProperties executeProperties) {
        this.storage = storage;
        this.classifyStore = classifyStore;
        this.runtimeService = runtimeService;
        this.executeProperties = executeProperties;
        // 一次性迁移：历史「无动作」分类缺注意点坐标（left/top 为 null）→ 补为该分类样本的屏幕中心
        // （幂等：没有缺失时零写入；click 分类的注意点不再回退点击点，需注意区图时按屏幕中心兜底）
        try {
            int n = classifyStore.backfillNonePointsToCenter();
            if (n > 0) {
                log.info("已为 {} 个无动作分类补齐注意点坐标（屏幕中心），后台重算将刷新注意区图", n);
            }
        } catch (Exception e) {
            log.warn("补齐无动作分类注意点失败（不影响启动，待后续标注保存时修正）：{}", e.toString());
        }
        requestRecompute();   // 启动后立刻补一轮：上次退出没跑完 / 重启期间样本有变的产物尽快对齐
    }

    /* ---------------------------------------------------------------- 对外 API */

    /**
     * 分析全部待分析的组合并写入/刷新 `summary/<分类标注>/` 下产物。
     *
     * @param force true = 无视已有产物全部重算
     */
    public String startAnalyze(boolean force) {
        if (tasks.size() > 32) {
            tasks.entrySet().removeIf(e -> !"running".equals(e.getValue().status));   // 只留运行中的任务，防止长期挂机后 map 无限膨胀
        }
        String id = UUID.randomUUID().toString();
        Task t = new Task(id, force);
        tasks.put(id, t);
        submitAnalyze("批量汇总分析", t);
        return id;
    }

    /**
     * 一键重建（前端「重新生成全部对照图」按钮）：先清空 `summary/` 下全部产物目录，再全量重跑一轮分析。
     *
     * <p>等价于“手动删掉整个 summary/ 再触发自动生成”，但删除动作放进本服务的串行计算池执行，
     * 不会与自动重算 / 其它手动分析互踩；清场同时带走历史遗留文件（旧命名、孤儿图）。
     * 产物由 classify/ 已标注样本派生，删除不影响原始截图与标注。
     */
    public String startRebuild() {
        if (tasks.size() > 32) {
            tasks.entrySet().removeIf(e -> !"running".equals(e.getValue().status));   // 只留运行中的任务，防止 map 无限膨胀
        }
        String id = UUID.randomUUID().toString();
        Task t = new Task(id, true, true);
        tasks.put(id, t);
        submitAnalyze("重新生成全部", t);
        return id;
    }

    /** 任务快照；不存在返回 null */
    public Task task(String taskId) {
        return taskId == null ? null : tasks.get(taskId);
    }

    /* ---------------------------------------------------------------- 任务提交（代次作废式） */

    /**
     * 提交一次汇总分析：自动重算 / 手动「开始分析」/「重新生成全部」都走这里。
     *
     * <p><b>数据一变就以最新一轮为准</b>（2026-09-13 用户指定）：先递增代次并打断正在跑的旧任务 ——
     * 它算的是变动前的旧数据，跑完也没有价值；再把本次任务交给串行计算池。池里先前排队、代次已过期的
     * 任务在启动时自检直接退出，所以既不防抖、也不排队等待：旧任务被强行作废后新任务立刻接上。
     */
    private void submitAnalyze(String label, Task t) {
        long gen = runGen.incrementAndGet();
        Thread th = runningThread.get();
        if (th != null) {
            th.interrupt();       // 正在跑：打断它（阻塞 IO 立即抛错；计算循环在下个检查点退出）
        }
        submitPool(label, () -> runGuarded(t, gen));
    }

    /** 计算池执行外壳：已过期代次直接不跑；被新请求取代时把任务标为 superseded（不是失败） */
    private void runGuarded(Task t, long gen) {
        if (gen != runGen.get()) {
            markSuperseded(t);
            return;               // 排队期间已被更新的请求取代：不白跑
        }
        runningThread.set(Thread.currentThread());
        try {
            runAnalyze(t);
        } catch (Superseded e) {
            markSuperseded(t);
        } catch (Exception e) {
            log.warn("汇总分析异常: {}", e.toString());
            t.status = "error";
            t.message = "分析失败：" + e.getMessage();
        } finally {
            runningThread.compareAndSet(Thread.currentThread(), null);
            Thread.interrupted();  // 清掉打断标志，避免污染串行池的后续任务
        }
    }

    /** 把任务标成「已被最新一轮取代」：它算的是旧数据，前端据此提示而不是当失败报错 */
    private void markSuperseded(Task t) {
        t.status = "superseded";
        t.message = "数据已变动，本轮分析已被最新一轮取代（结果以最新一轮为准）";
        log.info("汇总分析任务 [{}] 因数据变动被作废，已提前退出", t.taskId);
    }

    /** 检查点：本任务已被新请求作废 → 抛 Superseded 立刻停止（穿透各层 catch，见 runAnalyze 里的放行）。
     *  只对「本轮分析线程」生效：同一批私有方法也会被接口线程同步调用（如拉组合列表），那些调用没有作废语义 */
    private void checkSuperseded() {
        Thread cur = Thread.currentThread();
        if (cur != runningThread.get()) {
            return;
        }
        if (cur.isInterrupted()) {
            throw new Superseded();
        }
    }

    /** 本次分析被更新请求取代（控制流用，不是错误）：一路穿到 runGuarded 收尾 */
    private static final class Superseded extends RuntimeException {
        Superseded() {
            super("superseded", null, false, false);   // 不抓栈：纯控制流，且检查点调用很密
        }
    }

    /**
     * 标注样本 / 分类定义（classify/）或产物（summary/）发生变化后调用：**立刻**按最新状态重跑一轮后台
     * 汇总分析，把「有样本（≥ 1 张）且产物缺失 / 样本数有变」的分组全部补齐或重算；正在跑的旧任务
     * （输入已经过期）当场作废让出计算池 —— 没有防抖延迟，也没有排队等待。
     *
     * <p>用于「窗口挂机持续标注」的场景：无需停留在汇总分析页，也不用点按钮，
     * 只要样本或分类定义（动作/注意点/点击点坐标）变化，summary/ 下的对照图（15 张基础合成图
     * + 15 张 -unique 独有区图 + 12 张注意区交集图 + 12 张点击区交集图）就会自动保持与最新样本一致，
     * 供执行模式随时取用。
     */
    public void requestRecompute() {
        submitAnalyze("自动重算", new Task("auto", false));   // 自动任务不进 tasks map（无需前端轮询）
    }

    /* ---------------------------------------------------------------- 智能建议（未标注图 × runtime/ 综合最佳算法） */

    /** 单图智能建议任务：把一张未标注截图交给运行时算法（{@link RuntimeService}）与各分类产物比对 */
    public static class SuggestTask {
        public final String taskId;
        public final String file;
        public volatile String status = "running";   // running / done / error
        public volatile String message = "";
        /** 候选组，与执行模式同口径：按差异度（100 − 加权匹配度）升序；每条含 diffPercent/recognized 等字段 */
        public volatile List<Map<String, Object>> candidates = List.of();

        SuggestTask(String taskId, String file) {
            this.taskId = taskId;
            this.file = file;
        }
    }

    /** 一次建议的全部结果：运行时算法算出的各分类候选。 */
    private static final class SuggestPack {
        final List<Map<String, Object>> candidates;

        SuggestPack(List<Map<String, Object>> candidates) {
            this.candidates = candidates;
        }
    }

    /** 启动单图智能建议。走独立建议池，**抢断式**：新请求把正在跑的旧建议（上一张图 / 旧产物）当场作废
     *  并打断它，只算最新停留的这一张图 —— 不再「等旧的跑完」。 */
    public String startSuggest(String file) {
        String id = UUID.randomUUID().toString();
        if (suggestTasks.size() > 100) {
            suggestTasks.entrySet().removeIf(e -> !"running".equals(e.getValue().status));
        }
        SuggestTask t = new SuggestTask(id, file);
        suggestTasks.put(id, t);
        SuggestTask prev = latestSuggest.getAndSet(t);
        if (prev != null && "running".equals(prev.status)) {
            prev.status = "superseded";
            prev.message = "已切换到最新一张截图";
        }
        Thread th = suggestThread.get();
        if (th != null) {
            th.interrupt();   // 打断正在跑的旧建议：立刻收手，计算池让给最新这一张
        }
        suggestPool.submit(() -> runSuggest(t, file));
        return id;
    }

    /** 建议任务快照；不存在返回 null */
    public SuggestTask suggestTask(String taskId) {
        return taskId == null ? null : suggestTasks.get(taskId);
    }

    private void runSuggest(SuggestTask t, String file) {
        suggestThread.set(Thread.currentThread());   // 记下本轮线程：新请求会直接打断它
        try {
            /* 已被更新的建议请求取代 → 当场作废，不再占用计算 */
            if (isSuggestStale(t)) {
                supersedeSuggest(t);
                return;
            }
            Path png = suggestPng(file);
            if (png == null) {
                t.status = "error";
                t.message = "截图不存在或尚未写入完成：" + file;
                return;
            }
            if (!runtimeService.ready()) {
                t.status = "error";
                t.message = "还没有可用于推荐的运行时算法：请先到「算法调优」完成一轮（「刷新算法特征」或"
                        + "「自动调整参数」），把综合最佳算法落地到 runtime/ 后，这里才会按它给出推荐分类。";
                return;
            }
            BufferedImage target = ImageIO.read(png.toFile());
            if (target == null) {
                t.status = "error";
                t.message = "无法解码截图：" + file;
                return;
            }
            String sig = suggestSig(png, target);
            SuggestPack pack;
            synchronized (suggestCache) {
                pack = suggestCache.get(file + "|" + sig);
            }
            if (pack == null) {
                List<Map<String, Object>> out = suggestWithClassifier(target);   // 走执行模式的同一运行时算法，同口径
                if (isSuggestStale(t)) {
                    supersedeSuggest(t);
                    return;                       // 用户已切到下一张图：这一轮结果已经没人看了
                }
                pack = new SuggestPack(out);
                synchronized (suggestCache) {
                    suggestCache.put(file + "|" + sig, pack);
                }
            }
            if (isSuggestStale(t)) {
                supersedeSuggest(t);
                return;                           // 命中缓存也要看是否已过期：结果只给最新这一张图用
            }
            t.candidates = pack.candidates;
            t.status = "done";
        } catch (Exception e) {
            if (isSuggestStale(t)) {
                supersedeSuggest(t);              // 被打断（解码途中）不算失败：本轮已被新请求取代
                return;
            }
            log.warn("智能建议 分析失败 {}: {}", file, e.toString());
            t.status = "error";
            t.message = "分析失败：" + e.getMessage();
        } finally {
            suggestThread.compareAndSet(Thread.currentThread(), null);
            Thread.interrupted();   // 清掉打断标志，避免污染串行池的后续任务
        }
    }

    /** 本建议是否已被更新的请求取代（用户切到了下一张图 / 产物重算）：是则立刻收手，不再算剩下的 */
    private boolean isSuggestStale(SuggestTask t) {
        return latestSuggest.get() != t;
    }

    /** 作废本轮建议：结果只服务最新一张图，旧的那一轮算完也没人看 */
    private void supersedeSuggest(SuggestTask t) {
        t.status = "superseded";
        t.message = "已切换到最新一张截图，本轮建议已作废";
    }

    /** 建议结果签名：目标图 + runtime/（算法定义 algorithm.json + 综合最佳算法用到的各分类产物）全部文件的
     *  尺寸与修改时间（重跑算法调优 / 产物刷新即失效） */
    private String suggestSig(Path png, BufferedImage target) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(png.getFileName()).append('|').append(Files.size(png)).append('|')
              .append(Files.getLastModifiedTime(png).toMillis()).append('|')
              .append(target.getWidth()).append('x').append(target.getHeight()).append('|');
        } catch (IOException e) {
            sb.append("png?|");
        }
        Path rt = storage.runtime();
        if (Files.isDirectory(rt)) {
            try (Stream<Path> ps = Files.walk(rt)) {
                for (Path f : (Iterable<Path>) ps.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(p -> rt.relativize(p).toString()))::iterator) {
                    String rel = rt.relativize(f).toString();
                    try {
                        sb.append(rel).append('=').append(Files.size(f)).append(',')
                          .append(Files.getLastModifiedTime(f).toMillis()).append(';');
                    } catch (IOException e) {
                        sb.append(rel).append("=?;");
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return sb.toString();
    }

    /** 校验目标截图名：必须是 capture/（未标注原始截图）下 img_*.png 且已写完 */
    private Path suggestPng(String file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        Path dir = storage.capture();
        Path p = dir.resolve(file).normalize();
        if (!p.startsWith(dir) || !isCapturedPng(p)) {
            return null;
        }
        return p;
    }

    /**
     * 单图智能建议：把目标截图交给<b>运行时算法</b>（{@code runtime/}，算法调优落地的「综合最佳算法」，
     * 见 {@link RuntimeService#classify}）识别，结果口径与执行模式完全一致——算法由若干特征
     * （产物 kind + 基础分 X + 权重 Y）组成，逐特征同尺度逐像素比对出各分类的匹配值，先放弃「最高匹配值被
     * ≥2 个分类并列」的打平特征，再按 Σ「匹配值 × X × Y」÷ Σ「X × Y」算出各分类匹配度；
     * 差异度 = 100 − 匹配度。识别不设阈值门槛：有可比的最近似分类即视为已识别（差异度仅供展示参考），
     * 最高匹配度被并列则整体判「无法区分」。
     */
    private List<Map<String, Object>> suggestWithClassifier(BufferedImage target) {
        FrameClassifier.Outcome oc = runtimeService.classify(target);
        if (oc == null || oc.candidates == null || oc.candidates.isEmpty()) {
            return List.of();
        }
        double thr = executeProperties.getMatchThresholdPercent();
        List<Map<String, Object>> out = new ArrayList<>(oc.candidates.size());
        for (int i = 0; i < oc.candidates.size(); i++) {
            FrameClassifier.Candidate c = oc.candidates.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", c.state());
            m.put("dir", c.matchedFile());
            m.put("diffPercent", Math.round(c.diffPercent() * 100.0) / 100.0);   // 差异度 = 100 − 加权匹配度（%）
            if (i == 0) {
                m.put("action", oc.ambiguous ? null : oc.action);   // 建议分类的动作只在最佳候选上读取（来自 algorithm.json）
            }
            m.put("recognized", oc.recognized);          // 不设识别阈值：有唯一最近似分类即 true（无法区分时 false）
            if (i == 0 && oc.ambiguous) {
                m.put("ambiguous", true);                // 最高匹配度被这几个分类并列：分不出该选哪个
                m.put("tiedStates", List.copyOf(oc.tiedStates));
            }
            m.put("thresholdPercent", Math.round(thr * 100.0) / 100.0);
            List<Map<String, Object>> kinds = new ArrayList<>(c.kinds().size());
            for (FrameClassifier.KindScore ks : c.kinds()) {
                kinds.add(Map.of("kind", ks.kind(), "file", ks.file(),
                        "w", ks.w(), "h", ks.h(), "score", Math.round(ks.score() * 100.0) / 100.0));
            }
            m.put("kinds", kinds);
            out.add(m);
        }
        return out;
    }

    /**
     * 1/8、1/32 多数 / 均值图：按 block×block 网格把所有样本对齐切块，将「同位置的块内全部
     * 像素」跨样本合并成一个像素集合（右侧 / 底部不足整块的余边忽略，产物尺寸 w/block × h/block）——
     * 多数图：输出该集合中出现次数最多的颜色（同票取样本顺序靠前的颜色）；
     * 均值图：输出该集合全部像素 R/G/B 的总平均色。即一个输出像素直接对应
     * 「全部样本同一块区域的所有原始像素」，不再先逐张块内压缩再跨样本合成。
     */
    private BufferedImage lowSample(List<BufferedImage> imgs, int w, int h, int block, boolean majority) {
        int wb = Math.max(1, w / block), hb = Math.max(1, h / block);
        int S = imgs.size();
        int[] outPx = new int[wb * hb];
        if (majority) {
            // 跨样本块内合并多数：同一输出行带（原图 block 行）逐输出列统计，控制峰值内存
            int[] buf = new int[w * block];
            int[][] band = new int[S][w * block];
            HashMap<Integer, Integer> freq = new HashMap<>();
            for (int oy = 0; oy < hb; oy++) {
                int y0 = oy * block;
                for (int s = 0; s < S; s++) {
                    imgs.get(s).getRGB(0, y0, w, block, buf, 0, w);
                    System.arraycopy(buf, 0, band[s], 0, buf.length);
                }
                for (int ox = 0; ox < wb; ox++) {
                    int x0 = ox * block;
                    freq.clear();
                    int bestCnt = 0, bestColor = 0;
                    for (int s = 0; s < S; s++) {
                        int[] row = band[s];
                        for (int dy = 0; dy < block; dy++) {
                            int base = dy * w + x0;
                            for (int dx = 0; dx < block; dx++) {
                                int c = row[base + dx];
                                int cnt = freq.merge(c, 1, Integer::sum);
                                if (cnt > bestCnt) {   // 同票保留先出现的颜色（样本顺序靠前）
                                    bestCnt = cnt;
                                    bestColor = c;
                                }
                            }
                        }
                    }
                    outPx[oy * wb + ox] = bestColor | 0xff000000;
                }
            }
        } else {
            // 跨样本块内合并均值：全部样本同位置块内的每个原始像素都计入同一条累加器
            int total = S * block * block;
            long[] sr = new long[wb * hb], sg = new long[wb * hb], sb = new long[wb * hb];
            for (BufferedImage im : imgs) {
                int[] px = im.getRGB(0, 0, w, h, null, 0, w);
                for (int y = 0; y < hb * block; y++) {
                    int rowBase = y * w;
                    int cellRow = (y / block) * wb;
                    for (int x = 0; x < wb * block; x++) {
                        int idx = cellRow + x / block;
                        int c = px[rowBase + x];
                        sr[idx] += (c >>> 16) & 0xff;
                        sg[idx] += (c >>> 8) & 0xff;
                        sb[idx] += c & 0xff;
                    }
                }
            }
            for (int i = 0; i < outPx.length; i++) {
                outPx[i] = 0xff000000
                    | ((int) ((sr[i] + total / 2) / total) << 16)
                    | ((int) ((sg[i] + total / 2) / total) << 8)
                    | (int) ((sb[i] + total / 2) / total);
            }
        }
        BufferedImage out = new BufferedImage(wb, hb, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, wb, hb, outPx, 0, wb);
        return out;
    }

    /**
     * 1/8、1/32 去重均值图（block=1 时为全幅去重均值图 dedup-avg.png）：与多数分支同款带式读样本，
     * 每个输出格子 = 跨样本块内「出现过的不同颜色」先整块去重（忽略 alpha，低 24 位相同视为同色），
     * 再对去重后的颜色集合逐通道等权平均——同一画面被截多次再多也只按一个颜色计，重复采样的冗余不再抬高平均权重。
     * 产物尺寸 w/block × h/block，右侧 / 底部不足整块的余边忽略；识别端画面侧用同口径 blockDedupMean 压缩比对。
     */
    private BufferedImage lowDedupSample(List<BufferedImage> imgs, int w, int h, int block) {
        int wb = Math.max(1, w / block), hb = Math.max(1, h / block);
        int S = imgs.size();
        int[] outPx = new int[wb * hb];
        int[] buf = new int[w * block];
        int[][] band = new int[S][w * block];
        HashSet<Integer> seen = new HashSet<>();
        for (int oy = 0; oy < hb; oy++) {
            int y0 = oy * block;
            for (int s = 0; s < S; s++) {
                imgs.get(s).getRGB(0, y0, w, block, buf, 0, w);
                System.arraycopy(buf, 0, band[s], 0, buf.length);
            }
            for (int ox = 0; ox < wb; ox++) {
                int x0 = ox * block;
                seen.clear();
                for (int s = 0; s < S; s++) {
                    int[] row = band[s];
                    for (int dy = 0; dy < block; dy++) {
                        int base = dy * w + x0;
                        for (int dx = 0; dx < block; dx++) {
                            seen.add(row[base + dx] & 0xffffff);
                        }
                    }
                }
                if (seen.isEmpty()) {
                    continue;
                }
                long sr = 0, sg = 0, sb = 0;
                for (int c : seen) {
                    sr += (c >>> 16) & 0xff;
                    sg += (c >>> 8) & 0xff;
                    sb += c & 0xff;
                }
                int cn = seen.size();
                outPx[oy * wb + ox] = 0xff000000
                    | ((int) ((sr + cn / 2) / cn) << 16)
                    | ((int) ((sg + cn / 2) / cn) << 8)
                    | (int) ((sb + cn / 2) / cn);
            }
        }
        BufferedImage out = new BufferedImage(wb, hb, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, wb, hb, outPx, 0, wb);
        return out;
    }

    /**
     * 组合总览：枚举 classify/（已标注截图 + .json）里全部「分类标注（state）+ 动作」组合，
     * 附样本数、产物目录、是否已分析（该组基础合成图已生成）及覆盖率。
     *
     * <p>另在列表<b>固定首位</b>插入一个不属于任何分类标注的「全部」汇总组（{@code all=true}，
     * 目录 {@link #ALL_DIR}）：不做分类分组，取 classify/ 全部已标注截图合成 12 张产物
     * （交集图六档 6 张（100% 档公共部分 + 90/80/70/60/50 档稳定区）、多数 / 均值 / 去重均值 3 张代表图 +
     * 这 3 族「与代表图差异最大的一张原图」，见 {@link #computeAllGroup()}）。
     * 它不参与列表排序、不作为识别比对样本，仅供整体目检。</p>
     */
    public List<Map<String, Object>> groups() {
        return groups(null);
    }

    /** {@link #groups()} 的带进度版本：后台任务（{@code t != null}）逐组合回报准备阶段进度，
     *  前端右栏据此显示「正在统计待分析组合：37/95 个分类（分类标注 ｜ 动作）（已耗时 1 分 59 秒）」，不再是一句笼统的「正在准备…」 */
    private List<Map<String, Object>> groups(Task t) {
        if (t != null) {
            t.stage = 0;                 // 准备阶段：还没开始生成对照图
            t.prepAct = "统计待分析组合";
            t.prepUnit = "个分类";
            t.prepTotal = 0;
            t.prepCur = "扫描已标注样本";
        }
        List<Path> pngs = annotatedPngs();
        Map<String, List<CaptureMark>> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> actionsOf = new HashMap<>();
        for (Path p : pngs) {
            CaptureMark m = classifyStore.sampleOf(p);
            if (m == null || trim(m.getState()).isEmpty()) {
                continue;
            }
            String state = trim(m.getState());
            String action = m.getAction() == null ? CaptureMark.ACTION_NONE : m.getAction();
            actionsOf.computeIfAbsent(state, k -> new HashSet<>()).add(action);
            byKey.computeIfAbsent(state + "\u0001" + action, k -> new ArrayList<>()).add(m);
        }
        if (t != null) {
            t.prepTotal = byKey.size();   // 组合数确定：下面逐组合核对产物是否齐全 / 是否为旧规则产物
            t.prepCur = "";
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int gi = 0;
        for (Map.Entry<String, List<CaptureMark>> e : byKey.entrySet()) {
            checkSuperseded();     // 准备阶段也可能被新请求作废（逐组合核对产物是耗时项）
            String[] sa = e.getKey().split("\u0001", 2);
            String state = sa[0];
            String action = sa[1];
            List<CaptureMark> marks = e.getValue();
            boolean multiAction = actionsOf.getOrDefault(state, Set.of()).size() > 1;
            String dir = dirNameOf(state, action, multiAction);
            if (t != null) {
                t.prepDone = ++gi;        // 准备阶段进度：正在核对第几个组合
                t.prepCur = state + " ｜ " + actionLabel(action);
            }

            Map<String, Object> g = new LinkedHashMap<>();
            g.put("state", state);
            g.put("action", action);
            g.put("dir", dir);
            g.put("sampleCount", marks.size());
            g.put("canAnalyze", !marks.isEmpty());   // 有样本（≥1 张）即可分析：单张也生成全部基础对照图，供执行模式匹配
            int[] cc = commonPoint(marks);            // 注意点（注意区图框心 / 匹配裁剪中心）
            int[] ac = commonAct(marks);              // 鼠标点击点（仅 click 分类的样本有 left/top）

            Path gdir = groupDir(dir);
            Map<String, Object> info = readInfo(gdir);
            boolean complete = hasCoreArtifacts(gdir);
            if (cc[0] < 0 || cc[1] < 0) {
                // 注意点未设 = 默认屏幕中心（与产物端同口径：按该分类画幅尺寸的一半）
                int iw = infoClick(info.get("width"));
                int ih = infoClick(info.get("height"));
                if (iw > 0 && ih > 0) {
                    cc = new int[]{iw / 2, ih / 2};
                }
            }
            g.put("clickLeft", cc[0]);
            g.put("clickTop", cc[1]);
            g.put("actLeft", ac[0]);
            g.put("actTop", ac[1]);
            g.put("analyzed", complete);   // 主产物基础图（交集 90% 档 + 多数/均值/去重均值/8·32 块族）齐全即视为已分析；-unique 由随后的跨分类刷新补齐
            // 注意区交集图（每个分类都有）90% 档 1/8、1/32 两图是否已生成；旧目录未重算 → false，后台重算后恢复
            boolean attnReq = cc[0] >= 0 && cc[1] >= 0;
            boolean hasAttn = complete && attnReq && hasArtifacts(gdir, cropPrimary(ATTN_ALL_KINDS));
            g.put("hasAttn", hasAttn);
            g.put("hasAttnLow", hasAttn && hasArtifacts(gdir, cropRest(ATTN_ALL_KINDS)));
            // 点击区交集图（仅 click 分类，框心 = 鼠标点击点）90% 档 1/8、1/32 两图是否已生成
            boolean clickReq = complete && ac[0] >= 0 && ac[1] >= 0;
            boolean hasClick = clickReq && hasArtifacts(gdir, cropPrimary(CLICK_ALL_KINDS));
            g.put("hasClick", hasClick);
            // 点击区交集图 100% 档与低档（same100/80/70/60/50 × 1/8、1/32 共 10 张）是否齐全：
            // 90% 档两图已有且这十张都在才展示对应卡片（重算前的旧目录没有这些产物 → false）
            g.put("hasClickLow", hasClick && hasArtifacts(gdir, cropRest(CLICK_ALL_KINDS)));
            if (complete) {
                boolean hasUnique = hasUniqueArtifacts(gdir);   // 全部 15 张 -unique 是否已随重算生成
                g.put("hasUnique", hasUnique);
                // 各独有区图剩余独有像素占各自全图的比例（kind → 百分数值，与 coverage 同口径）；未生成时为 null
                g.put("uniqueCov", hasUnique ? normUniqueCov(info.get("uniqueCov")) : null);
                g.put("coverage", info.get("coverage"));
                // 各交集档覆盖率（kind → 百分数值，90% 档与 coverage 同值；各档用于卡片角标）
                g.put("sameCov", info.get("sameCov"));
                // 各注意区 / 点击区交集图非透明像素占比（kind → 百分数值，卡片右上角展示；旧目录未重算时为 null）
                g.put("attnCov", info.get("attnCov"));
                g.put("clickCov", info.get("clickCov"));
                g.put("width", info.get("width"));
                g.put("height", info.get("height"));
                g.put("mtime", infoMtime(gdir));   // 产物 info.json 修改时刻：前端作缓存失效版本号（后台重算后界面能拉到新图）
                // 各特征（kind）的「生效范围」：该分类该 kind 产物中非透明像素占比（0..100，
                // 0 = 产物无任何有效数据，如独有图没有独有点 / 点击区框越出画幅）；null = 该分类无此产物
                g.put("eff", normEff(info.get("eff")));
                // 样本数相较上次分析有变化，或产物生成规则版本不一致 → 标记为待重分析
                Object fc = info.get("fileCount");
                boolean ruleChanged = !Integer.valueOf(ART_RULE_VERSION).equals(info.get("artVer"));
                // 「原分类内重定义动作/点击点/注意点」不改变样本数量，样本数与 artVer 都发现不了：
                // 只有产物 info.json 记录的坐标与当前中心表定义（commonPoint/commonAct 按样本实时合成）不一致时才判 stale，
                // 使 requestRecompute 重算刷新 summary/ 产物——否则执行模式将一直按 info.json 里的旧坐标处理。
                boolean defChanged = infoClick(info.get("clickLeft")) != cc[0]
                        || infoClick(info.get("clickTop")) != cc[1]
                        || infoClick(info.get("actLeft")) != ac[0]
                        || infoClick(info.get("actTop")) != ac[1];
                g.put("stale", ruleChanged || (fc != null && !fc.equals(marks.size())) || defChanged);
            } else {
                g.put("hasUnique", false);
                g.put("hasAttn", false);
                g.put("hasAttnLow", false);
                g.put("hasClick", false);
                g.put("hasClickLow", false);
                g.put("uniqueCov", null);
                g.put("coverage", null);
                g.put("attnCov", null);
                g.put("clickCov", null);
                g.put("width", null);
                g.put("height", null);
                g.put("stale", false);
            }
            out.add(g);
        }
        // 按“像素相同比例”排序（对应前端列表标题「分类标注列表（按像素相同比例）」）。
        // 相同比例 = 交集图 100% 档覆盖率（sameCov.same100，全部样本在该像素完全一致的占比，即列表首卡
        // 「交集图 100% 覆盖率（完全一致）」角标数值）。相同比例越高说明该组截图彼此差异越小——多为同一画面反复
        // 截取（采样不足，可信度反而低）；相同比例越低说明采到了该状态不同时刻的真实差异（采样更充分）。
        // 故已分析（产物齐全且样本未变）组合：相同比例低的靠前、高的靠后；同值按「分类标注 → 动作」文字升序。
        // 其余（未分析 / 样本有变待重算）排后：段内先按样本数从多到少，再按「分类标注 → 动作」文字升序。
        out.sort((a, b) -> {
            boolean ad = Boolean.TRUE.equals(a.get("analyzed")) && !Boolean.TRUE.equals(a.get("stale"));
            boolean bd = Boolean.TRUE.equals(b.get("analyzed")) && !Boolean.TRUE.equals(b.get("stale"));
            if (ad != bd) {
                return ad ? -1 : 1;
            }
            if (ad) {
                Object ca = pixelSameRatio(a), cb = pixelSameRatio(b);
                double x = ca instanceof Number na ? na.doubleValue() : -1d;
                double y = cb instanceof Number nb ? nb.doubleValue() : -1d;
                int c = Double.compare(x, y);   // 相同比例（截图差异小 = 采样不足）降序 → 升序：低者靠前
                if (c != 0) {
                    return c;
                }
            } else {
                // ≥1 张样本即可后台自动分析，未分析 / 待重算的组通常是产物刷新前的瞬时状态：样本多的排前
                int ca = ((Number) a.get("sampleCount")).intValue();
                int cb = ((Number) b.get("sampleCount")).intValue();
                if (ca != cb) {
                    return Integer.compare(cb, ca);
                }
            }
            int c = String.valueOf(a.get("state")).compareTo(String.valueOf(b.get("state")));
            if (c != 0) {
                return c;
            }
            return String.valueOf(a.get("action")).compareTo(String.valueOf(b.get("action")));
        });
        // 固定第一条「全部」：不参与上面的分类排序，恒置列表首位（分类标注列表为空时也不出现）
        if (!pngs.isEmpty()) {
            out.add(0, allGroup(pngs));
        }
        return out;
    }

    /**
     * 「全部」汇总组（列表固定第一条，{@code all=true}）：不做分类标注分组，取 classify/ 全部已标注
     * 截图合成 12 张产物（交集图六档 6 张 + 多数 / 均值 / 去重均值 3 张代表图 +
     * 这 3 族「与代表图差异最大的一张原图」，见 {@link #computeAllGroup()}）。
     *
     * <p>字段与普通组同构，供前端同一套列表 / 卡片渲染复用；只有 {@code hasUnique/hasAttn/hasClick}
     * 恒为 false（该组不生成这些产物），12 张产物的 kind / 角标 / 命中原图另由 {@code items} 下发。</p>
     */
    private Map<String, Object> allGroup(List<Path> pngs) {
        Path gdir = groupDir(ALL_DIR);
        Map<String, Object> info = readInfo(gdir);
        // 产物齐全 = 12 张专用产物都在磁盘上且 items 已随 info.json 写入（两次原子写之间有中断则视为未生成，下轮重算）
        boolean has = info.get("items") != null;
        if (has) {
            for (String k : ArtifactKind.allKinds()) {
                if (!Files.isRegularFile(gdir.resolve(ArtifactKind.allFile(k)))) {
                    has = false;
                    break;
                }
            }
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("state", ALL_TITLE);
        g.put("action", CaptureMark.ACTION_NONE);
        g.put("dir", ALL_DIR);
        g.put("all", true);
        g.put("sampleCount", pngs.size());
        g.put("canAnalyze", true);
        g.put("analyzed", has);
        g.put("clickLeft", -1);      // 无点击点 / 注意点：不生成任何方框图，前端也不会用这些字段
        g.put("clickTop", -1);
        g.put("actLeft", -1);
        g.put("actTop", -1);
        g.put("hasUnique", false);
        g.put("hasAttn", false);
        g.put("hasAttnLow", false);
        g.put("hasClick", false);
        g.put("hasClickLow", false);
        g.put("uniqueCov", null);
        g.put("attnCov", null);
        g.put("clickCov", null);
        g.put("eff", null);
        if (has) {
            Map<String, Object> sameCov = new LinkedHashMap<>();
            sameCov.put("same100", info.get("coverage"));   // 「全部」组代表图 = 交集图 100% 档公共部分
            g.put("sameCov", sameCov);
            g.put("coverage", info.get("coverage"));
            g.put("items", info.get("items"));               // 12 张产物：kind → {kind, pct, src?}
            g.put("width", info.get("width"));
            g.put("height", info.get("height"));
            g.put("mtime", infoMtime(gdir));
            // 样本数有变化或产物规则版本不一致 → 待重算（该组只看全部截图，样本数与产物规则即全部依据）
            Object fc = info.get("fileCount");
            g.put("stale", !Integer.valueOf(ART_RULE_VERSION).equals(info.get("artVer"))
                    || (fc != null && !fc.equals(pngs.size())));
        } else {
            g.put("sameCov", null);
            g.put("coverage", null);
            g.put("items", null);
            g.put("width", null);
            g.put("height", null);
            g.put("mtime", 0L);
            g.put("stale", false);
        }
        return g;
    }

    /** 列表排序用「像素相同比例」：交集图 100% 档覆盖率（info.sameCov.same100，全部样本一致像素占比）；
     *  旧目录缺 same100 键时退回 90% 档主覆盖率（info.coverage）。 */
    private static Object pixelSameRatio(Map<String, Object> g) {
        Object sameCov = g.get("sameCov");
        if (sameCov instanceof Map<?, ?> m) {
            Object v = m.get("same100");
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        }
        return g.get("coverage");
    }

    /** 读取分析产物 PNG（kind 为 {@link ArtifactKind} 里的产物维度之一：15 基础 + 15 -unique
     * + 12 张注意区交集图 + 12 张点击区交集图，或「全部」组 12 张专用产物；dir=产物目录名，禁止穿越）；非法返回 null */
    public Path resolveArtifact(String kind, String dir) {
        String file = ArtifactKind.allKind(kind) ? ArtifactKind.allFile(kind) : ArtifactKind.file(kind);
        if (file == null) {
            return null;
        }
        if (dir == null || dir.isEmpty() || dir.indexOf('/') >= 0 || dir.indexOf('\\') >= 0) {
            return null;
        }
        try {
            Path root = storage.summary();
            Path p = root.resolve(dir).resolve(file).normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) {
                return null;
            }
            return p;
        } catch (RuntimeException e) {
            return null;   // dir 解码结果含非法字符（NUL/控制符等）导致路径无法解析
        }
    }

    /* ---------------------------------------------------------------- 后台分析 */

    private void runAnalyze(Task t) {
        try {
            if (t.rebuild) {
                wipeSummary(t);
                log.info("一键重建：summary/ 已清空，开始全量重建全部对照图");
            }
            List<Map<String, Object>> groups = groups(t);
            List<Map<String, Object>> todo = new ArrayList<>();
            for (Map<String, Object> g : groups) {
                boolean need = Boolean.TRUE.equals(t.force)
                    || !Boolean.TRUE.equals(g.get("analyzed"))
                    || Boolean.TRUE.equals(g.get("stale"));
                if (Boolean.TRUE.equals(g.get("canAnalyze")) && need) {
                    todo.add(g);
                }
            }
            t.total = todo.size();
            t.stage = 1;                  // 准备阶段结束：进入逐分类生成基础对照图
            t.current = "";
            int processed = 0;
            for (Map<String, Object> g : todo) {
                checkSuperseded();   // 数据又变了 → 本任务当场作废，让最新一轮接着跑（不再把旧结果算完）
                String state = String.valueOf(g.get("state"));
                String action = String.valueOf(g.get("action"));
                boolean all = Boolean.TRUE.equals(g.get("all"));   // 固定第一条「全部」：合成 12 张专用产物，不按分类分组
                t.current = all ? ALL_TITLE : state + " ｜ " + actionLabel(action);
                try {
                    if (all) {
                        computeAllGroup();
                    } else {
                        computeGroup(state, action);
                    }
                    processed++;
                    t.processed = processed;
                } catch (Superseded e) {
                    throw e;         // 被新请求作废：不算「分类失败」，直接交给 runGuarded 收尾
                } catch (Exception e) {
                    log.warn("汇总分析 分类 [{}|{}] 分析失败: {}", state, action, e.toString());
                    t.processed = ++processed;
                    t.errors++;
                }
            }
            // 全部分组的基础图生成完成后，再刷新跨分类依赖的各 -unique 独有区图：
            // refreshUniqueArtifacts 内部带全集门禁（任一有样本分组的基础图未齐则整轮跳过），
            // 门禁通过后再按「各成员 15 张基础图 mtime 签名」增量检查（签名未变则跳过，成本只有 stat）
            int uniqueClasses = 0;
            try {
                t.stage = 2;
                t.current = "";
                uniqueClasses = refreshUniqueArtifacts(groups, t);
            } catch (Superseded e) {
                throw e;
            } catch (Exception e) {
                log.warn("刷新 -unique 独有区图异常（不影响本轮分析结果）：{}", e.toString());
            }
            t.status = "done";
            t.message = (todo.isEmpty()
                    ? "没有需要分析的组合（已有样本的分组都已有分析结果）"
                    : String.format("完成 %d/%d 个分类的基础对照图", processed, todo.size())
                        + (t.errors > 0 ? "（" + t.errors + " 个失败，详见日志）" : ""))
                + (uniqueClasses > 0 ? "；独有区图已同步刷新（" + uniqueClasses + " 个分类）" : "");
            log.info("汇总分析批量分析结束：{}", t.message);
        } catch (Superseded e) {
            throw e;              // 被新请求作废：由 runGuarded 标成 superseded（不是失败）
        } catch (Exception e) {
            log.warn("汇总分析批量分析异常: {}", e.toString());
            t.status = "error";
            t.message = "分析失败：" + e.getMessage();
        }
    }

    /** 以 (cx,cy) 为中心、取整幅长宽 ÷div 的方框几何（1280×720：div=8 → 160×90，div=32 → 40×22）。
     *  中心固定不向画幅内收敛：框可越出画幅边缘，出界部分按透明像素处理（与识别端全像素裁剪同口径对齐）。 */
    private static int[] clickBox(int cx, int cy, int w, int h, int div) {
        int bw = Math.max(1, w / div);
        int bh = Math.max(1, h / div);
        return new int[] {cx - bw / 2, cy - bh / 2, bw, bh};
    }

    /** 从 src 裁出以 (x0,y0) 为左上角、bw×bh 的子图（点击区交集图 = 交集图的方框裁剪，交集口径天然一致）。
     *  x0/y0 可为负、框右/下缘也可越出 src：越界区域先铺透明，仅拷贝与 src 相交的有效区。 */
    private static BufferedImage cropImage(BufferedImage src, int x0, int y0, int bw, int bh) {
        BufferedImage out = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        int[] zero = new int[bw * bh];
        out.setRGB(0, 0, bw, bh, zero, 0, bw);
        int ix0 = Math.max(0, x0), iy0 = Math.max(0, y0);
        int ix1 = Math.min(src.getWidth(), x0 + bw), iy1 = Math.min(src.getHeight(), y0 + bh);
        if (ix0 < ix1 && iy0 < iy1) {
            int[] row = new int[ix1 - ix0];
            for (int y = iy0; y < iy1; y++) {
                src.getRGB(ix0, y, ix1 - ix0, 1, row, 0, row.length);
                out.setRGB(ix0 - x0, y - y0, ix1 - ix0, 1, row, 0, row.length);
            }
        }
        return out;
    }

    /** 非透明像素占比（0~1）：统计 alpha 位非 0 像素 ÷ 总像素。点击区交集图右上角数值 = 框内该档保留像素占
     *  框图总像素的比例（出界透明 / 框内未达该档一致率而透明的像素都不计），口径同交集档覆盖率但只限框内区域 */
    private static double opaqueRatio(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int cnt = 0;
        for (int v : px) {
            if ((v >>> 24) != 0) {
                cnt++;
            }
        }
        return cnt / (double) px.length;
    }

    /** 计算单个分类（state+action）的 15 张基础对照图并刷新产物目录（交集六档 + 多数/均值/去重均值/8·32 块族；固定文件名原子替换；-unique 独有区图由跨分类刷新统一生成） */
    private void computeGroup(String state, String action) throws IOException {
        checkSuperseded();
        List<Path> pngs = annotatedPngs();
        List<Path> group = new ArrayList<>();
        for (Path p : pngs) {
            CaptureMark m = classifyStore.sampleOf(p);
            if (m != null && trim(state).equals(trim(m.getState()))
                && action.equals(m.getAction() == null ? CaptureMark.ACTION_NONE : m.getAction())) {
                group.add(p);
            }
        }
        if (group.isEmpty()) {
            throw new IllegalStateException("该组合没有可用样本");
        }

        // 两个互相独立的点（写入 info.json，分别作为注意区图 / 点击区图的框心）：
        //  ① 注意点 cx/cy = 样本标注的注意点（全分类可选，未设 = 屏幕中心：解码得尺寸后兜底）；
        //  ② 鼠标点击点 actX/actY = 仅 click 分类样本携带的 left/top（点击区图框心 = 执行点击坐标）。
        // 历史无动作分类旧单点（left/top 实为注意点）→ normalizeDefs 已迁到 attn，effPoint 亦兼容
        Integer cx = null, cy = null, actX = null, actY = null;
        {
            Map<Long, Integer> freq = new HashMap<>();
            Map<Long, Integer> actFreq = new HashMap<>();
            long bestKey = 0;
            int best = 0;
            long bestActKey = 0;
            int bestAct = 0;
            for (Path p : group) {
                CaptureMark m = classifyStore.sampleOf(p);
                if (m == null) {
                    continue;
                }
                int[] eff = effPoint(m);
                if (eff != null) {
                    long key = (((long) eff[0]) << 32) | (eff[1] & 0xffffffffL);
                    int c = freq.merge(key, 1, Integer::sum);
                    if (c > best) {
                        best = c;
                        bestKey = key;
                    }
                }
                if (CaptureMark.ACTION_CLICK.equals(m.getAction())
                    && m.getLeft() != null && m.getTop() != null
                    && m.getLeft() >= 0 && m.getTop() >= 0) {
                    long key = (((long) m.getLeft()) << 32) | (m.getTop() & 0xffffffffL);
                    int c = actFreq.merge(key, 1, Integer::sum);
                    if (c > bestAct) {
                        bestAct = c;
                        bestActKey = key;
                    }
                }
            }
            if (best > 0) {
                cx = (int) (bestKey >> 32);
                cy = (int) bestKey;
            }
            if (bestAct > 0) {
                actX = (int) (bestActKey >> 32);
                actY = (int) bestActKey;
            }
        }

        // 解码全部样本（分辨率须与第一张一致：同一窗口截图同尺寸，不一致 readScaled 直接报错）
        BufferedImage first = ImageIO.read(group.get(0).toFile());
        if (first == null) {
            throw new IOException("无法解码样本 " + group.get(0).getFileName());
        }
        int w = first.getWidth();
        int h = first.getHeight();
        // 注意点未设（该分类没有任何样本带注意点）= 默认屏幕中心：注意区图框心与匹配裁剪中心都用它
        // （所有分类一致，不再回退鼠标点击点；注意区图因此恒可生成）
        if (cx == null || cy == null) {
            cx = w / 2;
            cy = h / 2;
        }
        List<BufferedImage> imgs = new ArrayList<>();
        imgs.add(first);
        for (int g = 1; g < group.size(); g++) {
            BufferedImage bi = readScaled(group.get(g), w, h);
            if (bi != null) {
                imgs.add(bi);
            }
        }
        if (imgs.isEmpty()) {
            throw new IOException("该组合的样本全部无法解码");
        }
        int S = imgs.size();
        // 交集图判定：某像素“主流色一致张数 > 样本数 × 档位阈值”即视为达标（严格大于，如 >50% 档
        // 必须超过半数一致）；100% 档达标 = 一致张数 == 样本数（样本像素完全一致，最严格）；
        // 单样本时全部像素天然一致（交集图即原图本身），各档达标数下限自动为 1。
        // 达标数 = floor(S × 阈值) + 1（100% 档特判为 S）
        int T = SAME_TIERS.size();
        int[] need = new int[T];
        for (int ti = 0; ti < T; ti++) {
            need[ti] = SAME_TIER_AGREE[ti] >= 1.0 ? S : (int) Math.floor(S * SAME_TIER_AGREE[ti]) + 1;
        }

        int n = w * h;
        int[][] samePx = new int[T][n];   // 交集图各档：达标像素 = 该点颜色，其余保持 0（透明）
        int[] maxPx = new int[n];         // 多数图：每个像素 = 样本中出现最多的颜色
        int[] sumR = new int[n];          // 逐像素均值图用：各通道在所有样本上的累加
        int[] sumG = new int[n];
        int[] sumB = new int[n];
        boolean[] dead = new boolean[n];  // 主档（same90）未达标点：不计入公共（稳定）区域
        // 以行带方式逐点处理，控制峰值内存（每带一次作废检查：单分类像素统计也要能被秒级打断）
        for (int y = 0; y < h; y += BAND_H) {
            checkSuperseded();
            int hh = Math.min(BAND_H, h - y);
            int[][] band = new int[S][w * hh];
            int[] buf = new int[w * hh];
            for (int s = 0; s < S; s++) {
                imgs.get(s).getRGB(0, y, w, hh, buf, 0, w);
                System.arraycopy(buf, 0, band[s], 0, buf.length);
            }
            HashMap<Integer, Integer> freq = new HashMap<>();
            for (int yy = 0; yy < hh; yy++) {
                int rowBase = yy * w;
                int globalBase = (y + yy) * w;
                for (int x = 0; x < w; x++) {
                    int ii = rowBase + x;
                    int gi = globalBase + x;
                    int c0 = band[0][ii];
                    int sr = (c0 >>> 16) & 0xff;
                    int sg = (c0 >>> 8) & 0xff;
                    int sb = c0 & 0xff;
                    boolean allSame = true;
                    for (int s = 1; s < S; s++) {
                        int c = band[s][ii];
                        if (c != c0) {
                            allSame = false;
                        }
                        sr += (c >>> 16) & 0xff;
                        sg += (c >>> 8) & 0xff;
                        sb += c & 0xff;
                    }
                    sumR[gi] = sr;
                    sumG[gi] = sg;
                    sumB[gi] = sb;
                    if (allSame) {
                        for (int ti = 0; ti < T; ti++) {
                            samePx[ti][gi] = c0;   // 各样本完全一致：各档交集图该点均取该颜色
                        }
                        maxPx[gi] = c0;
                        continue;
                    }
                    // 该点各样本颜色不全相同：统计出现最多的颜色（同票取样本顺序靠前的）
                    freq.clear();
                    int bestColor = c0;
                    int bestCnt = 0;
                    for (int s = 0; s < S; s++) {
                        int c = band[s][ii];
                        int cnt = freq.merge(c, 1, Integer::sum);
                        if (cnt > bestCnt) {
                            bestCnt = cnt;
                            bestColor = c;
                        }
                    }
                    maxPx[gi] = bestColor | 0xff000000;
                    // 交集图各档：一致张数达到该档要求就保留该主流颜色（各档独立判断，互不干扰）
                    for (int ti = 0; ti < T; ti++) {
                        if (bestCnt >= need[ti]) {
                            samePx[ti][gi] = bestColor;
                        }
                    }
                    if (bestCnt < need[0]) {
                        dead[gi] = true;   // 主档一致度不足（覆盖 ≤ 90%）→ 该点透明，不计入公共（稳定）区域
                    }
                }
            }
        }
        // 均值图：每点 R/G/B 分别取全部样本的平均（所有图均按不透明画面输出）
        int[] avgPx = new int[n];
        for (int i = 0; i < n; i++) {
            int r = (sumR[i] + S / 2) / S;
            int g = (sumG[i] + S / 2) / S;
            int b = (sumB[i] + S / 2) / S;
            avgPx[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        // 各档透明度归一：达标像素统一置为不透明（值为 0 的点 = 该档未达标，保持透明）
        for (int i = 0; i < n; i++) {
            if (dead[i]) {
                samePx[0][i] = 0x00000000;
            } else {
                samePx[0][i] |= 0xff000000;
            }
        }
        for (int ti = 1; ti < T; ti++) {
            int[] px = samePx[ti];
            for (int i = 0; i < n; i++) {
                if (px[i] != 0) {
                    px[i] |= 0xff000000;
                }
            }
        }
        // 各交集档覆盖率 = 该档保留（不透明）像素占全图比例；主档口径与旧版一致（未达标点已归零）
        double[] tierCov = new double[T];
        for (int ti = 0; ti < T; ti++) {
            int[] px = samePx[ti];
            int cnt = 0;
            for (int i = 0; i < n; i++) {
                if ((px[i] >>> 24) != 0) {
                    cnt++;
                }
            }
            tierCov[ti] = cnt / (double) n;
        }
        double coverage = tierCov[0];

        BufferedImage[] sameImgs = new BufferedImage[T];
        for (int ti = 0; ti < T; ti++) {
            sameImgs[ti] = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            sameImgs[ti].setRGB(0, 0, w, h, samePx[ti], 0, w);
        }
        BufferedImage maxImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        maxImg.setRGB(0, 0, w, h, maxPx, 0, w);
        BufferedImage avgImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        avgImg.setRGB(0, 0, w, h, avgPx, 0, w);

        // 1/8、1/32 多数 / 均值图：把所有样本按块对齐切分后，同一块内的全部原始像素跨样本合并统计——
        // 多数 = 合并集中出现次数最多的颜色；均值 = 合并集 R/G/B 的总平均（不再先逐张压缩再合成）
        BufferedImage major8Img = lowSample(imgs, w, h, 8, true);
        BufferedImage avg8Img = lowSample(imgs, w, h, 8, false);
        BufferedImage major32Img = lowSample(imgs, w, h, 32, true);
        BufferedImage avg32Img = lowSample(imgs, w, h, 32, false);
        // 去重均值系列（全幅 + 1/8 + 1/32）：每格子先把跨样本出现过的颜色去重，再对去重后颜色逐通道等权平均，
        // 消除“同一画面被截多次 → 该颜色在平均里被重复加权”的抽样不均（block=1 即全幅逐像素去重平均）
        BufferedImage dedupAvgImg = lowDedupSample(imgs, w, h, 1);
        BufferedImage dedupAvg8Img = lowDedupSample(imgs, w, h, 8);
        BufferedImage dedupAvg32Img = lowDedupSample(imgs, w, h, 32);

        boolean multiAction = stateActions().getOrDefault(state, Set.of()).size() > 1;
        String dir = dirNameOf(state, action, multiAction);
        Path gdir = groupDir(dir);
        Files.createDirectories(gdir);
        checkSuperseded();                 // 开始写盘前最后确认：已作废就一个文件都不落
        for (int ti = 0; ti < T; ti++) {   // 六档交集图：same90/80/70/60/50/same100，全部为比对维度
            atomicWritePng(sameImgs[ti], gdir.resolve(ArtifactKind.file(SAME_TIERS.get(ti))));
        }
        atomicWritePng(maxImg, gdir.resolve(ArtifactKind.file("max")));
        atomicWritePng(avgImg, gdir.resolve(ArtifactKind.file("avg")));
        atomicWritePng(dedupAvgImg, gdir.resolve(ArtifactKind.file("dedup-avg")));
        atomicWritePng(major8Img, gdir.resolve(ArtifactKind.file("major8")));
        atomicWritePng(avg8Img, gdir.resolve(ArtifactKind.file("avg8")));
        atomicWritePng(dedupAvg8Img, gdir.resolve(ArtifactKind.file("dedup-avg8")));
        atomicWritePng(major32Img, gdir.resolve(ArtifactKind.file("major32")));
        atomicWritePng(avg32Img, gdir.resolve(ArtifactKind.file("avg32")));
        atomicWritePng(dedupAvg32Img, gdir.resolve(ArtifactKind.file("dedup-avg32")));
        // 注意区交集图（每个分类都生成：注意点未设 = 屏幕中心 cx/cy 已兜底）与点击区交集图（仅 click 分类：
        // 框心 = 鼠标点击点 actX/actY）。两套都是框心 (x,y) 的 1/8、1/32 方框小图 × 交集六档，各档直接裁剪
        // 同尺寸全幅交集图 sameImgs[ti] 的对应区域——交集口径与该档一致（不足该档一致率的框内像素透明）。
        // 各档框图都参与识别比对。框可越出画幅，出界 cropImage 填透明
        Map<String, Object> attnCov = new LinkedHashMap<>();   // 各注意区交集图非透明像素占比（kind → 4 位小数百分比）
        Map<String, Object> clickCov = new LinkedHashMap<>();  // 各点击区交集图非透明像素占比（kind → 4 位小数百分比）
        for (int di = 0; di < CLICK_DIVS.length; di++) {
            int div = CLICK_DIVS[di];
            int[] abox = clickBox(cx, cy, w, h, div);
            for (int ti = 0; ti < T; ti++) {
                String kind = "attn" + div + "-" + SAME_TIERS.get(ti);
                BufferedImage crop = cropImage(sameImgs[ti], abox[0], abox[1], abox[2], abox[3]);
                atomicWritePng(crop, gdir.resolve(ArtifactKind.file(kind)));
                attnCov.put(kind, Math.round(opaqueRatio(crop) * 1000000) / 10000.0d);
            }
        }
        if (actX != null && actY != null) {
            for (int di = 0; di < CLICK_DIVS.length; di++) {
                int div = CLICK_DIVS[di];
                int[] cbox = clickBox(actX, actY, w, h, div);
                for (int ti = 0; ti < T; ti++) {
                    String kind = "click" + div + "-" + SAME_TIERS.get(ti);
                    BufferedImage crop = cropImage(sameImgs[ti], cbox[0], cbox[1], cbox[2], cbox[3]);
                    atomicWritePng(crop, gdir.resolve(ArtifactKind.file(kind)));
                    clickCov.put(kind, Math.round(opaqueRatio(crop) * 1000000) / 10000.0d);
                }
            }
        } else {
            // 无动作分类没有鼠标点击点：不生成点击区图并清理残留（识别端该 12 张自然不参与）
            for (String kind : CLICK_ALL_KINDS) {
                Files.deleteIfExists(gdir.resolve(ArtifactKind.file(kind)));
            }
        }
        Files.deleteIfExists(gdir.resolve("click8-same.png"));    // 未带档位后缀的点击区图旧名残留，随重算清理
        Files.deleteIfExists(gdir.resolve("click32-same.png"));
        Files.deleteIfExists(gdir.resolve("half.png"));   // 旧版半分辨率均值图产物已废弃，随重算清理
        Files.deleteIfExists(gdir.resolve("same.png"));   // 旧版交集图产物已改名 same90，随重算清理
        Files.deleteIfExists(gdir.resolve("same-unique.png"));   // 旧版交集独有区图已改名 same90-unique，随重算清理

        // 分析记录文件（仅展示参考；不再存储带时间戳的文件名，产物名固定）
        Path info = gdir.resolve(FILE_INFO);
        Map<String, Object> d = Files.isRegularFile(info) ? readInfo(gdir) : new LinkedHashMap<>();
        d.put("state", trim(state));
        d.put("action", action);
        d.put("dir", dir);
        d.put("fileCount", group.size());
        d.put("sampleCount", S);
        d.put("artVer", ART_RULE_VERSION);
        d.put("width", w);
        d.put("height", h);
        d.put("coverage", Math.round(coverage * 1000000) / 10000.0d);   // 主档覆盖率（4 位小数百分比）
        Map<String, Object> sameCov = new LinkedHashMap<>();   // 各交集档覆盖率（kind → 4 位小数百分比）
        for (int ti = 0; ti < T; ti++) {
            sameCov.put(SAME_TIERS.get(ti), Math.round(tierCov[ti] * 1000000) / 10000.0d);
        }
        d.put("sameCov", sameCov);
        // 各特征（kind）的「生效范围」：该分类该 kind 产物中非透明像素占比（0..100 百分比，4 位小数；
        // 0 = 该特征在该分类没有任何有效数据，如独有图无独有点 / 方框越出画幅；null = 无此产物）。
        // 交集六档与 coverage 同源；多数/均值/去重均值/8·32 块族为各自产物非透明像素占比；
        // 注意区图同 attnCov、点击区图同 clickCov；-unique 各档由跨分类刷新 refreshUniqueClass 合并写入
        Map<String, Object> eff = new LinkedHashMap<>();
        for (int ti = 0; ti < T; ti++) {
            eff.put(SAME_TIERS.get(ti), Math.round(tierCov[ti] * 1000000) / 10000.0d);
        }
        String[] famKinds = {"max", "avg", "dedup-avg", "major8", "avg8", "dedup-avg8",
                             "major32", "avg32", "dedup-avg32"};
        BufferedImage[] famImgs = {maxImg, avgImg, dedupAvgImg, major8Img, avg8Img, dedupAvg8Img,
                                   major32Img, avg32Img, dedupAvg32Img};
        for (int i = 0; i < famKinds.length; i++) {
            eff.put(famKinds[i], Math.round(opaqueRatio(famImgs[i]) * 1000000) / 10000.0d);
        }
        eff.putAll(attnCov);
        eff.putAll(clickCov);
        d.put("eff", eff);
        d.put("attnCov", attnCov);
        d.put("clickCov", clickCov);
        // 注意点与真实点击点分开记录：
        //  attnLeft/attnTop = 注意点（裁剪中心<b>权威键</b>：注意区图框心 / 匹配裁剪中心；未设 = 屏幕中心，
        //                     即上面的 cx/cy，故恒有值）；
        //  clickLeft/clickTop = 历史键名，与 attnLeft/attnTop 同值（旧产物只有它 → 识别/验证端先读新键、
        //                       缺失再回退旧键；保留一两个版本后删除）；
        //  actLeft/actTop = 鼠标点击点（仅 click 分类有；点击区图框心，也是执行模式真实点击坐标）。
        d.put("attnLeft", cx);
        d.put("attnTop", cy);
        d.put("clickLeft", cx);
        d.put("clickTop", cy);
        if (actX != null) {
            d.put("actLeft", actX);
            d.put("actTop", actY);
        } else {
            d.remove("actLeft");
            d.remove("actTop");
        }
        d.put("updatedAt", LocalDateTime.now().format(TS_FORMAT));
        atomicWriteJson(info, d);
        pruneObsoleteGroupDirs(state, action, dir);
        log.info("汇总分析 分类 [{}|{}] 完成：{} 张样本，公共像素覆盖率（覆盖>90% 一致）{}% ，产物 → summary/{}/",
            trim(state), action, S, Math.round(coverage * 10000) / 100.0d, dir);
    }

    /**
     * 合成「全部」汇总组（列表固定第一条）的 12 张产物：交集图六档 6 张 + 多数 / 均值 / 去重均值三族各 2 张。
     * 交集图 = 100% 档（<b>全部图片的公共部分</b>）与 90/80/70/60/50 档（一致张数 &gt; 样本数 × 90/80/70/60/50%
     * 的稳定区，与分类产物同档口径一致、主档仍为 {@link ArtifactKind#PRIMARY_TIER}）；其余三族 = 代表图
     * （逐像素取覆盖率最多的值 / RGB 均值 / 按颜色去重后等权平均）+「与代表图差异最大的一张原图」。
     *
     * <p>全部已标注截图一次解码进内存（分辨率与首张不一致者不参与：不做缩放兜底，只记警告并在 info.json
     * 记 {@code skipped}），再逐带（{@link #BAND_H} 行）把各样本该带像素并排拼成连续数组后逐像素统计：
     * 交集六档（100% 档 = 全样本一致像素，其余 = 一致张数 &gt; 样本数 × 阈值）、众数、通道均值、去重后
     * 等权均值。全部像素统计完再逐张原图与 3 张非交集代表图比对，取各族差异最大者，
     * 直接把该原图 PNG 复制为 {@code <kind>-maxdiff.png}（不重编码）。</p>
     *
     * <p>内存 ≈ 样本数 × 画幅 × 4B（807 张 1280×720 ≈ 3GB，配 -Xmx24g 可用，另加交集六档图 ≈ 6 × 画幅 × 4B）；
     * 超过可用堆一半时直接报错退出，不做降级。差异口径 = 代表图全图像素中原图与它低 24 位不同的占比；
     * 交集族不出「差异最大图」——公共区与每张原图恒一致（差异恒 0，挑不出最大者），六档图各自落盘。</p>
     */
    private void computeAllGroup() throws IOException {
        checkSuperseded();
        List<Path> pngs = annotatedPngs();
        if (pngs.isEmpty()) {
            throw new IllegalStateException("没有已标注截图");
        }
        List<Path> used = new ArrayList<>();     // 参与合成的样本（与 px 下标一一对应）
        List<int[]> px = new ArrayList<>();
        int skipped = 0;
        int w = 0, h = 0, n = 0;
        for (Path p : pngs) {
            BufferedImage bi = ImageIO.read(p.toFile());
            if (bi == null) {
                skipped++;
                continue;
            }
            if (w == 0) {
                w = bi.getWidth();
                h = bi.getHeight();
                n = w * h;
            }
            if (bi.getWidth() != w || bi.getHeight() != h) {
                skipped++;
                log.warn("「全部」组：{} 分辨率 {}x{} 与首张 {}x{} 不一致，该张不参与（不做缩放兜底）",
                        p.getFileName(), bi.getWidth(), bi.getHeight(), w, h);
                bi.flush();
                continue;
            }
            px.add(bi.getRGB(0, 0, w, h, null, 0, w));
            used.add(p);
            bi.flush();
        }
        int S = px.size();
        if (S == 0) {
            throw new IOException("没有可用的已标注截图（全部解码失败或分辨率都不一致）");
        }
        long need = (long) S * n * 4 + (long) SAME_TIERS.size() * n * 4;   // 样本并排数组 + 交集六档图
        long heap = Runtime.getRuntime().maxMemory();
        if (need > heap / 2) {
            throw new IOException("「全部」组合成需把 " + S + " 张 " + w + "×" + h + " 截图解码进内存（约 "
                    + (need >> 20) + "MB），已超过可用堆（" + (heap >> 20) + "MB）的一半，本组跳过");
        }
        int[][] tierImg = new int[SAME_TIERS.size()][n];   // 交集六档图（100% 档公共部分 + 90/80/70/60/50 档稳定区）
        long[] tierCnt = new long[SAME_TIERS.size()];      // 各档覆盖像素数（角标 = 占全图比例）
        int[] refMax = new int[n];       // 多数图
        int[] refAvg = new int[n];       // 均值图
        int[] refDedup = new int[n];     // 去重均值图
        long same100Cnt = 0;             // 100% 档（全样本一致）像素数：coverage 与列表「像素相同比例」口径
        // 逐像素众数 / 去重用的原始 int 线性探测表：按「代」复用，避免每像素清表
        int cap = 16;
        while (cap < S * 2) {
            cap <<= 1;
        }
        int mask = cap - 1;
        int[] tKey = new int[cap];
        int[] tCnt = new int[cap];
        int[] tStamp = new int[cap];
        int gen = 0;
        int[][] band = new int[S][];
        for (int s = 0; s < S; s++) {
            band[s] = new int[w * BAND_H];
        }
        for (int y0 = 0; y0 < h; y0 += BAND_H) {
            checkSuperseded();       // 「全部」组样本最大：逐带统计也要能被秒级打断
            int bh = Math.min(BAND_H, h - y0);
            int len = w * bh;
            for (int s = 0; s < S; s++) {
                System.arraycopy(px.get(s), y0 * w, band[s], 0, len);   // 并排拼带：同一像素跨样本访问连续
            }
            for (int i = 0; i < len; i++) {
                gen++;
                int nd = 0, sr = 0, sg = 0, sb = 0, dr = 0, dg = 0, db = 0, modeC = 0, modeN = 0;
                for (int s = 0; s < S; s++) {
                    int c = band[s][i] & 0xffffff;
                    sr += c >>> 16;
                    sg += (c >>> 8) & 0xff;
                    sb += c & 0xff;
                    int hsh = c * 0x9E3779B1;
                    int slot = (hsh ^ (hsh >>> 15)) & mask;
                    while (tStamp[slot] == gen && tKey[slot] != c) {
                        slot = (slot + 1) & mask;
                    }
                    if (tStamp[slot] != gen) {          // 该颜色首次出现：计入众数与去重均值
                        tStamp[slot] = gen;
                        tKey[slot] = c;
                        tCnt[slot] = 1;
                        nd++;
                        dr += c >>> 16;
                        dg += (c >>> 8) & 0xff;
                        db += c & 0xff;
                        if (modeN == 0) {
                            modeN = 1;
                            modeC = c;
                        }
                    } else if (++tCnt[slot] > modeN) {  // 众数同票取样本顺序靠前者
                        modeN = tCnt[slot];
                        modeC = c;
                    }
                }
                int o = y0 * w + i;
                refMax[o] = 0xff000000 | modeC;
                refAvg[o] = 0xff000000 | (((sr + S / 2) / S) << 16)
                        | (((sg + S / 2) / S) << 8) | ((sb + S / 2) / S);
                refDedup[o] = 0xff000000 | (((dr + nd / 2) / nd) << 16)
                        | (((dg + nd / 2) / nd) << 8) | ((db + nd / 2) / nd);
                // 交集六档：一致张数 == 样本数（100% 档，最严格）必然满足所有档；其余档 = 一致张数 > 样本数 × 阈值
                for (int t = 0; t < SAME_TIERS.size(); t++) {
                    if (modeN == S || modeN > S * SAME_TIER_AGREE[t]) {
                        tierImg[t][o] = 0xff000000 | modeC;
                        tierCnt[t]++;
                    }
                }
                if (modeN == S) {
                    same100Cnt++;
                }
            }
        }
        // 逐张原图与 3 张非交集代表图（多数 / 均值 / 去重均值）比对：差异最大者即该族的「最大差异图」，同时累计平均差异
        long[] hi = {-1, -1, -1};   // -1 起手：保证第一张即被选中（全部截图完全一致、各族差异恒为 0 时也有命中）
        long[] sum = new long[3];
        int[] best = {0, 0, 0};
        for (int s = 0; s < S; s++) {
            int[] p = px.get(s);
            long[] c = new long[3];
            for (int k = 0; k < n; k++) {
                int v = p[k] & 0xffffff;
                if ((refMax[k] & 0xffffff) != v) {
                    c[0]++;
                }
                if ((refAvg[k] & 0xffffff) != v) {
                    c[1]++;
                }
                if ((refDedup[k] & 0xffffff) != v) {
                    c[2]++;
                }
            }
            for (int f = 0; f < 3; f++) {
                sum[f] += c[f];
                if (c[f] > hi[f]) {
                    hi[f] = c[f];
                    best[f] = s;
                }
            }
        }
        Path gdir = groupDir(ALL_DIR);
        Files.createDirectories(gdir);
        for (int t = 0; t < SAME_TIERS.size(); t++) {
            writeArgbPng(tierImg[t], w, h, gdir.resolve(ArtifactKind.allFile(SAME_TIERS.get(t))));
        }
        writeArgbPng(refMax, w, h, gdir.resolve(ArtifactKind.allFile("max")));
        writeArgbPng(refAvg, w, h, gdir.resolve(ArtifactKind.allFile("avg")));
        writeArgbPng(refDedup, w, h, gdir.resolve(ArtifactKind.allFile("dedup-avg")));
        // 交集族六张角标 = 各档覆盖像素占全图的比例；非交集族代表图角标 = 全部原图与该图的平均差异
        Map<String, Double> pcts = new LinkedHashMap<>();
        for (int t = 0; t < SAME_TIERS.size(); t++) {
            pcts.put(SAME_TIERS.get(t), pct(tierCnt[t], n));
        }
        String[] reps = {"max", "avg", "dedup-avg"};
        Map<String, String> srcs = new LinkedHashMap<>();
        for (int f = 0; f < reps.length; f++) {
            pcts.put(reps[f], pct(sum[f], (long) S * n));
            Path src = used.get(best[f]);
            String diffKind = reps[f] + ArtifactKind.ALL_MAXDIFF;
            Path dst = gdir.resolve(ArtifactKind.allFile(diffKind));
            Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);   // 差异图 = 原图 PNG 直接复制（不重编码），临时名 + 原子改名
            moveReplace(tmp, dst);
            srcs.put(diffKind, src.getFileName().toString());
            pcts.put(diffKind, pct(hi[f], n));   // 差异最大图角标 = 命中原图与该族代表图的不一致像素占比（全图口径）
        }
        // 历史产物：交集族曾出「最大差异图」（基准为不落盘的 90% 档），现第二张直接落盘 90% 档图 → 清掉旧文件
        Files.deleteIfExists(gdir.resolve("same100" + ArtifactKind.ALL_MAXDIFF + ".png"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (String kind : ArtifactKind.allKinds()) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("kind", kind);
            it.put("pct", pcts.get(kind));
            if (srcs.containsKey(kind)) {
                it.put("src", srcs.get(kind));   // 差异最大图 = 命中哪张原图
            }
            items.add(it);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("state", "");   // 不占任何分类标注：按 state 匹配的改名迁移 / 旧目录清理天然跳过本目录
        d.put("action", CaptureMark.ACTION_NONE);
        d.put("dir", ALL_DIR);
        d.put("all", true);
        d.put("artVer", ART_RULE_VERSION);
        d.put("fileCount", pngs.size());
        d.put("sampleCount", S);      // 实际参与合成的张数
        d.put("skipped", skipped);    // 分辨率不一致 / 解码失败而未参与的张数
        d.put("width", w);
        d.put("height", h);
        d.put("coverage", pct(same100Cnt, n));
        d.put("items", items);
        d.put("updatedAt", LocalDateTime.now().format(TS_FORMAT));
        atomicWriteJson(gdir.resolve(FILE_INFO), d);
        StringBuilder tierTxt = new StringBuilder();
        for (int t = 0; t < SAME_TIERS.size(); t++) {
            if (t > 0) {
                tierTxt.append(" / ");
            }
            tierTxt.append(SAME_TIERS.get(t)).append(' ').append(pct(tierCnt[t], n)).append('%');
        }
        log.info("汇总分析「全部」完成：{} 张截图（跳过 {} 张），交集六档覆盖率 {} ，{} 张产物 → summary/{}/",
            S, skipped, tierTxt, ArtifactKind.allKinds().size(), ALL_DIR);
    }

    /* ----------------------------------------------- -unique 独有区图（15 张基础图各一张 = 15 张） */

    /**
     * 刷新「classify/ 中当前有样本（≥ 1 张）的全部分组」的各 -unique 独有区图
     * （交集六档 same100/90/80/70/60/50 与多数/均值/去重均值/8·32 块族共 15 张基础图各一张，
     * 全部参与识别比对）。
     *
     * <p>基础合成图只刻画“本分类稳定出现的画面”，而独有区图进一步要求该稳定像素<b>只属于本分类</b>：
     * 以本分类某张基础图（kind）为起点，逐个与其它分类标注（同尺寸的已汇总分组）的<b>同 kind 基础图</b>
     * 同位比较，凡其它分类该图在该像素颜色完全相同的点都从本图剔除（它们无助于区分分类），
     * 保留下来的即本分类独有的画面区域。15 个 kind 逐张独立生成、互不干扰。
     *
     * <p><b>全集门禁</b>：独有区图是跨分类产物——少算一个分类，其它分类“哪些像素独有”的判定就不完整。
     * 因此只有「当前有样本的全部分组」的 15 张基础对照图（交集六档 + 多数/均值/去重均值/8·32 块族）
     * 都生成完毕，本方法才真正开始计算；任一分组基础图尚未齐备（本轮新增样本的分组还没轮到、
     * 或该分组本轮生成失败留下残图），整轮直接跳过，等下一轮补齐后再算——不允许在“分类集合不完整”
     * 的状态下过早生成。
     * 互比对象以 classify/ 有样本的分组为准，而非简单枚举 summary/ 磁盘目录（历史遗留的孤儿目录不参与现行分类判定）。
     *
     * <p>尺寸不同的分组无法逐像素对齐，彼此不参与比较；同一尺寸类按“各成员 15 张基础图 mtime 序列签名”
     * 增量更新——自身或任一其它分类的基础图更新过、或任一 -unique 图缺失 / 无独有覆盖率字段时才真正重算
     * 该尺寸类，因此自动重算频繁触发时成本仅为 stat。内存上逐 kind 单独处理：任一时点只保留一个 kind 的
     * 逐成员像素，且每种 kind 的文件总量超限即整类跳过本轮（签名不落盘，下轮自动重算会再尝试）。</p>
     */
    /** 刷新全部 -unique 独有区图；返回本轮实际刷新了多少个分类（门禁未过 / 无需变更返回 0） */
    private int refreshUniqueArtifacts(List<Map<String, Object>> groups, Task t) {
        Path root = storage.summary();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        // 先做全集门禁并收集互比目录：只放行「有样本且 15 张基础图（交集六档 + 多数/均值/去重均值/8·32 块族）齐全」的组
        List<Path> dirs = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            if (Boolean.TRUE.equals(g.get("all"))) {
                continue;   // 「全部」组只有 12 张专用产物：不参与 -unique 互比，也不计入全集门禁
            }
            if (!Boolean.TRUE.equals(g.get("canAnalyze"))) {
                continue;
            }
            Path d = groupDir(String.valueOf(g.get("dir")));
            if (!hasBaseArtifacts(d)) {
                log.warn("分组 {} 的基础对照图尚未齐备（交集六档 + 多数/均值/去重均值/8·32 块族），本轮跳过 -unique 独有区图刷新，等补齐后重算",
                    d.getFileName());
                return 0;
            }
            dirs.add(d);
        }
        if (dirs.isEmpty()) {
            return 0;
        }
        // 按 (宽,高) 归类：同尺寸才可逐像素同位比较
        Map<Long, List<Path>> byDim = new LinkedHashMap<>();
        for (Path d : dirs) {
            int[] wh = pngSize(d.resolve(ArtifactKind.file(ArtifactKind.PRIMARY_TIER)));
            if (wh == null || wh[0] <= 0 || wh[1] <= 0) {
                continue;
            }
            byDim.computeIfAbsent((((long) wh[0]) << 32) | (wh[1] & 0xffffffffL), k -> new ArrayList<>()).add(d);
        }
        int refreshed = 0;
        for (List<Path> cls : byDim.values()) {
            checkSuperseded();
            try {
                if (refreshUniqueClass(cls, t)) {
                    refreshed += cls.size();
                }
            } catch (Superseded e) {
                throw e;
            } catch (Exception e) {
                log.warn("刷新 -unique 独有区图失败（{} 个同尺寸分类）：{}", cls.size(), e.toString());
            }
        }
        return refreshed;
    }

    /** 重算一个“同尺寸类”的全部 -unique 独有区图；签名未变且各 -unique 图齐全时整类跳过。返回 true = 本轮实际执行了刷新 */
    private boolean refreshUniqueClass(List<Path> cls, Task t) throws IOException {
        String sig = classSig(cls);
        boolean need = false;
        for (Path d : cls) {
            Map<String, Object> info = readInfo(d);
            if (!sig.equals(String.valueOf(info.get("uniqueSig")))) {
                need = true;
                break;
            }
            Object covObj = info.get("uniqueCov");
            if (!(covObj instanceof Map)) {
                need = true;   // 旧产物没有分 kind 的独有覆盖率 map → 一并补算
                break;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cov = (Map<String, Object>) covObj;
            Object effObj = info.get("eff");
            Map<?, ?> effMap = effObj instanceof Map ? (Map<?, ?>) effObj : Map.of();
            for (String kind : UNIQUE_BASE_KINDS) {
                String uniqKind = kind + "-unique";
                if (!Files.isRegularFile(d.resolve(ArtifactKind.file(uniqKind)))
                    || cov.get(uniqKind) == null
                    || effMap.get(uniqKind) == null) {   // v18：旧产物没有 -unique 生效范围键 → 一并补算
                    need = true;
                    break;
                }
            }
            if (need) {
                break;
            }
        }
        if (!need) {
            return false;
        }
        int[] wh = pngSize(cls.get(0).resolve(ArtifactKind.file(ArtifactKind.PRIMARY_TIER)));
        // 内存防护：按 kind 单独处理，每种 kind 的文件总量都要在限内；任一种超限本轮跳过且不记录签名，
        // 之后每轮自动重算会再尝试，避免瞬时峰值内存过高
        for (String kind : UNIQUE_BASE_KINDS) {
            long bytes = 0;
            for (Path d : cls) {
                try {
                    bytes += Files.size(d.resolve(ArtifactKind.file(kind)));
                } catch (IOException ignore) {
                    // 文件刚被重算替换：按 0 计，让签名差异触发下一轮重试
                }
            }
            if (bytes > UNIQUE_CLASS_BYTES_LIMIT) {
                log.warn("同尺寸 {} 基础图共约 {}MB，超过 {}MB 上限，本轮跳过 -unique 独有区图刷新",
                    kind, bytes >> 20, UNIQUE_CLASS_BYTES_LIMIT >> 20);
                return false;
            }
        }
        // 逐 kind 计算：以本分类该 kind 基础图为起点，剔除“其它分类同 kind 基础图同位同色”的像素
        Map<Path, Map<String, Double>> covOf = new LinkedHashMap<>();
        int kindIndex = 0;
        for (String kind : UNIQUE_BASE_KINDS) {
            checkSuperseded();
            kindIndex++;
            if (t != null) {
                t.current = kindLabel(kind) + "（" + kindIndex + "/" + UNIQUE_BASE_KINDS.size() + "）";
            }
            Map<Path, Double> cov = refreshUniqueKind(cls, kind);
            for (Path d : cls) {
                covOf.computeIfAbsent(d, k -> new LinkedHashMap<>()).put(kind + "-unique", cov.get(d));
            }
        }
        // 全部 kind 算完才统一写签名与覆盖率，避免写一半造成信息不一致
        for (Path d : cls) {
            Map<String, Object> info = readInfo(d);
            info.put("uniqueSig", sig);
            info.put("uniqueCov", covOf.get(d));
            // -unique 各档的生效范围并入 eff（基础/点击区档已由 computeGroup 写入）
            Map<String, Object> eff = new LinkedHashMap<>();
            Object effObj = info.get("eff");
            if (effObj instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    eff.put(String.valueOf(en.getKey()), en.getValue());
                }
            }
            for (Map.Entry<String, Double> en : covOf.get(d).entrySet()) {
                eff.put(en.getKey(), en.getValue());
            }
            info.put("eff", eff);
            info.remove("uniqueCoverage");   // 旧版单一覆盖率字段清理
            atomicWriteJson(d.resolve(FILE_INFO), info);
        }
        log.info("-unique 独有区图已刷新：{} 个同尺寸分类（{}×{}），每分类 15 张",
            cls.size(), wh == null ? 0 : wh[0], wh == null ? 0 : wh[1]);
        return true;
    }

    /** 计算并落盘某一 kind 的 -unique 独有区图：把本分类该 kind 基础图中「其它分类同 kind 基础图同位同色」的点清透明 */
    private Map<Path, Double> refreshUniqueKind(List<Path> cls, String kind) throws IOException {
        String baseFile = ArtifactKind.file(kind);
        String uniqKind = kind + "-unique";
        Path first = cls.get(0).resolve(baseFile);
        int[] wh = pngSize(first);
        if (wh == null) {
            throw new IOException("PNG 尺寸读取失败：" + first);
        }
        int w = wh[0];
        int h = wh[1];
        Map<Path, int[]> pxOf = new LinkedHashMap<>();
        Map<Path, String> stateOf = new HashMap<>();
        for (Path d : cls) {
            BufferedImage img = ImageIO.read(d.resolve(baseFile).toFile());
            if (img == null || img.getWidth() != w || img.getHeight() != h) {
                throw new IOException(baseFile + " 读取失败/尺寸不一致：" + d.getFileName());
            }
            pxOf.put(d, img.getRGB(0, 0, w, h, null, 0, w));
            Object st = readInfo(d).get("state");
            stateOf.put(d, st == null ? "" : String.valueOf(st));
        }
        int n = w * h;
        Map<Path, Double> cov = new LinkedHashMap<>();
        for (Path me : cls) {
            int[] own = pxOf.get(me);
            int[] uniq = own.clone();   // 起点 = 本 kind 基础图；命中“其它分类同 kind 同位同色”的点逐个清透明
            String myState = stateOf.get(me);
            for (Path other : cls) {
                if (other.equals(me) || myState.equals(stateOf.get(other))) {
                    continue;   // 同分类标注的其它动作目录不算“其它分类”
                }
                int[] op = pxOf.get(other);
                for (int i = 0; i < n; i++) {
                    if (uniq[i] == 0) {
                        continue;                       // 自身基础图透明，或已被判定非独有
                    }
                    int oc = op[i];
                    if (oc != 0 && (oc & 0xffffff) == (uniq[i] & 0xffffff)) {
                        uniq[i] = 0;                    // 其它分类同 kind 基础图同位同色 → 非本分类独有
                    }
                }
            }
            long uniqueCount = 0;                        // 剔完后保留的独有像素数
            for (int i = 0; i < n; i++) {
                if (uniq[i] != 0) {
                    uniqueCount++;
                }
            }
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, w, h, uniq, 0, w);
            atomicWritePng(out, me.resolve(ArtifactKind.file(uniqKind)));
            // 独有像素占该图全图的比例（百分数值，口径与 coverage 一致：0.5 = 0.5%）
            cov.put(me, Math.round(uniqueCount * 1000000.0 / n) / 10000.0);
        }
        return cov;
    }

    /** 尺寸类签名 = 各成员目录“目录名=15 张基础图最后修改时刻序列”排序后拼接：任一成员任一基础图更新即变化 */
    private String classSig(List<Path> cls) {
        List<String> parts = new ArrayList<>(cls.size());
        for (Path d : cls) {
            StringBuilder sb = new StringBuilder(d.getFileName().toString());
            for (String kind : UNIQUE_BASE_KINDS) {
                long m = 0;
                try {
                    m = Files.getLastModifiedTime(d.resolve(ArtifactKind.file(kind))).toMillis();
                } catch (IOException ignore) {
                    // 读取失败按 0 计：签名必变，触发下一轮重算
                }
                sb.append('=').append(m);
            }
            parts.add(sb.toString());
        }
        parts.sort(String::compareTo);
        return String.join("|", parts);
    }

    /** 轻量读取 PNG 尺寸（只解析文件头，不整图解码） */
    private int[] pngSize(Path p) {
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) {
                return null;
            }
            ImageReader r = it.next();
            try {
                r.setInput(in, true, true);
                return new int[]{r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /* ---------------------------------------------------------------- 产物工具 */

    /** 产物子目录完整路径：summary/&lt;dir&gt;/ */
    private Path groupDir(String dir) {
        return storage.summary().resolve(dir).normalize();
    }

    /* 产物齐全性：分组全部取自注册表（primaryBaseKinds / extraBaseKinds / uniqueKinds / attnKinds /
     * clickKinds），不再逐张 hand-write 文件名清单。方法统一 hasXxx* 命名：
     *   hasCoreArtifacts     主产物 10 张基础图（交集 90% 档 + 多数/均值/去重均值/8·32 块族）
     *   hasBaseArtifacts     全部 15 张基础图
     *   hasUniqueArtifacts   15 张 -unique 独有区图
     *   hasAttnArtifacts（90% 档两图）/ hasAttnLowArtifacts（100% 与低档 10 张）/ hasAttnAllArtifacts
     *   hasClickArtifacts / hasClickLowArtifacts / hasClickAllArtifacts（仅 click 分类要求，其余视为通过）
     *   hasAllArtifacts      42 / 54 张全齐（产物齐全口径，与 FrameClassifier 同源） */

    /** 指定 kind 分组的产物是否全部就位 */
    private static boolean hasArtifacts(Path gdir, List<String> kinds) {
        for (String kind : kinds) {
            if (!Files.isRegularFile(gdir.resolve(ArtifactKind.file(kind)))) {
                return false;
            }
        }
        return true;
    }

    /** 方框 kind 分组里的主档（same90）两图：1/8 与 1/32 各一张 */
    private static List<String> cropPrimary(List<String> kinds) {
        return kinds.stream().filter(k -> k.endsWith("-" + ArtifactKind.PRIMARY_TIER)).toList();
    }

    /** 方框 kind 分组里除主档外的 10 张（100% 档与低档） */
    private static List<String> cropRest(List<String> kinds) {
        return kinds.stream().filter(k -> !k.endsWith("-" + ArtifactKind.PRIMARY_TIER)).toList();
    }

    /** 汇总分析主产物（交集 90% 档 + 多数/均值/去重均值/8·32 块族共 10 张基础图）是否齐全 */
    private boolean hasCoreArtifacts(Path gdir) {
        return hasArtifacts(gdir, ArtifactKind.primaryBaseKinds());
    }

    /** 全部 15 张基础图是否齐全（主产物 10 张 + 交集 100/80/70/60/50 档） */
    private boolean hasBaseArtifacts(Path gdir) {
        return hasCoreArtifacts(gdir) && hasArtifacts(gdir, ArtifactKind.extraBaseKinds());
    }

    /** 全部 15 张 -unique 独有区图是否齐全 */
    private boolean hasUniqueArtifacts(Path gdir) {
        return hasArtifacts(gdir, ArtifactKind.uniqueKinds());
    }

    /** 是否 click 分类（info 记录了真实点击点 actLeft/actTop）：只有 click 分类才要求点击区交集图 */
    private boolean clickRequired(Path gdir) {
        Map<String, Object> info = readInfo(gdir);
        return infoClick(info.get("actLeft")) >= 0 && infoClick(info.get("actTop")) >= 0;
    }

    /** 注意区交集图 90% 档两图是否齐全：注意点恒有效（未设 = 屏幕中心）故每个分类都需要 */
    private boolean hasAttnArtifacts(Path gdir) {
        return hasArtifacts(gdir, cropPrimary(ArtifactKind.attnKinds()));
    }

    /** 注意区交集图 100% 档与低档（共 10 张）是否齐全：参与识别比对且前端据此展示非 90% 档卡片 */
    private boolean hasAttnLowArtifacts(Path gdir) {
        return hasArtifacts(gdir, cropRest(ArtifactKind.attnKinds()));
    }

    /** 注意区交集图全部 12 张是否齐全（每个分类都需要） */
    private boolean hasAttnAllArtifacts(Path gdir) {
        return hasAttnArtifacts(gdir) && hasAttnLowArtifacts(gdir);
    }

    /** 点击区交集图 90% 档两图是否齐全：无动作分类视为通过 */
    private boolean hasClickArtifacts(Path gdir) {
        return !clickRequired(gdir) || hasArtifacts(gdir, cropPrimary(ArtifactKind.clickKinds()));
    }

    /** 点击区交集图 100% 档与低档（共 10 张）是否齐全：无动作分类视为通过 */
    private boolean hasClickLowArtifacts(Path gdir) {
        return !clickRequired(gdir) || hasArtifacts(gdir, cropRest(ArtifactKind.clickKinds()));
    }

    /** 点击区交集图全部 12 张是否齐全：仅 click 分类要求，无动作分类视为通过 */
    private boolean hasClickAllArtifacts(Path gdir) {
        return hasClickArtifacts(gdir) && hasClickLowArtifacts(gdir);
    }

    /** 对照图是否齐全：15 基础 + 15 -unique + 12 张注意区交集图必须有（注意点未设 = 屏幕中心，故恒有）；
     *  info 记录了鼠标点击点（actLeft/actTop）的 click 分类再需全部 12 张点击区交集图
     *  （无动作分类 = 42 张，click 分类 = 54 张）。
     *  与执行模式识别器同口径（低档/方框图齐全才参与匹配，旧目录缺图 → 不参与直到后台重算补齐）。 */
    private boolean hasAllArtifacts(Path gdir) {
        return hasBaseArtifacts(gdir) && hasUniqueArtifacts(gdir)
                && hasAttnAllArtifacts(gdir) && hasClickAllArtifacts(gdir);
    }

    /** 产物目录 info.json 的最后修改时刻（毫秒）；缺失/读不到返回 0。作为该组对照图产物整体是否更新过的版本号 */
    private long infoMtime(Path gdir) {
        Path info = gdir.resolve(FILE_INFO);
        if (!Files.isRegularFile(info)) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(info).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 读取产物目录下的 info.json；不存在/损坏返回空 map */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readInfo(Path gdir) {
        Path info = gdir.resolve(FILE_INFO);
        if (!Files.isRegularFile(info)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(info.toFile(), LinkedHashMap.class);
        } catch (IOException e) {
            log.warn("读取分析记录 {} 失败: {}", info, e.toString());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 产物目录名：= 分类标注文本（经文件名安全化后），正常一份标注对应一个目录；
     * 仅当同分类标注在历史数据里混用了多种动作时，追加 _action 后缀避免互相覆盖
     * （新界面规则已不允许此类混用）。
     */
    private String dirNameOf(String state, String action, boolean multiAction) {
        String s = dirBaseName(state);
        return multiAction ? s + "_" + action : s;
    }

    /** 分类标注 → 产物目录基础名（文件名安全化 + 截断；目录命名与改名迁移共用同一口径；静态方法内不便复用实例 trim） */
    private static String dirBaseName(String state) {
        String s = (state == null ? "" : state.trim())
            .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")   // 兜底：非法文件名符号
            .replaceAll("[\\.\\s]+$", "");                  // 兜底：Windows 禁止尾部的 . 与空格
        if (s.isEmpty()) {
            s = "unnamed";
        }
        if (ALL_DIR.equalsIgnoreCase(s)) {
            s = s + "_cls";   // 回避「全部」汇总组的固定目录名（同名分类仍可与「全部」并存）
        }
        if (s.length() > 120) {
            s = s.substring(0, 120);
        }
        return s;
    }

    /** 汇总 classify/ 中所有已标注图片的分类标注 → 该标注用到的动作集合（判断是否混用） */
    private Map<String, Set<String>> stateActions() {
        Map<String, Set<String>> map = new HashMap<>();
        for (Path p : annotatedPngs()) {
            CaptureMark m = classifyStore.sampleOf(p);
            if (m == null || trim(m.getState()).isEmpty()) {
                continue;
            }
            String action = m.getAction() == null ? CaptureMark.ACTION_NONE : m.getAction();
            map.computeIfAbsent(trim(m.getState()), k -> new HashSet<>()).add(action);
        }
        return map;
    }

    /** 删除指定分类+动作在其它目录下遗留的同组合产物（如分类标注改名后产生的旧目录） */
    private void pruneObsoleteGroupDirs(String state, String action, String keepDir) {
        Path sum = storage.summary();
        if (!Files.isDirectory(sum)) {
            return;
        }
        try (Stream<Path> s = Files.list(sum)) {
            for (Path d : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(d) || d.getFileName().toString().equals(keepDir)) {
                    continue;
                }
                Map<String, Object> dm = readInfo(d);
                if (trim(state).equals(dm.get("state")) && action.equals(dm.get("action"))) {
                    deleteTree(d);
                    log.info("已删除该分类旧产物目录 {}", d);
                }
            }
        } catch (IOException e) {
            log.warn("清理旧产物目录失败: {}", e.toString());
        }
    }

    /** 某分类样本已清零时清理其 summary/ 下全部残留产物目录（按 info.json 的 state 匹配）；仍有样本则不动作 */
    public void removeGroupArtifacts(String state) {
        String st = trim(state);
        Path sum = storage.summary();
        if (st.isEmpty() || !Files.isDirectory(sum) || classifyStore.sampleCount(st) > 0) {
            return;
        }
        try (Stream<Path> s = Files.list(sum)) {
            for (Path d : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(d)) {
                    continue;
                }
                Object dm = readInfo(d).get("state");
                if (!st.equals(dm == null ? "" : String.valueOf(dm).trim())) {
                    continue;
                }
                try {
                    deleteTree(d);
                    log.info("分类「{}」样本已清零，清理残留产物目录 {}", st, d);
                } catch (IOException e) {
                    log.warn("清理分类「{}」残留产物目录 {} 失败: {}", st, d, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("枚举 summary/ 失败，跳过分类 {} 的残留清理: {}", st, e.toString());
        }
    }

    /** 分类标注改名后产物迁移结果：moved = 成功迁名的目录数；needRebuild = 删除待后台重建的目录数 */
    public record RenameArtifactsResult(int moved, int needRebuild) {
    }

    /**
     * 分类标注整体改名后调用：把 summary/ 下该分类的产物目录整体迁名为新名，并把 info.json 的 state 改为新名。
     * 样本画面未变，15 张基础图内容不变；-unique 独有区图按像素互比、与目录名无关，均无需重算——
     * 改名即刻对执行模式生效，不再出现「删旧产物 + 后台重建」期间对照图不全的半成品目录被识别到而报红字。
     * 产物不齐 / 目录名不是标准同名目录 / 迁移失败的旧目录删除，交由后台按新名重建补齐。
     */
    public RenameArtifactsResult renameArtifacts(String from, String to) {
        Path sum = storage.summary();
        String f = trim(from);
        String t = trim(to);
        if (!Files.isDirectory(sum) || f.isEmpty() || t.isEmpty()) {
            return new RenameArtifactsResult(0, 0);
        }
        String baseFrom = dirBaseName(f);
        String baseTo = dirBaseName(t);
        if (baseFrom.equals(baseTo)) {
            return new RenameArtifactsResult(0, 0);
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(sum)) {
            dirs = s.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.warn("枚举 summary/ 失败，跳过产物目录改名: {}", e.toString());
            return new RenameArtifactsResult(0, 0);
        }
        int moved = 0;
        int needRebuild = 0;
        for (Path d : dirs) {
            Object st = readInfo(d).get("state");
            if (!f.equals(st == null ? "" : String.valueOf(st).trim())) {
                continue;
            }
            String name = d.getFileName().toString();
            String newName = name.equals(baseFrom) ? baseTo : null;
            if (newName == null || !hasAllArtifacts(d)) {
                // 名字非标准同名目录（历史遗留后缀等）或产物不齐：删除，后台按新名重建
                try {
                    deleteTree(d);
                    needRebuild++;
                    log.info("分类标注改名后旧产物目录 {} 无法直接迁移，删除待后台重建", d);
                } catch (IOException e) {
                    log.warn("删除 {} 失败: {}", d, e.toString());
                }
                continue;
            }
            Path target = sum.resolve(newName);
            if (Files.exists(target)) {
                try {
                    deleteTree(target);
                } catch (IOException e) {
                    log.warn("删除 {} 失败: {}", target, e.toString());
                }
            }
            if (Files.exists(target)) {
                try {
                    deleteTree(d);
                    needRebuild++;
                    log.warn("目标产物目录 {} 清理失败，删除旧目录 {} 待后台重建", target, d);
                } catch (IOException e) {
                    log.warn("删除 {} 失败: {}", d, e.toString());
                }
                continue;
            }
            try {
                Files.move(d, target);
                Map<String, Object> info = readInfo(target);
                info.put("state", t);
                atomicWriteJson(target.resolve(FILE_INFO), info);
                moved++;
                log.info("分类标注改名后产物目录已迁移：{} → {}", d, target);
            } catch (IOException e) {
                log.warn("迁移产物目录 {} → {} 失败，删除待后台重建: {}", d, target, e.toString());
                try {
                    deleteTree(d);
                } catch (IOException ignore) {
                }
                needRebuild++;
            }
        }
        return new RenameArtifactsResult(moved, needRebuild);
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除 {} 失败: {}", p, e.toString());
                }
            }
        }
    }

    /* ---------------------------------------------------------------- 通用工具 */

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private String actionLabel(String a) {
        return CaptureMark.ACTION_CLICK.equals(a) ? "点击" : "无动作";
    }

    /** kind → 中文短名（用于任务阶段提示文案）：由注册表口径（族 + 块边长 + 交集档）派生，不再逐个列举 */
    private String kindLabel(String kind) {
        ArtifactKind.Def def = ArtifactKind.of(kind);
        if (def == null) {
            return kind;
        }
        return switch (def.family()) {
            case INTERSECT -> "交集图 " + def.tier().substring("same".length()) + "%";
            case MAJOR -> blockLabel("多数", def.block());
            case AVG -> blockLabel("均值", def.block());
            case DEDUP_AVG -> blockLabel("去重均值", def.block());
            case CROP -> kind;
        };
    }

    /** 块族短名：块边长 1 = 全幅「X图」，8/32 = 「X块 8×8 / 32×32」 */
    private static String blockLabel(String name, int block) {
        return block == 1 ? name + "图" : name + "块 " + block + "×" + block;
    }

    /** 历史产物 info.json 的 uniqueCov 键为旧 kind 短码 → 迁移为 kind 全名；非 map / 空返回 null */
    private static Map<String, Object> normUniqueCov(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(UNIQ_KIND_ALIAS.getOrDefault(String.valueOf(e.getKey()), String.valueOf(e.getKey())), e.getValue());
        }
        return out.isEmpty() ? null : out;
    }

    /** info.json 的 eff（kind → 该分类该 kind 产物非透明像素占比 0..100）；非 map / 空返回 null */
    private static Map<String, Object> normEff(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out.isEmpty() ? null : out;
    }

    /** 一组标注中最常见的<b>注意点</b>（注意区图框心 / 匹配裁剪中心；click 分类未设不回退点击点）；
     *  无有效坐标返回 {-1,-1}（调用方按画幅尺寸取屏幕中心兜底） */
    private int[] commonPoint(List<CaptureMark> marks) {
        Map<Long, Integer> freq = new HashMap<>();
        long bestKey = 0;
        int best = 0;
        for (CaptureMark m : marks) {
            int[] eff = effPoint(m);
            if (eff == null) {
                continue;
            }
            long key = (((long) eff[0]) << 32) | (eff[1] & 0xffffffffL);
            int c = freq.merge(key, 1, Integer::sum);
            if (c > best) {
                best = c;
                bestKey = key;
            }
        }
        return best > 0 ? new int[]{(int) (bestKey >> 32), (int) bestKey} : new int[]{-1, -1};
    }

    /** 单条标注/定义的有效注意点：注意点 → 旧版无动作单点（left/top 实为注意点，尚未迁移时的兼容）
     *  → null（null = 未设，由调用方按画面尺寸取屏幕中心兜底）。
     *  click 分类未设注意点不再回退鼠标点击点：注意点与点击点是两个独立的点（默认 = 屏幕中心）。 */
    private static int[] effPoint(CaptureMark m) {
        if (m == null) {
            return null;
        }
        int[] eff = CaptureMark.attentionOf(m);
        if (eff != null) {
            return eff;
        }
        if (!CaptureMark.ACTION_CLICK.equals(m.getAction())
            && m.getLeft() != null && m.getTop() != null && m.getLeft() >= 0 && m.getTop() >= 0) {
            return new int[]{m.getLeft(), m.getTop()};
        }
        return null;
    }

    /** 一组标注中最常见的<b>鼠标点击点</b>（仅 click 分类的样本有 left/top）；无有效坐标返回 {-1,-1} */
    private int[] commonAct(List<CaptureMark> marks) {
        Map<Long, Integer> freq = new HashMap<>();
        long bestKey = 0;
        int best = 0;
        for (CaptureMark m : marks) {
            if (m.getLeft() == null || m.getTop() == null
                || m.getLeft() < 0 || m.getTop() < 0) {
                continue;
            }
            long key = (((long) m.getLeft()) << 32) | (m.getTop() & 0xffffffffL);
            int c = freq.merge(key, 1, Integer::sum);
            if (c > best) {
                best = c;
                bestKey = key;
            }
        }
        return best > 0 ? new int[]{(int) (bestKey >> 32), (int) bestKey} : new int[]{-1, -1};
    }

    /** info.json 里 clickLeft/clickTop 归一化为 int：null / 负数 → -1（与 commonClick 的「无有效坐标 = -1」同语义）。 */
    private static int infoClick(Object v) {
        return v instanceof Number n && n.intValue() >= 0 ? n.intValue() : -1;
    }

    /** 读取样本并校验分辨率与组内基准一致：同一窗口的同组截图分辨率本就相同，不一致直接报错（禁止缩放混用不同分辨率样本）。 */
    private BufferedImage readScaled(Path p, int w, int h) throws IOException {
        BufferedImage bi = ImageIO.read(p.toFile());
        if (bi == null) {
            return null;
        }
        if (bi.getWidth() != w || bi.getHeight() != h) {
            throw new IOException("样本分辨率与组内不一致，无法合成对照图：" + p.getFileName()
                    + " = " + bi.getWidth() + "x" + bi.getHeight() + "，组内基准 = " + w + "x" + h
                    + "（同一分类的样本须同一分辨率，请重新在同分辨率下标注）");
        }
        return bi;
    }

    /** classify/ 下 IMG_*.png：已标注数据集（保存标注时完整移入；写入端统一 .tmp→原子改名，半成品按后缀天然排除） */
    private List<Path> annotatedPngs() {
        List<Path> pngs = new ArrayList<>();
        Path dir = storage.classify();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> pngShape(p) && nonEmpty(p))
                 .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                 .forEach(pngs::add);
            } catch (IOException e) {
                log.warn("枚举 classify/ 目录失败: {}", e.toString());
            }
        }
        return pngs;
    }

    /** capture/（未标注）截图：命名规范且非空即可读。写入端是「.png.tmp → 原子改名 .png」，
     *  `.png` 出现即完整落盘（写入中的 .png.tmp 不符合 img_*.png 命名），无需时间等待 */
    private boolean isCapturedPng(Path p) {
        return pngShape(p) && nonEmpty(p);
    }

    private boolean pngShape(Path p) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        String n = p.getFileName().toString().toLowerCase();
        return n.startsWith("img_") && n.endsWith(".png");
    }

    private boolean nonEmpty(Path p) {
        try {
            return Files.size(p) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void atomicWriteJson(Path json, Map<String, Object> m) throws IOException {
        Path tmp = json.resolveSibling(json.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.writeString(tmp, JSON.writeValueAsString(m));
        moveReplace(tmp, json);
    }

    private void atomicWritePng(BufferedImage img, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        boolean ok = ImageIO.write(img, "png", tmp.toFile());
        if (!ok) {
            Files.deleteIfExists(tmp);
            throw new IOException("ImageIO 无法写出 PNG");
        }
        moveReplace(tmp, target);
    }

    /** 把 ARGB 像素数组原子写为 PNG（「全部」组 4 张代表图落盘用） */
    private void writeArgbPng(int[] px, int w, int h, Path target) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, px, 0, w);
        atomicWritePng(img, target);
    }

    /** 占比（num / den）换算为 4 位小数百分比；den ≤ 0（无可比像素）返回 0 */
    private static double pct(long num, long den) {
        return den <= 0 ? 0d : Math.round(num * 1000000.0d / den) / 10000.0d;
    }

    private void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 递归删除整个 `summary/` 产物目录（一键重建前清场；单个文件删除失败仅告警，不中断后续重建）。
     *  {@code t != null} 时逐项回报删除进度，前端右栏显示「正在清理旧产物：1200/3226 张（same100.png）（已耗时 N 分 M 秒）」 */
    private void wipeSummary(Task t) {
        Path root = storage.summary();
        if (!Files.isDirectory(root)) {
            return;
        }
        // 先枚举一遍拿到总数（几 GB / 几千张的删除在 Windows 上可能持续数十秒，没有计数看不出是否在推进）
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.forEach(all::add);
        } catch (IOException e) {
            log.warn("清理 summary/ 前枚举目录失败：{}", e.toString());
        }
        if (t != null) {
            t.stage = 0;
            t.prepAct = "清理旧产物";
            t.prepUnit = "张";
            t.prepTotal = all.size();
            t.prepCur = "";
        }
        all.sort(Comparator.reverseOrder());   // 先删子项再删目录
        int done = 0;
        for (Path p : all) {
            checkSuperseded();     // 清场期间数据又变 → 本任务作废，让最新一轮从干净目录重来
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("清理 summary/ 时无法删除 {}（保留并随重建覆盖，不影响运行）：{}", p, e.toString());
            }
            if (t != null) {
                t.prepDone = ++done;
                Path nm = p.getFileName();
                t.prepCur = nm == null ? "" : nm.toString();
            }
        }
    }

    /* ---------------------------------------------------------------- 模型 */

    /** 后台分析任务（running → done / error；跑一半被更新请求取代 → superseded，不算失败） */
    public static class Task {
        public final String taskId;
        public final boolean force;
        /** true = 一键重建：开始前先清空整个 summary/ 再全量分析 */
        public final boolean rebuild;
        public volatile String status = "running";
        public volatile String message = "";
        public volatile int total;
        public volatile int processed;
        public volatile int errors;
        /** 正在处理的分类展示文案 */
        public volatile String current = "";
        /** 当前阶段：0 = 准备（一键重建先逐张清场 summary/，再逐分类统计待分析组合）；1 = 逐分类生成 15 张基础对照图
         *  （交集六档 + 多数/均值/去重均值/8·32 块族）；2 = 生成各分类 15 张 -unique 独有区图 */
        public volatile int stage = 1;
        /** 准备阶段（stage=0）正在做的事：清场时的「清理旧产物」/ 统计时的「统计待分析组合」 */
        public volatile String prepAct = "";
        /** 准备阶段计数单位（清场 = 张、统计 = 个分类）：前端按「已完成/共几 单位」展示 */
        public volatile String prepUnit = "";
        /** 准备阶段已完成数量 / 总数（0 = 尚未得出总数） */
        public volatile int prepDone;
        public volatile int prepTotal;
        /** 准备阶段当前对象（被删产物文件名 / 正在核对的「分类标注 ｜ 动作」） */
        public volatile String prepCur = "";
        /** 任务提交时刻（毫秒）：前端据此显示已耗时，长计算期间能判断仍在推进而非卡死 */
        public final long submittedAtMs = System.currentTimeMillis();

        Task(String taskId, boolean force) {
            this(taskId, force, false);
        }

        Task(String taskId, boolean force, boolean rebuild) {
            this.taskId = taskId;
            this.force = force;
            this.rebuild = rebuild;
        }
    }
}
