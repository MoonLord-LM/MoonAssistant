package cn.moonlord.ai.web.controller;

import cn.moonlord.ai.run.PerformanceRecorder;
import cn.moonlord.ai.run.ScreenshotRecorder;
import cn.moonlord.ai.web.vo.PerformanceVO;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@CrossOrigin(origins = "*")
@RestController
public class APIController {

    @Autowired
    private PerformanceRecorder performanceRecorder;

    @Autowired
    private ScreenshotRecorder screenshotRecorder;

    private static final ConcurrentLinkedDeque<String> videoCache = new ConcurrentLinkedDeque<>();
    private static final Map<String, Object> cache = new ConcurrentHashMap<>();

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
        String filePath = "video.mp4";

        // cache
        if (cache.containsKey(filePath)) {
            return (String) cache.get(filePath);
        }

        synchronized (filePath.intern()) {
            // file
            if (new File("video.m3u8").exists()) {
                cache.putIfAbsent(filePath, FileUtils.readFileToString(new File("video.m3u8"), StandardCharsets.UTF_8));
                return (String) cache.get(filePath);
            }

            // ffmpeg
            String ffmpegCommand1 = "ffmpeg.exe -loglevel quiet -y -f gdigrab -i desktop -s 1280x720 -r 5 -t 30 -c:v libx264 -c:a aac -pix_fmt yuv420p " + filePath;
            // TODO String ffmpegCommand1 = "ffmpeg.exe -y -f gdigrab -i desktop -s 1280x720 -r 5 -t 30 -c:v libx264 -c:a aac -pix_fmt yuv420p " + filePath;
            Process process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", ffmpegCommand1});
            process.getOutputStream().close();
            Process finalProcess1 = process;
            new Thread(new Runnable() {
                @SneakyThrows
                @Override
                public void run() {
                    String input = new String(IOUtils.toCharArray(finalProcess1.getInputStream(), StandardCharsets.UTF_8));
                    // TODO
                    log.info("getHLSPlaylist finalProcess1 input: {}", input);
                }
            });
            new Thread(new Runnable() {
                @SneakyThrows
                @Override
                public void run() {
                    String error = new String(IOUtils.toCharArray(finalProcess1.getErrorStream(), StandardCharsets.UTF_8));
                    // TODO
                    log.info("getHLSPlaylist finalProcess1 error: {}", error);
                }
            });
            int exitCode = process.waitFor();
            process.destroy();
            if (exitCode == 0) {
                log.info("getHLSSegment ffmpegCommand1 success");
            } else {
                log.error("getHLSSegment ffmpegCommand1 failed");
            }
            String ffmpegCommand2 = "ffmpeg.exe -loglevel quiet -y -i " + filePath + " -c:v libx264 -c:a aac -f hls -hls_time 5 -hls_list_size 0 video.m3u8";
            // TODO String ffmpegCommand2 = "ffmpeg.exe -y -i " + filePath + " -c:v libx264 -c:a aac -f hls -hls_time 5 -hls_list_size 0 video.m3u8";
            process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", ffmpegCommand2});
            process.getOutputStream().close();
            Process finalProcess2 = process;
            new Thread(new Runnable() {
                @SneakyThrows
                @Override
                public void run() {
                    String input = new String(IOUtils.toCharArray(finalProcess2.getInputStream(), StandardCharsets.UTF_8));
                    // TODO
                    log.info("getHLSPlaylist finalProcess2 input: {}", input);
                }
            });
            new Thread(new Runnable() {
                @SneakyThrows
                @Override
                public void run() {
                    String error = new String(IOUtils.toCharArray(finalProcess2.getErrorStream(), StandardCharsets.UTF_8));
                    // TODO
                    log.info("getHLSPlaylist finalProcess2 error: {}", error);
                }
            });
            exitCode = process.waitFor();
            process.destroy();
            if (exitCode == 0) {
                log.info("getHLSSegment ffmpegCommand2 success");
            } else {
                log.error("getHLSSegment ffmpegCommand2 failed");
            }

            cache.putIfAbsent(filePath, FileUtils.readFileToString(new File("video.m3u8"), StandardCharsets.UTF_8));
            return FileUtils.readFileToString(new File("video.m3u8"), StandardCharsets.UTF_8);
        }
    }

    @SneakyThrows
    @RequestMapping("/api/screenshot/video{segmentNumber}.ts")
    public ResponseEntity<byte[]> getHLSSegment(@PathVariable("segmentNumber") Long segmentNumber) {
        // cache
        if (cache.containsKey(String.valueOf(segmentNumber))) {
            return ResponseEntity.ok().contentType(MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM_VALUE)).body((byte[]) cache.get(String.valueOf(segmentNumber)));
        }

        String filePath = "video" + segmentNumber + ".ts";
        synchronized (filePath.intern()) {
            log.debug("getHLSSegment segmentNumber: {} filePath: {} exists: {}", segmentNumber, filePath, new File(filePath).exists());

            if (new File(filePath).exists()) {
                byte[] data = Files.readAllBytes(new File(filePath).toPath());
                cache.putIfAbsent(String.valueOf(segmentNumber), Arrays.copyOf(data, data.length));
                return ResponseEntity.ok().contentType(MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM_VALUE)).body(data);
            }
        }
        return null;
    }

}
