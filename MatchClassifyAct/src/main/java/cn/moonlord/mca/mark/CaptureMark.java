package cn.moonlord.mca.mark;

import lombok.Data;

/**
 * 单张截图的标注内容（API / 页面统一形状：{@code state/action/left/top}）。
 *
 * <p><b>存储已中心化</b>：动作与坐标是“分类级定义”，收敛在 {@code classify/data.json}
 * （每分类一份，见 {@link ClassifyStore}）；图片旁同名 json（IMG_x.png → IMG_x.json）
 * 只记分类归属 {@code { "state": "登录页" }}。读取时由 {@link ClassifyStore#readSample(String)}
 * 以“样本 state + 中心表定义”合成本对象，因此对接口与页面保持原字段形状，数据不再逐图冗余。</p>
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
