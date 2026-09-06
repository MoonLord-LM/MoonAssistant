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
     * 差异度参考阈值（百分比，0~100）。当前画面与每个分类的「汇总分析产物」（summary/ 下 14 张对照图，
     * 7 张基础图 same / max / avg / major8 / avg8 / major32 / avg32 及其各自的 -unique 独有区图）逐张
     * 同尺度比对得各图「不匹配点占比」后，按加权平均聚合为分类差异度
     * = (独有交集图占比×50 + 交集图占比×30 + 其余 12 张图占比的平均×20) / 100
     * （交集 / 独有交集锁定样本的公共稳定区与独占核心区，权重最高）。
     * 不同分类按差异度比较取最小者为最近似结果。识别不设阈值门槛（有最近似分类即可用），
     * 本值仅随识别结果返回供界面参考展示。
     */
    private double matchThresholdPercent = 25;

    /**
     * <b>均值型对照图</b>（avg、avg8、avg32 及各自的 -unique 独有区图）判定单个像素是否「匹配」的
     * 逐通道色差阈值（0~255，默认 255/3 = 85）：分别取两像素 R、G、B 三通道差值的绝对值，
     * 三通道差都 ≤ 本阈值判「匹配」，任一通道差 > 本阈值判「不匹配」。
     * 均值图的对照色是样本的逐通道平均值，真实画面几乎不会恰好等于它，故用容差判定。
     * <b>交集/多数型对照图</b>（same、major、major8、major32 及各自的 -unique 独有区图）固定按
     * 「逐像素完全一致」判据（R/G/B 三通道差都为 0 才算匹配，等价于本阈值取 0），不受本配置影响。
     * 调大更宽容颜色偏差（更容易识别），调小更严格。
     */
    private int rgbDistThreshold = 255 / 3;

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
