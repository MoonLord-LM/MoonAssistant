package cn.moonlord.mca.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 截屏相关配置，对应 application.properties 中 capture.* 前缀。
 */
@Data
@ConfigurationProperties(prefix = "capture")
public class CaptureProperties {

    /** 目标窗口标题关键字，命中任一即视为候选；多个窗口命中时取面积最大的那个 */
    private List<String> windowKeywords = new ArrayList<>(List.of("MuMu模拟器", "MuMu安卓设备", "MuMu"));

    /** 截图间隔（毫秒） */
    private long intervalMs = 3 * 1000L;

    /**
     * 原始截图保存目录（相对程序运行目录）。
     * 捕获阶段输出的截图一律写到这里；一旦被人工标注（写入 .json）即整体移至 classify-dir。
     */
    private String captureDir = "capture";

    /** 标注后保存目录（相对程序运行目录）：已标注截图 + 同名 .json 标注数据存放于此 */
    private String classifyDir = "classify";

    /** 汇总分析产物保存目录（相对程序运行目录）：每个分类标注一个子目录，内含七张对照图与 info.json */
    private String summaryDir = "summary";

    /** 旧版单目录布局（截图/标注/sum 同根），仅用于启动时一次性迁移到 capture/classify/summary 新布局 */
    @Deprecated
    private String outputDir = "captures";

    /** WindowsCapture 采集器内部抓帧超时（毫秒） */
    private long captureTimeoutMs = 5000;

    /**
     * 强制截图目标宽度（像素）：开启截图后，凡是截出来不是该尺寸的帧一律不保存，
     * 并自动用 SetWindowPos 把窗口整体缩放/移动到目标尺寸后重试，直到截出的 PNG
     * 恰好等于「目标宽 × 目标高」才保存。0 = 不强制宽度。
     *
     * <p>注意：为保证画面不变形无黑边，请在目标程序中把它的显示分辨率/方向
     * 配置成与目标尺寸一致（例如 MuMu 里设 1280x720、16:9）。</p>
     */
    private int resizeWidth = 0;

    /**
     * 强制截图目标高度（像素），与 {@link #getResizeWidth()} 配套。
     * 宽高都 &gt;0 时才启用“尺寸强制 + 不达标自动调窗重试”；任一为 0 则按旧行为
     * 截图原样保存、不做尺寸校验。
     */
    private int resizeHeight = 0;

}
