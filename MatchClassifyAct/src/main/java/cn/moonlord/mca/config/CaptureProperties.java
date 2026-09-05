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
     * 开启截图时，把目标窗口「截图内容区（客户区）」宽度调整为该像素值
     * （物理像素），高度按当前宽高比缩放、位置不变；0 表示不调整。
     */
    private int resizeWidth = 0;

}
