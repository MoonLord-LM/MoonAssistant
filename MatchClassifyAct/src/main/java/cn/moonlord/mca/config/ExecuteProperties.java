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
     * 差异度参考阈值（百分比，0~100）。识别只按【算法调优】落地在 {@code resource/runtime/} 的
     * <b>综合最佳算法</b>（综合分 =（1 − 无法区分率）× 匹配正确率 最高的那一种）比对：
     * 算法 = 若干特征（产物 kind + 基础分 X + 权重 Y），逐特征把当前画面与该分类在
     * {@code resource/runtime/&lt;分类&gt;/} 下对应的那张对照图同尺度逐点比对得不匹配点占比，
     * 匹配值 = 100 − 不匹配点占比；先放弃「最高匹配值被 ≥2 个分类并列」的特征（并列只在有有效产物的分类间数），
     * 再算加权匹配度 = Σ「匹配值 × X × Y」÷ Σ「X × Y」（只累加该分类可用的特征），
     * 分类差异度 = 100 − 加权匹配度。产物无任何有效像素的空图（独有区图无独有点等）
     * 没有可判别点、无法做区分，判完全不匹配、按不匹配占比满值计入并照常参与加权。
     * 不同分类按差异度比较取最小者为最近似结果；最高匹配度被 ≥2 个分类并列 = 无法区分（不动作）。
     * 识别不设阈值门槛（有唯一最近似分类即可用），本值仅随识别结果返回供界面参考展示。
     */
    private double matchThresholdPercent = 25;

    /**
     * <b>均值型对照图</b>（avg、dedup-avg、avg8、dedup-avg8、avg32、dedup-avg32 及各自的
     * -unique 独有区图）判定单个像素是否「匹配」的逐通道色差阈值（0~255）：
     * 分别取两像素 R、G、B 三通道差值的绝对值，
     * 三通道差都 ≤ 本阈值判「匹配」，任一通道差 > 本阈值判「不匹配」。
     * 均值图的对照色是样本的逐通道平均值 / 去重平均值（先对样本出现过的颜色去重再平均），
     * 真实画面几乎不会恰好等于它，故用容差判定。
     * <b>交集/多数/方框交集型对照图</b>（交集六档 same100/90/80/70/60/50、major、major8、major32 及各自的
     * -unique 独有区图，以及每个分类按注意点（未设 = 屏幕中心）生成的 12 张注意区交集图
     * attn8/32-same100/90/80/70/60/50、鼠标点击分类按鼠标点击点生成的 12 张点击区交集图
     * click8/32-same100/90/80/70/60/50）
     * 固定按「逐像素完全一致」判据
     * （R/G/B 三通道差都为 0 才算匹配，等价于本阈值取 0），不受本配置影响。
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
     *   <li>{@code mumu}（默认）—— MuMu 模拟器：不碰鼠标、不动窗口焦点，把这次点击交给
     *       {@code MuMuManager.exe adb -v <实例序号> -c "shell input tap x y"} 由模拟器自己的 adb 通道注入，
     *       模拟器在后台 / 窗口被遮挡也能点。点击坐标要从「窗口截图坐标」分两步换算成「模拟器内坐标」——
     *       先减掉模拟器边框占用 {@link #mumuClickOffsetX}/{@link #mumuClickOffsetY}，
     *       再按「游戏画面 → 模拟器实际分辨率」等比放大（常量在 {@code WindowClicker}）；</li>
     *   <li>{@code screen} —— 前台点击：截图画面 = 窗口整窗外框（采集器按 GetWindowRect 裁取），
     *       用「外框左上角 + 图片像素」得到屏幕坐标，把窗口带到前台后用
     *       {@code SetCursorPos + mouse_event} 模拟一次真实左键点击。要求目标窗口可见且不被完全遮挡；</li>
     *   <li>{@code rawinput} —— RawInput 输入：坐标换算同 {@code screen}，但不做前台切换，
     *       直接注入系统级真实鼠标输入（{@code SendInput}：绝对移动 → 左键按下 → 抬起，点完把光标移回原位）。
     *       注入事件会进系统输入链，认 RawInput / DirectInput（或轮询 {@code GetCursorPos}）的程序也能收到
     *       （{@code screen} 用的 {@code mouse_event} 是遗留接口，这类程序常常收不到）。
     *       不要求窗口前台、也不等待前台；但真实鼠标语义是「投给光标下的窗口」，所以仍要求目标点在屏幕上
     *       可见 —— 注入前会核对目标点归属，被别的窗口遮挡就取消本次点击并说明命中的是谁。
     *       需要窗口被遮挡也能点（完全后台）请用 {@code mumu} / {@code post}；</li>
     *   <li>{@code post} —— 后台消息：向目标窗口投递完整点击消息序列（3 次 {@code WM_MOUSEMOVE}
     *       滑入轨迹 → {@code WM_MOUSEACTIVATE} 点击意图 → {@code WM_LBUTTONDOWN / WM_LBUTTONUP}，
     *       客户区坐标 = 图片像素 − 标题栏/边框偏移），不要求窗口在前台/可见、不抢占用户焦点。
     *       比只发按下/抬起更易被普通桌面程序接受；游戏 / 模拟器多数仍忽略合成消息。</li>
     * </ul>
     */
    private String clickMode = "mumu";

    /**
     * MuMu 模拟器模式用来发送点击的 {@code MuMuManager.exe} 位置（MuMu 12 的默认安装路径）。
     * 换机器 / 换安装位置时改这里（或外部化配置覆盖）。
     */
    private String mumuManagerPath = "C:\\Program Files\\Netease\\MuMuPlayer-12.0\\nx_main\\MuMuManager.exe";

    /**
     * MuMu 模拟器模式坐标换算的<b>第一步</b>：从窗口截图坐标里减掉的横向边框占用（像素）——
     * 识别点 x − 本值 = 游戏画面内 x。模拟器把游戏画面居中嵌在自己的窗口里，左右两侧各让出这么多像素，
     * 游戏画面只占中间那一段（游戏画面宽度 = 截图宽度 − 2 × 本值）；减成负数按 {@code 0} 处理。
     * 本值以<b>窗口截图像素</b>计，「游戏画面 → 模拟器实际分辨率」的放大是第二步（见 {@code WindowClicker}）。
     */
    private int mumuClickOffsetX = 55;

    /**
     * 同上，纵向偏移（像素）：识别点 y − 本值 = 游戏画面内 y。模拟器上边缘占掉这么多像素、
     * 下边缘为 0（即只有上方要减）；减成负数按 {@code 0} 处理。
     */
    private int mumuClickOffsetY = 60;
}
