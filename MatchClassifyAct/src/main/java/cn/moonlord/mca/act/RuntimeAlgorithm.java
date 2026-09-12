package cn.moonlord.mca.act;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运行时算法：算法调优选出的「综合最佳算法」（综合分 = （1 − 无法区分率）× 匹配正确率 最高）的落地形态。
 *
 * <p>算法调优每跑完一轮就把它生成到 {@code resource/runtime/}：{@code algorithm.json} 是本类的 JSON 形态，
 * 同目录下按分类分子目录存放该算法<b>生效特征</b>的对照图产物（每个生效特征 kind 一张）。
 * 执行模式与「未标注」的单图智能推荐<b>只认这份落地物</b>（不再直接读 resource/summary/ 下的对照图），
 * 于是运行时与标注 / 汇总分析过程解耦：算法调优跑过一次即可长期执行，直到下次调优刷新。
 *
 * <p><b>只落地生效特征</b>（{@link #effective}）：权重 Y &gt; 0 的特征才算生效特征，落地与识别都只用它们 ——
 * 每个分类只复制生效特征那几个 kind 的产物、{@code algorithm.json} 里也只写这几个特征，识别时自然只按它们
 * 比对与加权（Y = 0 的特征在 Σ「X × Y」里恒为 0，对匹配值没有任何贡献，少一个特征就少复制 / 少解码一张 3.7MB 产物）。
 *
 * <p>判分口径与算法调优的评价完全一致，见 {@link FrameClassifier#classifyByAlgorithm}。
 */
public record RuntimeAlgorithm(String algoId, String algoName, double accuracy, double tieRate, double score,
                               long builtMs, long costMs, String source,
                               List<Feature> features, List<State> states) {

    /** 算法里的一个特征：{@code kind} 是产物种类（见 ArtifactKind），{@code x} 基础分（B 自分类平均匹配值 − C 其它分类平均匹配值），
     *  {@code y} 权重（0~10，0 = 该特征不参与匹配值计算 → 不落地、也不参与识别，见 {@link #effective}）。 */
    public record Feature(String kind, double x, double y) {
    }

    /**
     * 一个分类在运行时用到的对照图产物与动作定义。
     *
     * @param state    分类标注（界面显示用）
     * @param dir      相对 {@code resource/runtime/} 的子目录名（= 汇总分析里的分类目录名）
     * @param action   动作类型（mouse-click / none 等，见 CaptureMark）
     * @param attnLeft 注意点 X（注意区方框图以它为中心生成，比对新画面时同坐标裁剪）
     * @param attnTop  注意点 Y
     * @param actLeft  点击点 X（仅鼠标点击分类有）
     * @param actTop   点击点 Y
     * @param width    产物分辨率宽（与画面不一致则该分类跳过比对）
     * @param height   产物分辨率高
     * @param files    特征 kind → 该分类目录下的产物文件名
     */
    public record State(String state, String dir, String action, Integer attnLeft, Integer attnTop,
                        Integer actLeft, Integer actTop, int width, int height, Map<String, String> files) {

        /** 某个特征产物在运行时的绝对路径（{@code root} = resource/runtime/ 目录）；该分类没有此特征产物时返回 null。 */
        public Path file(Path root, String kind) {
            String name = files.get(kind);
            return name == null ? null : root.resolve(dir).resolve(name);
        }
    }

    /**
     * 取「生效特征」= 权重 Y &gt; 0 的那些特征（Y = 0 = 该特征不参与匹配值计算：Σ「X × Y」里恒为 0）。
     *
     * <p>落地与识别都只用生效特征：{@code resource/runtime/&lt;分类&gt;/} 每个分类只复制这几个 kind 的产物、
     * {@code algorithm.json} 里也只写这几个特征，识别时自然只按它们比对与加权 —— 内容最小化，
     * 少一个特征就少复制 / 少解码一张产物。
     *
     * <p>唯一的例外是<b>全部 Y = 0</b> 的极端情形：此时 Σ「X × Y」恒为 0，加权会退化成「可用特征的等权平均」
     * （见 {@link FrameClassifier#classifyByAlgorithm}），这些特征其实仍在参与判定，故原样返回 ——
     * 否则会落地成一个没有任何特征的算法（读不出来 = 执行模式与单图智能推荐全部失效）。
     */
    public static List<Feature> effective(List<Feature> features) {
        if (features == null || features.isEmpty()) {
            return features == null ? List.of() : features;
        }
        List<Feature> out = new ArrayList<>(features.size());
        for (Feature f : features) {
            if (f != null && f.y() > 0) {
                out.add(f);
            }
        }
        return out.isEmpty() ? features : List.copyOf(out);
    }

    /** 算法参与比对的 kind 顺序（= 全部生效特征，权重 Y 都 &gt; 0；落地物里不含 Y = 0 的特征） */
    public List<String> kinds() {
        List<String> out = new ArrayList<>(features.size());
        for (Feature f : features) {
            out.add(f.kind());
        }
        return out;
    }
}
