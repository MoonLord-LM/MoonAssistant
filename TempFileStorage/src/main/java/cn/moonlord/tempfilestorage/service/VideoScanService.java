package cn.moonlord.tempfilestorage.service;

import cn.moonlord.tempfilestorage.vo.LogicalVolumeVO;
import cn.moonlord.tempfilestorage.vo.VideoFileVO;
import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class VideoScanService {

    private static final List<Integer> SCORES = List.of(100, 90, 80, 70, 60);

    @Autowired
    private DiskScanService diskScanService;

    @SneakyThrows
    public CopyOnWriteArrayList<VideoFileVO> getAllMarkedVideos() {
        log.info("begin scan");
        CopyOnWriteArrayList<VideoFileVO> result = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<LogicalVolumeVO> volumes = diskScanService.getLogicalVolume();
        for (LogicalVolumeVO volume : volumes) {
            for (int score : SCORES) {
                File dir = new File(volume.getDeviceID() + "\\Temp\\Checked\\" + score);
                log.info("try dir: {}", dir.getAbsolutePath());
                if (dir.exists() && dir.isDirectory()) {
                    log.info("begin scan dir: {}", dir.getAbsolutePath());
                    System.gc();
                    File[] files = dir.listFiles(file -> file.isFile() && !file.getName().equals("desktop.ini"));
                    if (files != null) {
                        for (File file : files) {
                            log.debug("file: {}", file.getAbsolutePath());
                            VideoFileVO videoFile = new VideoFileVO(file);
                            result.add(videoFile);
                        }
                    }
                }
            }
        }
        log.info("scan result size: {}", result.size());
        return result;
    }

}
