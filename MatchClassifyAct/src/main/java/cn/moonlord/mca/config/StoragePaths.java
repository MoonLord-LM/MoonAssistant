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
 *   summary/   汇总分析产物：&lt;分类标注&gt;/ 下对照图（10 张基础合成图，含交集 90/80/70/60 四档，
 *              其中主档交集与其余 6 张核心基础图各带 -unique 独有区图共 7 张；
 *              点击动作且有坐标的分类另有 2 张点击区交集图；
 *              交集 80/70/60 展示档仅目检、不参与识别）+ info.json
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
