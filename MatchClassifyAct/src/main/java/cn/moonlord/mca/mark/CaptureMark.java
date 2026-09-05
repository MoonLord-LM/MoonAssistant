package cn.moonlord.mca.mark;

import lombok.Data;

/**
 * 单张截图的标注内容，保存为与图片同名的 {@code .json}（如 IMG_x.png → IMG_x.json）。
 *
 * <p>JSON 结构（{@code left/top} 仅在动作 click 时出现）：</p>
 * <pre>{@code
 * {
 *   "state":  "登录页",      // 当前画面状态标签（自由文本）
 *   "action": "click",      // none=无动作 | click=鼠标点击
 *   "left":   640,          // 动作 click 时的窗口相对坐标 X（图片像素）
 *   "top":    360           // 动作 click 时的窗口相对坐标 Y
 * }
 * }</pre>
 *
 * <p>截图输出的是窗口自身的物理像素内容，因此“图片像素坐标”就是“窗口相对坐标”，
 * 后续 Match/Classify/Act 阶段可直接按此坐标执行鼠标动作。</p>
 */
@Data
public class CaptureMark {

    public static final String ACTION_NONE = "none";   // 无动作
    public static final String ACTION_CLICK = "click"; // 鼠标点击[窗口相对坐标]

    /** 当前画面状态（状态标签），任意 GUI 程序皆适用，如 登录页 / 主界面 / 弹窗 / 无响应 */
    private String state = "";

    /** 动作标记：none | click */
    private String action = ACTION_NONE;

    /** 动作 click 时的窗口相对坐标 X（图片像素） */
    private Integer left;

    /** 动作 click 时的窗口相对坐标 Y（图片像素） */
    private Integer top;

}
