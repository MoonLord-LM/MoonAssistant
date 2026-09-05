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
     * 识别阈值（不匹配点占比百分比，0~100）。当前画面与每个分类的「汇总分析产物」（summary/ 下 7 张对照图）
     * 逐张同尺度比对后，取该分类 7 图各自的「不匹配点占比」做算术平均；不同分类按各自的平均占比比较，
     * 取最小者为最近似结果。仅当该平均占比 ≤ 本阈值时才判定为「已识别」，
     * 大于阈值则视为「未识别（画面不属于任何已标注状态）」（未识别也会给出最近似分类与占比供界面参考）。
     */
    private double matchThresholdPercent = 25;

    /**
     * RGB 三维空间中判定单个对应像素点是否「匹配」的欧氏距离阈值（0~441，默认 256）。
     * 把每个像素当作 (R,G,B) 三维空间里的一点，两点距离 = √(ΔR² + ΔG² + ΔB²)；
     * 距离小于本阈值判为「匹配」点，大于等于本阈值判为「不匹配」点。
     * 调大更宽容颜色偏差（更容易识别），调小更严格；具体分类判定请配合 {@link #matchThresholdPercent}。
     */
    private int rgbDistThreshold = 256;

    /**
     * 前台点击（screen 模式）里「切换到前台 → 真正发送鼠标输入」的等待上限（毫秒）。
     * SetForegroundWindow 并非同步生效：窗口从前台切换到真正可接收输入的焦点之间常有可见延迟，
     * 且可能被系统前台锁定、被其他窗口抢占而失效。本工程采用「F22 解锁键 + SetForegroundWindow
     * 周期重试 + 轮询 GetForegroundWindow 确认前台焦点已归属目标窗口（顶层根句柄核对，比只查进程更严——
     * 同进程其他浮窗抢在前台也不会放行）」的方式等待；超过本上限仍未就绪则取消本次点击并报错
     * （避免误点到别的窗口），并在日志里输出当前前台句柄供排查。
     */
    private long foregroundWaitMs = 2500;

    /**
     * 前台焦点确认到位后，额外等待的“激活稳定”时长（毫秒），让窗口完成激活处理/重绘再发送鼠标输入。
     */
    private long foregroundSettleMs = 120;

    /**
     * 鼠标点击的执行方式（控制台「执行模式」页可实时切换，本值为启动默认）：
     * <ul>
     *   <li>{@code post}（默认）—— 后台消息：向目标窗口投递完整点击消息序列（3 次 {@code WM_MOUSEMOVE}
     *       滑入轨迹 → {@code WM_MOUSEACTIVATE} 点击意图 → {@code WM_LBUTTONDOWN / WM_LBUTTONUP}，
     *       客户区坐标 = 图片像素 − 标题栏/边框偏移），不要求窗口在前台/可见、不抢占用户焦点。
     *       比只发按下/抬起更易被普通桌面程序接受；游戏 / 模拟器多数仍忽略合成消息，此时切前台；</li>
     *   <li>{@code screen} —— 前台点击：截图画面 = 窗口整窗外框（采集器按 GetWindowRect 裁取），
     *       用「外框左上角 + 图片像素」得到屏幕坐标，把窗口带到前台后用
     *       {@code SetCursorPos + mouse_event} 模拟一次真实左键点击。
     *       模拟器 / 游戏必须用此项，否则点击无效。要求目标窗口可见且不被完全遮挡。</li>
     * </ul>
     */
    private String clickMode = "post";
}
