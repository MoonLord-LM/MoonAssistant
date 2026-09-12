package cn.moonlord.mca.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图片 / 数据四分区目录的统一取法（所有模块共用，避免各自拼接路径不一致）。
 *
 * <p>四个分区统一收纳在运行目录下的 {@value #ROOT_DIR}/ 里（便于整体备份 / 搬迁 / 清理），
 * 各分区目录名由 {@code capture.*-dir} 配置项给出、相对 {@value #ROOT_DIR}/ 解析，
 * 也可以直接配成绝对路径（配了绝对路径就按绝对路径用、不再拼 {@value #ROOT_DIR}/）。</p>
 *
 * <pre>
 *   resource/capture/   捕获的原始截图（未标注）
 *   resource/classify/  标注后的截图 + 同名 .json 标注数据
 *   resource/summary/   汇总分析产物：&lt;分类标注&gt;/ 下对照图（15 张基础合成图 = 交集六档 same100/90/80/70/60/50
 *              （100% = 全部样本像素一致，其余覆盖 &gt;90%/80%/70%/60%/50%）与多数/均值/去重均值/8·32 块图族，
 *              全部参与识别比对；每张基础图各带 1 张 -unique 独有区图共 15 张，同样全部参与比对；
 *              关注点有效的每个分类（click=点击位置 / 无动作=画面关注区域，默认屏幕中心）另以该坐标为中心
 *              生成 12 张点击区交集图 click8/32-same100/90/80/70/60/50（每分类 = 42 张），全部参与比对）+ info.json；
 *              根目录另有几个非产物的数据文件（可手删）：verify.json = 特征验证结果的完整缓存（见 VerifyService）、
 *              opt-result.json = 算法调优结果的完整缓存、opt-weights.json = 算法调优的特征选择 + 权重数值快照（见 OptimizeService），
 *              它们都不参与指纹统计（写自己不会把自己判成过期）
 *   resource/runtime/   运行时算法：算法调优每跑完一轮把选出的「综合最佳算法」落地到这里（&lt;分类&gt;/ 下只放该算法
 *              「生效特征」（权重 Y &gt; 0）那几个 kind 的对照图产物 + algorithm.json 记算法特征与各分类动作 / 坐标），
 *              执行模式与「未标注」的单图智能推荐只认这份落地物、也只按生效特征比对与加权
 *              （不再直接读 resource/summary/）；整个目录可随时删除后重跑算法调优重建
 * </pre>
 *
 * <p>均以进程工作目录为基准取绝对路径，便于直接查看/备份数据目录。</p>
 */
@Component
@RequiredArgsConstructor
public class StoragePaths {

    /** 四个数据分区统一收纳的运行目录子目录名（相对进程工作目录） */
    private static final String ROOT_DIR = "resource";

    private final CaptureProperties properties;

    /** 捕获的原始截图目录 */
    public Path capture() {
        return resolve(properties.getCaptureDir());
    }

    /** 标注后的截图与数据目录 */
    public Path classify() {
        return resolve(properties.getClassifyDir());
    }

    /** 汇总分析产物目录 */
    public Path summary() {
        return resolve(properties.getSummaryDir());
    }

    /** 运行时算法目录（算法调优「综合最佳算法」生效特征的对照图 + algorithm.json；执行模式与单图智能推荐只认它、也只按生效特征比对） */
    public Path runtime() {
        return resolve(properties.getRuntimeDir());
    }

    /** 相对目录名统一挂到 {@value #ROOT_DIR}/ 下；配了绝对路径的则原样使用（Path#resolve 对绝对路径直接返回它自身） */
    private Path resolve(String name) {
        return declared(name).toAbsolutePath().normalize();
    }

    /** 分区目录「怎么配就怎么写」的写法：相对目录名前挂 {@value #ROOT_DIR}/、绝对路径原样；{@link #resolve} 在它之上补绝对化。 */
    private static Path declared(String name) {
        return Paths.get(ROOT_DIR).resolve(name);
    }

    /** 已标注目录下某个文件的落盘写法（{@value #ROOT_DIR}/&lt;分区目录&gt;/&lt;文件名&gt;）：落盘进 json / 展示给
     *  用户的路径一律用它 —— 本机绝对路径一旦进了产物（如算法调优摘要里的权重文件位置），换台机器 / 换个目录打开
     *  就指向别人机器上的位置，既无从核对又泄露本机路径。与 {@link #classify()} 走同一条拼法，所以分区目录改名 /
     *  配成绝对路径时它跟着变，不会记成一个对不上的位置。 */
    public String classifyFile(String fileName) {
        return declared(properties.getClassifyDir()).resolve(fileName).toString();
    }
}
