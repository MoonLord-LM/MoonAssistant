package cn.moonlord.mca.mark;

import lombok.Data;

/**
 * 单张截图的标注内容（API / 页面统一形状：{@code state/action/left/top/attnLeft/attnTop}）。
 *
 * <p><b>存储已中心化</b>：动作与坐标是“分类级定义”，收敛在 {@code classify/data.json}
 * （每分类一份，见 {@link ClassifyStore}）；图片旁同名 json（IMG_x.png → IMG_x.json）
 * 只记分类归属 {@code { "state": "登录页" }}。读取时由 {@link ClassifyStore#readSample(String)}
 * 以“样本 state + 中心表定义”合成本对象，因此对接口与页面保持原字段形状，数据不再逐图冗余。</p>
 *
 * <p>截图输出的是窗口自身的物理像素内容，因此“图片像素坐标”就是“窗口相对坐标”。两类点互相独立：
 * <ul>
 *   <li><b>鼠标点击点</b>（left/top，红点）：仅 click 分类有意义，是执行模式实际点击的坐标，
 *       也是点击区交集图的框心；</li>
 *   <li><b>注意点</b>（attnLeft/attnTop，绿点）：任意分类都可手工标注，是“画面关注区域”——
 *       注意区交集图与匹配裁剪都以它为中心；<b>全部分类一致的默认值 = 屏幕中心</b>
 *       （未设即按画面尺寸取中心，不再回退点击点）。</li>
 * </ul></p>
 */
@Data
public class CaptureMark {

    public static final String ACTION_NONE = "none";   // 无动作（注意点 = 画面关注区域，默认屏幕中心）
    public static final String ACTION_CLICK = "click"; // 鼠标点击[窗口相对坐标]

    /** 当前画面状态（状态标签），任意 GUI 程序皆适用，如 登录页 / 主界面 / 弹窗 / 无响应 */
    private String state = "";

    /** 动作标记：none | click */
    private String action = ACTION_NONE;

    /** 鼠标点击点（图片像素坐标，仅 click 分类）：执行模式点击的位置；无动作分类恒为 null */
    private Integer left;

    /** 鼠标点击点（图片像素坐标，仅 click 分类）：执行模式点击的位置；无动作分类恒为 null */
    private Integer top;

    /** 注意点（图片像素坐标，全部分类可选）：注意区图 / 匹配裁剪以它为中心；null = 未设（默认屏幕中心） */
    private Integer attnLeft;

    /** 注意点（图片像素坐标，全部分类可选）：注意区图 / 匹配裁剪以它为中心；null = 未设（默认屏幕中心） */
    private Integer attnTop;

    /** 注意点（注意区图框心 / 匹配裁剪中心）：attn 有效 → 返回；null = 未设，
     *  由调用方按画面尺寸取<b>屏幕中心</b>兜底（全部分类一致，不再回退鼠标点击点）。 */
    public static int[] attentionOf(CaptureMark m) {
        if (m == null) {
            return null;
        }
        Integer ax = m.getAttnLeft(), ay = m.getAttnTop();
        if (ax != null && ay != null && ax >= 0 && ay >= 0) {
            return new int[] { ax, ay };
        }
        return null;
    }

}
