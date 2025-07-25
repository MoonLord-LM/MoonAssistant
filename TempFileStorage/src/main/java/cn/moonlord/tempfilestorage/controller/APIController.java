package cn.moonlord.tempfilestorage.controller;

import cn.moonlord.tempfilestorage.service.DiskScanService;
import cn.moonlord.tempfilestorage.service.VideoScanService;
import cn.moonlord.tempfilestorage.vo.DiskHardwareVO;
import cn.moonlord.tempfilestorage.vo.LogicalVolumeVO;
import cn.moonlord.tempfilestorage.vo.VideoFileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@CrossOrigin(origins = "*")
@RestController
public class APIController {

    @Autowired
    private DiskScanService diskScanService;

    @Autowired
    private VideoScanService videoScanService;

    @GetMapping("/disk/getDiskHardware")
    public CopyOnWriteArrayList<DiskHardwareVO> getDiskHardware() {
        return diskScanService.getDiskHardware();
    }

    @GetMapping("/disk/getLogicalVolume")
    public CopyOnWriteArrayList<LogicalVolumeVO> getLogicalVolume() {
        return diskScanService.getLogicalVolume();
    }

    @GetMapping("/video/getAllMarkedVideos")
    public CopyOnWriteArrayList<VideoFileVO> getAllMarkedVideos() {
        return videoScanService.getAllMarkedVideos();
    }

}
