package cn.moonlord.mca.capture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后启动「历史重复清理 + 去重基准预热」后台任务。
 *
 * <p>就绪事件（ApplicationReadyEvent）晚于全部 ApplicationRunner 执行，因此触发时
 * resource/capture/ resource/classify/ resource/summary/ 三阶段目录已就位，本次清理一次即可覆盖全量历史截图；
 * 历史重复清理按「逐像素完全一致」判据只删与保留图完全相同的重复图（与手动存入判重
 * 同口径，近似画面一律保留），随后 {@link ScreenCaptureService} 会把保留全集缩略图
 * 重建进内存缓存，作为运行期逐帧去重（capture.diff-threshold-percent）的比对基准。
 * 任务在独立后台线程执行，不阻塞启动；开启截图前的首轮去重判定会等它完成，
 * 故不会与清理阶段的文件删除并发。
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
