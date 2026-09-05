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
     * 截图间隔（毫秒）：控制台「开启截图」后，后台按此间隔周期性截取目标窗口画面。
     */
    private long intervalMs = 3 * 1000L;

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
     * 内含七张对照图与 info.json；样本取自 classifyDir，可随时整目录删除后重算。
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
     * 像素差异去重阈值（百分比，0~100，默认 5）。开启后，每次截到的画面在保存前会与
     * 「已保存截图参考集合」（从最近一张开始倒序，最多保留最近 30 张）逐张做逐像素对比：
     * 仅当与其中<b>每一张</b>的平均像素差异都 ≥ 该百分比时才保存 PNG；
     * 只要与任意一张的差异低于阈值（画面几乎没变 / 只有指针抖动之类的小扰动），
     * 该帧即视为重复丢弃，避免 capture/ 里堆满几乎一模一样的截图。
     *
     * <p>0 或负数 = 关闭去重，每次都按原逻辑保存。</p>
     */
    private double diffThresholdPercent = 5;

}
