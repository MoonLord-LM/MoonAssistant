package cn.moonlord.mca.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图片 / 数据三阶段目录的统一取法（所有模块共用，避免各自拼接路径不一致）。
 *
 * <pre>
 *   capture/   捕获的原始截图（未标注）
 *   classify/  标注后的截图 + 同名 .json 标注数据
 *   summary/   汇总分析产物：&lt;分类标注&gt;/ 下对照图（15 张基础合成图 = 交集六档 same100/90/80/70/60/50
 *              （100% = 全部样本像素一致，其余覆盖 &gt;90%/80%/70%/60%/50%）与多数/均值/去重均值/8·32 块图族，
 *              全部参与识别比对；每张基础图各带 1 张 -unique 独有区图共 15 张，同样全部参与比对；
 *              关注点有效的每个分类（click=点击位置 / 无动作=画面关注区域，默认屏幕中心）另以该坐标为中心
 *              生成 12 张点击区交集图 click8/32-same100/90/80/70/60/50（每分类 = 42 张），全部参与比对）+ info.json；
 *              根目录另有几个非产物的数据文件（可手删）：verify.json = 特征验证结果的完整缓存（见 VerifyService）、
 *              opt-result.json = 算法调优结果的完整缓存、opt-weights.json = 算法调优的特征选择 + 权重数值快照（见 OptimizeService），
 *              它们都不参与指纹统计（写自己不会把自己判成过期）
 * </pre>
 *
 * <p>均以进程工作目录为基准取绝对路径，便于直接查看/备份数据目录。</p>
 */
@Component
@RequiredArgsConstructor
public class StoragePaths {

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

    private Path resolve(String name) {
        return Paths.get(name).toAbsolutePath().normalize();
    }
}
