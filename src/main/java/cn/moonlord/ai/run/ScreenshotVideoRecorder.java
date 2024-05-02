package cn.moonlord.ai.run;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedDeque;

@Data
@Slf4j
@Component
public class ScreenshotVideoRecorder {

    private static final ConcurrentLinkedDeque<Properties> videoCache = new ConcurrentLinkedDeque<>();

    @SneakyThrows
    @Async
    @Scheduled(fixedRate = 6 * 1000)
    public void record() {
        String fileName = "video-" + System.currentTimeMillis() + ".ts";
        String command = "ffmpeg.exe -f gdigrab -i desktop -s 1920x1080 -r 5 -t 12.2 -c:v libx264 -c:a aac -pix_fmt yuv420p -y " + fileName;
        log.info("record command: {}", command);

        Process process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", command});
        new Thread(new Runnable() {
            @SneakyThrows
            @Override
            public void run() {
                String input = new String(IOUtils.toCharArray(process.getInputStream(), StandardCharsets.UTF_8));
                log.info("record ffmpeg getInputStream: {}", input);
            }
        }).start();
        new Thread(new Runnable() {
            @SneakyThrows
            @Override
            public void run() {
                String error = new String(IOUtils.toCharArray(process.getErrorStream(), StandardCharsets.UTF_8));
                log.info("record ffmpeg getErrorStream: {}", error);
            }
        }).start();
        int exitCode = process.waitFor();
        process.destroy();
        log.info("record exitCode: {}", exitCode);

        byte[] data = IOUtils.toByteArray(new FileInputStream(fileName));
        FileUtils.delete(new File(fileName));
        log.info("record data: {}", data.length);

        Properties video = new Properties();
        video.put("fileName", fileName);
        video.put("data", data);
        videoCache.add(video);
        if (videoCache.size() > 100) {
            videoCache.removeFirst();
        }
    }

    @SneakyThrows
    public byte[] getData() {
        return (byte[]) videoCache.getLast().get("data");
    }

    @SneakyThrows
    public String getHLSPlaylist() {
        StringBuilder m3u8 = new StringBuilder();
        m3u8.append("#EXTM3U\n");
        m3u8.append("#EXT-X-VERSION:3\n");
        m3u8.append("#EXT-X-TARGETDURATION:6\n");
        m3u8.append("#EXT-X-MEDIA-SEQUENCE:0\n");
        for (int i = 0; i < 1; i++) {
            m3u8.append("#EXTINF:6.000000,\n");
            m3u8.append("video").append(i).append(".ts\n");
        }
        m3u8.append("#EXT-X-ENDLIST");
        return m3u8.toString();
    }

}
