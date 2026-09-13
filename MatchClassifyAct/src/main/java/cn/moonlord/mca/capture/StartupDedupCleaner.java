package cn.moonlord.mca.capture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后启动「历史重复清理 + 去重基准安装」后台任务。
 *
 * <p>就绪事件（ApplicationReadyEvent）晚于全部 ApplicationRunner 执行，因此触发时
 * resource/capture/ resource/classify/ resource/summary/ 三阶段目录已就位，本次清理一次即可覆盖全量历史截图；
 * 历史重复清理按「逐像素完全一致」判据只删与保留图完全相同的重复图（与手动存入判重
 * 同口径，近似画面一律保留），收尾时把保留全集换成 {@link ScreenCaptureService} 的去重基准，
 * 供运行期逐帧去重（capture.diff-threshold-percent）使用。
 *
 * <p>任务在独立后台线程（最低优先级）里跑，<b>不阻塞启动、也不阻塞任何其它功能</b>：
 * 全程不持去重锁，运行期判定不等它，页面按钮 / 截图循环 / 标注 / 汇总分析随时可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupDedupCleaner implements ApplicationListener<ApplicationReadyEvent> {

    private final ScreenCaptureService screenCaptureService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        screenCaptureService.runStartupDedupAndSeedInBackground();
    }
}
