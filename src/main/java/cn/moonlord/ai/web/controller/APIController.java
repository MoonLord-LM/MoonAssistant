package cn.moonlord.ai.web.controller;

import cn.moonlord.ai.web.vo.PerformanceVO;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@CrossOrigin(origins = "*")
@RestController
@Slf4j
public class APIController {

    private static final PerformanceVO performance = new PerformanceVO();
    private static final Map<String, Object> cache = new ConcurrentHashMap<>();

    @RequestMapping("/api")
    public String api() {
        return "Hello World";
    }

    @SneakyThrows
    @RequestMapping("/api/performance")
    public PerformanceVO performance() {
        HardwareAbstractionLayer hardware = new SystemInfo().getHardware();
        OperatingSystem operatingSystem = new SystemInfo().getOperatingSystem();

        String computerName = operatingSystem.getNetworkParams().getHostName();
        double cpuLoad = hardware.getProcessor().getSystemCpuLoad(1000);
        double availableMemory = hardware.getMemory().getAvailable();
        double totalMemory = hardware.getMemory().getTotal();
        double availableDisk = operatingSystem.getFileSystem().getFileStores().get(0).getUsableSpace();
        double totalDisk = operatingSystem.getFileSystem().getFileStores().get(0).getTotalSpace();

        Long cpu = Math.round(cpuLoad * 100);
        Long memory = Math.round((totalMemory - availableMemory) / totalMemory * 100);
        Long disk = Math.round((totalDisk - availableDisk) / totalDisk * 100);
        performance.setComputerName(computerName);
        performance.addRecord(cpu, memory, disk);
        return performance;
    }

    @SneakyThrows
    @RequestMapping("/api/screenshot")
    public ResponseEntity<byte[]> screenshot(@RequestParam(value = "scale", required = false) Double scale, @RequestParam(value = "format", required = false) String format) {
        // fix: java.awt.AWTException: headless environment
        System.setProperty("java.awt.headless", "false");

        // realRectangle is the real resolution of display screen, such as 3840 * 2160
        GraphicsDevice[] gs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        DisplayMode mode = gs[gs.length - 1].getDisplayMode();
        Rectangle realRectangle = new Rectangle(mode.getWidth(), mode.getHeight());

        // virtualRectangle is the virtual resolution after Windows system scaling, such as 2560 * 1440（x 150%）
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Rectangle virtualRectangle = new Rectangle(screenSize);
        log.debug("screenshot realRectangle: {}, virtualRectangle: {}", realRectangle, virtualRectangle);

        Robot robot = new Robot();
        List<Image> captures = robot.createMultiResolutionScreenCapture(virtualRectangle).getResolutionVariants();
        BufferedImage screenCapture = (BufferedImage) captures.get(captures.size() - 1);

        // scale
        if (scale != null && scale > 0 && scale < 1) {
            int scaledWidth = (int) (screenCapture.getWidth() * scale);
            int scaledHeight = (int) (screenCapture.getHeight() * scale);
            BufferedImage scaledImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaledImage.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.drawImage(screenCapture, 0, 0, scaledWidth, scaledHeight, null);
            g.dispose();
            screenCapture = scaledImage;
        }

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
