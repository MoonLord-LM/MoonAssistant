package cn.moonlord.tempfilestorage.controller;

import cn.moonlord.tempfilestorage.service.DiskScanService;
import cn.moonlord.tempfilestorage.service.VideoScanService;
import cn.moonlord.tempfilestorage.vo.DiskHardwareVO;
import cn.moonlord.tempfilestorage.vo.LogicalVolumeVO;
import cn.moonlord.tempfilestorage.vo.VideoFileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Controller
public class PageController {

    @Autowired
    private DiskScanService diskScanService;

    @Autowired
    private VideoScanService videoScanService;

    @GetMapping("/disk/show")
    public String showDiskInfo(Model model) {
        CopyOnWriteArrayList<DiskHardwareVO> disks = diskScanService.getDiskHardware();
        CopyOnWriteArrayList<LogicalVolumeVO> logicalVolumes = diskScanService.getLogicalVolume();
        String generatedTime = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).format(new Date());
        model.addAttribute("disks", disks);
        model.addAttribute("volumes", logicalVolumes);
        model.addAttribute("generatedTime", generatedTime);
        return "disk-show";
    }

    @GetMapping("/video/show")
    public String showVideoInfo(Model model) {
        CopyOnWriteArrayList<VideoFileVO> videos = videoScanService.getAllMarkedVideos();
        String generatedTime = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).format(new Date());

        Long size = videos.stream().mapToLong(VideoFileVO::getFileSize).sum();
        String totalSize = VideoFileVO.formatFileSize(size);

        Map<String, Integer> actorWorkCounts = new HashMap<>();
        Map<String, List<Double>> actorScores = new HashMap<>();
        Map<String, Double> actorAvgScores = new HashMap<>();
        for (VideoFileVO video : videos) {
            for (String actor : video.getActorNames()) {
                actorWorkCounts.put(actor, actorWorkCounts.getOrDefault(actor, 0) + 1);
                actorScores.computeIfAbsent(actor, k -> new ArrayList<>()).add(Double.valueOf(video.getScore()));
            }
        }
        for (Map.Entry<String, List<Double>> entry : actorScores.entrySet()) {
            double average = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            actorAvgScores.put(entry.getKey(), average);
        }

        List<Map.Entry<String, Integer>> topWorkActors = actorWorkCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(30)
                .collect(Collectors.toList());

        List<Map.Entry<String, Double>> topRatedActors = actorAvgScores.entrySet().stream()
                .sorted((e1, e2) -> {
                    int scoreCompare = e2.getValue().compareTo(e1.getValue());
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return actorWorkCounts.getOrDefault(e2.getKey(), 0).compareTo(actorWorkCounts.getOrDefault(e1.getKey(), 0));
                })
                .limit(30)
                .collect(Collectors.toList());

        List<VideoFileVO> topSizeVideos = videos.stream()
                .sorted((v1, v2) -> Long.compare(v2.getFileSize(), v1.getFileSize()))
                .limit(30)
                .peek(v -> {v.addVideoInfo(v.getFile());})
                .collect(Collectors.toList());

        model.addAttribute("videos", videos);
        model.addAttribute("totalSize", totalSize);
        model.addAttribute("generatedTime", generatedTime);
        model.addAttribute("topWorkActors", topWorkActors);
        model.addAttribute("topRatedActors", topRatedActors);
        model.addAttribute("actorWorkCounts", actorWorkCounts);
        model.addAttribute("actorAvgScores", actorAvgScores);
        model.addAttribute("topSizeVideos", topSizeVideos);
        return "video-show";
    }

}
