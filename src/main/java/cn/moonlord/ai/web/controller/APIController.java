package cn.moonlord.ai.web.controller;

import cn.moonlord.ai.run.PerformanceRecorder;
import cn.moonlord.ai.run.ScreenshotRecorder;
import cn.moonlord.ai.run.ScreenshotVideoRecorder;
import cn.moonlord.ai.web.vo.PerformanceVO;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@CrossOrigin(origins = "*")
@RestController
public class APIController {

    private static final Map<String, Object> cache = new ConcurrentHashMap<>();
    @Autowired
    private PerformanceRecorder performanceRecorder;
    @Autowired
    private ScreenshotRecorder screenshotRecorder;
    @Autowired
    private ScreenshotVideoRecorder screenshotVideoRecorder;

    @SneakyThrows
    @RequestMapping("/api")
    public String api() {
        return "Hello World";
    }

    @SneakyThrows
    @RequestMapping("/api/performance")
    public PerformanceVO performance() {
        return performanceRecorder.getPerformance();
    }

    @SneakyThrows
    @RequestMapping("/api/screenshot")
    public ResponseEntity<byte[]> screenshot(@RequestParam(value = "scale", required = false) Double scale, @RequestParam(value = "format", required = false) String format) {
        BufferedImage screenCapture = screenshotRecorder.getScaleScreenCapture(scale);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (format != null && format.equals("jpg")) {
            ImageIO.write(screenCapture, "jpg", output);
            return ResponseEntity.ok().contentType(MediaType.valueOf(MediaType.IMAGE_JPEG_VALUE)).body(output.toByteArray());
        } else if (format != null && format.equals("gif")) {
            ImageIO.write(screenCapture, "gif", output);
            return ResponseEntity.ok().contentType(MediaType.valueOf(MediaType.IMAGE_GIF_VALUE)).body(output.toByteArray());
        } else {
            ImageIO.write(screenCapture, "png", output);
            return ResponseEntity.ok().contentType(MediaType.valueOf(MediaType.IMAGE_PNG_VALUE)).body(output.toByteArray());
        }
    }

    @SneakyThrows
    @RequestMapping("/api/screenshot/hls.m3u8")
    public String getHLSPlaylist() {
        new Thread(() -> screenshotVideoRecorder.record()).start();
        return screenshotVideoRecorder.getHLSPlaylist();
    }

    @SneakyThrows
    @RequestMapping("/api/screenshot/video{segmentNumber}.ts")
    public ResponseEntity<byte[]> getHLSSegment(@PathVariable("segmentNumber") Integer segmentNumber) {
        byte[] data = screenshotVideoRecorder.getData();
        return ResponseEntity.ok().contentType(MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM_VALUE)).body(data);
    }

}
