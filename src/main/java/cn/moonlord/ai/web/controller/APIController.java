package cn.moonlord.ai.web.controller;

import cn.moonlord.ai.web.vo.PerformanceVO;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@Slf4j
public class APIController {

    private static final PerformanceVO performance = new PerformanceVO();

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
        log.debug("realRectangle: {}, virtualRectangle{}", realRectangle, virtualRectangle);

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
        Long videoTimeSecond = 6L;
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\r\n");
        playlist.append("#EXT-X-VERSION:3\r\n");
        playlist.append("#EXT-X-TARGETDURATION:").append(videoTimeSecond).append("\r\n");
        playlist.append("#EXT-X-MEDIA-SEQUENCE:1\r\n");
        playlist.append("\r\n");
        for (int i = 1; i <= 100; i++) {
            playlist.append("#EXTINF:").append(videoTimeSecond).append(",\r\n");
            playlist.append("segment-").append(i).append(".ts\r\n");
        }
        playlist.append("\r\n");
        playlist.append("#EXT-X-ENDLIST\r\n");
        return playlist.toString();
    }

    @SneakyThrows
    @RequestMapping("/api/screenshot/segment-{segmentNumber}.ts")
    public synchronized ResponseEntity<byte[]> getHLSSegment(@PathVariable("segmentNumber") Long segmentNumber) {
        log.info("getHLSSegment i: {}", segmentNumber);
        Long videoTimeSecond = 6L;
        String filePath = segmentNumber + ".ts";

        if(Path.of(filePath).toFile().exists()) {
            return ResponseEntity.ok().contentType(MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM_VALUE)).body(Files.readAllBytes(Path.of(filePath)));
        }

        String[] command = {
                "ffmpeg.exe",
                "-f", "gdigrab",
                "-framerate", "30",
                "-t", String.valueOf(videoTimeSecond),
                "-video_size", "1920x1080",
                "-i", "desktop",
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                filePath
        };
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        process.getOutputStream().flush();
        process.getOutputStream().close();
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            log.info("FFmpeg command executed successfully.");
        } else {
            log.info("FFmpeg command failed.");
        }
        return ResponseEntity.ok().contentType(MediaType.valueOf(MediaType.APPLICATION_OCTET_STREAM_VALUE)).body(Files.readAllBytes(Path.of(filePath)));
    }

}
