package cn.moonlord.mca.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行模式（自动识别 + 自动动作）可调参数（前缀 {@code execute.*}），默认值固化在代码里，可被 Spring 外部化配置覆盖。
 */
@Data
@ConfigurationProperties(prefix = "execute")
public class ExecuteProperties {

    /**
     * 执行循环每轮「截图 + 识别最新画面」的间隔（毫秒）。「开始执行循环」后由后台按此周期自动运行；
     * 单次「立即识别一次」不受该间隔限制。
     */
    private long intervalMs = 2000;

    /**
     * 识别阈值（平均像素差异百分比，0~100）。把当前画面与 classify/ 已标注样本逐张比较后，
     * 取“差异最小”的分类为最近似结果；仅当该差异 ≤ 本阈值时才判定为「已识别」，
     * 大于阈值则视为「未识别（画面不属于任何已标注状态）」，界面上会展示最近似分类与差异供参考。
     */
    private double matchThresholdPercent = 25;

    /**
     * 鼠标点击的执行方式：
     * <ul>
     *   <li>{@code post}（默认）—— 后台点击：直接向目标窗口 {@code PostMessage}
     *       {@code WM_LBUTTONDOWN / WM_LBUTTONUP}，坐标 = 图片像素 = 窗口相对坐标。不要求窗口在前台/可见，
     *       不抢占用户焦点；</li>
     *   <li>{@code screen} —— 真实输入：把窗口相对坐标换算成屏幕坐标后，
     *       用 {@code SetCursorPos + mouse_event} 模拟一次真实的鼠标左键点击。要求目标窗口可见且不被其它窗口遮挡。</li>
     * </ul>
     */
    private String clickMode = "post";
}
