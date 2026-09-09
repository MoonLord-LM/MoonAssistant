package cn.moonlord.mca.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 截图相关可调参数（前缀 {@code capture.*}），值全部以本类字段默认值形式固化在代码里。
 *
 * <p>需要覆盖时不必改动代码，用 Spring 标准外部化配置即可（优先级高于本类默认值）：
 * 在 application.properties 里追加同名键、设置环境变量（如 {@code CAPTURE_INTERVAL_MS}），
 * 或启动时加命令行参数（如 {@code --capture.interval-ms=1000}）。</p>
 */
@Data
@ConfigurationProperties(prefix = "capture")
public class CaptureProperties {

    /**
     * 目标图形程序窗口标题关键字，命中任一即视为候选；多个窗口命中时取面积最大的那个。
     *
     * <p>默认值即演示环境用的 MuMu 模拟器；改成你想自动化的任意 GUI 程序窗口标题即可
     * （例如记事本 / 浏览器 / 游戏窗口）。逗号分隔，也支持用数组语法覆盖。</p>
     */
    private List<String> windowKeywords = new ArrayList<>(List.of("MuMu模拟器", "MuMu安卓设备", "MuMu"));

    /**
     * 相邻两帧截图之间的最小等待（毫秒）。截图节拍不是“固定每 3 秒一拍”，而是
     * Spring fixedDelay 语义：每处理完一帧（抓帧 → 尺寸校验/调窗 → 与去重基准全库匹配比对
     * → 保存或判重丢弃）后，再等这么久才取下一帧——单帧处理耗时多长就自然顺延多长，
     * 匹配比对没完成就不会开始下一帧，绝不叠帧并发。
     */
    private long intervalMs = 1000;

    /**
     * 捕获的原始截图（未标注）保存目录，相对程序运行目录，启动时自动创建。
     * 捕获阶段输出的截图一律写到这里；一旦被人工标注（写入 .json）即整体移至 classifyDir。
     */
    private String captureDir = "capture";

    /**
     * 标注后的截图 + 同名 .json 标注数据保存目录（相对程序运行目录，扁平存放）。
     */
    private String classifyDir = "classify";

    /**
     * 汇总分析产物保存目录（相对程序运行目录）：每个分类标注一个子目录，
     * 内含对照图（15 张基础合成图 = 交集六档 same100/90/80/70/60/50（100% = 全部样本像素一致，
     * 其余覆盖 >90%/80%/70%/60%/50%）与多数/均值/去重均值/8·32 块图族，全部参与识别比对；
     * 每张基础图各带 -unique 独有区图共 15 张，同样全部参与比对；
     * 点击动作且有坐标的分类另有最多 12 张点击区交集图 click8/32-same100/90/80/70/60/50，全部参与比对）
     * 与 info.json；样本取自 classifyDir，可随时整目录删除后重算。
     */
    private String summaryDir = "summary";

    /**
     * 旧版单目录布局（截图/标注/汇总同根，如 captures/）：
     * 仅用于启动时一次性迁移到 captureDir / classifyDir / summaryDir 新布局，之后不再读写。
     */
    @Deprecated
    private String outputDir = "captures";

    /**
     * 传给 WindowsCapture 采集器内部的抓帧超时（毫秒）。
     */
    private long captureTimeoutMs = 5000;

    /**
     * 强制截图目标宽度（物理像素）。默认 1280：开启截图后，凡是截出来不是该尺寸的帧一律不保存，
     * 并自动用 SetWindowPos 把窗口「整体外框」缩放/移动后重截验证，直到截出的 PNG 恰好等于
     * 「resizeWidth × resizeHeight」才保存。
     *
     * <p>与 {@link #resizeHeight} 同时 &gt;0 才启用尺寸强制（默认 1280×720）；
     * 把两者都设成 0 则关闭尺寸校验，按旧行为把截图原样保存。</p>
     *
     * <p>注意：为保证画面不变形/无黑边，请在目标程序里把它的显示分辨率与方向配置成同尺寸
     * （例如 MuMu 模拟器内设为 1280x720、16:9 横屏），否则即使窗口缩放到位画面也可能被拉伸。</p>
     */
    private int resizeWidth = 1280;

    /**
     * 强制截图目标高度（物理像素），与 {@link #resizeWidth} 配套，默认 720。
     * 宽高都 &gt;0 时才启用“尺寸强制 + 不达标自动调窗重试”；任一为 0 则关闭尺寸校验。
     */
    private int resizeHeight = 720;

    /**
     * 自动截图循环的去重阈值（像素点差异百分比，0~100，默认 5）。开启截图后，每次截到并符合
     * 尺寸的画面在保存前与「去重基准」做<b>全尺寸逐像素比对</b>——基准 = capture/（原始截图）
     * 与 classify/（已标注样本）两目录下的<b>全部</b> PNG：逐点比较 RGB（忽略 alpha），统计
     * 不一致像素点占全图比例；仅当与其中<b>每一张</b>的不一致像素占比都 &gt; 本值时才保存 PNG；
     * 只要与任意一张占比 ≤ 本值（画面几乎没变 / 与某张已标注样本几乎相同 / 只有指针抖动之类的
     * 小扰动），该帧即视为重复丢弃，避免一边标注一边 capture/ 又落盘几乎一模一样的截图。
     *
     * <p>缩略图（最长边 96px）只做候选预筛：不一致像素占比必 ≥ 缩略平均差，缩略差已超阈值则必
     * 不重复、可安全跳过不比对，判定始终以全尺寸逐像素计数为准。</p>
     *
     * <p>0 或负数 = 关闭自动截图去重，每次都按原逻辑保存。</p>
     */
    private double diffThresholdPercent = 5;

    /**
     * 手动「存入分类 / 存到待标注」保存前的去重阈值（像素点差异百分比，0~100，默认 0.5）。
     * 执行模式把当前画面存入 classify/ 样本或另存为 capture/ 截图前，与「去重基准」（capture/ +
     * classify/ 全部同尺寸 PNG）做<b>全尺寸逐像素比对</b>：逐点比较 RGB（忽略 alpha），统计不一致
     * 像素点占比；仅当与其中每一张的占比都 &gt; 本值才允许保存；与任意一张占比 ≤ 本值（同一画面 /
     * 差 ≤0.5% 像素的近似画面）即拒绝并提示「和哪张重复」。手动存入由人眼确认过才触发，
     * 阈值远比自动截图（默认 5%）严苛，只拦几乎一样的画面。判定方法同自动截图
     * {@link #diffThresholdPercent}（全尺寸逐像素计数，缩略图仅预筛加速）。
     *
     * <p>0 或负数 = 关闭手动保存的去重检查，每次都按原逻辑保存。</p>
     *
     * <p>每次启动的历史重复清理按自动截图与手动保存两个启用阈值中的<b>最低要求</b>执行
     * （两者都启用时默认 min(5, 0.5) = 0.5）：与保留图不一致像素占比 ≤ 该值即判重复删除，
     * 口径与运行期判定一致（全尺寸逐像素计数），不会把差得稍多一点的画面误删。</p>
     */
    private double diffThresholdManualPercent = 0.5;

}
