package cn.moonlord.ai.run;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Slf4j
@Component
public class ScreenshotVideoRecorder implements ApplicationRunner {

    @JsonIgnore
    private final ConcurrentHashMap<String, byte[]> fileCache = new ConcurrentHashMap<>();
    @JsonIgnore
    private final ConcurrentHashMap<String, Long> fileCacheTime = new ConcurrentHashMap<>();
    @JsonIgnore
    private volatile Process process;

    @SneakyThrows
    @Async
    @Override
    public void run(ApplicationArguments args) {
        String command = "ffmpeg.exe -y -f gdigrab -i desktop -s 1280x720 -r 10 -g 1 -c:v libx264 -preset ultrafast -tune zerolatency -hls_list_size 0 -f segment -segment_list playlist.m3u8 -segment_time 0.2 -flush_packets 0 video-%d.ts";
        log.info("run ffmpeg command: {}", command);

        process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", command});
        new Thread(new Runnable() {
            @SneakyThrows
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("run ffmpeg getInputStream: {}", line);
                    }
                }
            }
        }, "run-ffmpeg-getInputStream").start();
        new Thread(new Runnable() {
            @SneakyThrows
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("run ffmpeg getErrorStream: {}", line);
                    }
                }
            }
        }, "run-ffmpeg-getErrorStream").start();
        new Thread(new Runnable() {
            @SneakyThrows
            @Override
            public void run() {
                while (Thread.currentThread().isAlive()) {
                    long sum = fileCache.isEmpty() ? 0 : fileCache.reduceValues(8, bytes -> bytes.length, Integer::sum) / 1024 / 1024;
                    log.info("run ffmpeg collect files count: {}, size: {} MB", fileCache.size(), sum);

                    try {
                        Thread.sleep(10);
                        File playlist = new File("playlist.m3u8");
                        if (playlist.canRead()) {
                            Long playlistLastModified = playlist.lastModified();
                            if (playlistLastModified > fileCacheTime.getOrDefault("playlist.m3u8", 0L)) {
                                byte[] playlistData = FileUtils.readFileToByteArray(playlist);
                                if (playlistData.length > 0) {
                                    fileCache.put("playlist.m3u8", playlistData);
                                    fileCacheTime.put("playlist.m3u8", playlistLastModified);
                                }

                                List<String> tsFileNames = Stream.of(new String(playlistData).split("\n")).filter(line -> !line.isEmpty()).filter(line -> !line.startsWith("#")).toList();
                                for (String tsFileName : tsFileNames) {
                                    File tsFile = new File(tsFileName);
                                    if (tsFile.canRead()) {
                                        Long lastModified = tsFile.lastModified();
                                        if (lastModified > fileCacheTime.getOrDefault(tsFileName, 0L)) {
                                            byte[] data = FileUtils.readFileToByteArray(tsFile);
                                            if (data.length > 0) {
                                                fileCache.put(tsFileName, data);
                                                fileCacheTime.put(tsFileName, lastModified);
                                                FileUtils.writeByteArrayToFile(new File("video.ts"), data, true);
                                                FileUtils.deleteQuietly(new File(tsFileName));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("run ffmpeg collect files error", e);
                    }
                }
            }
        }, "run-ffmpeg-collect-files").start();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            // fix: java.lang.InterruptedException
            log.debug("run ffmpeg exit");
        }
    }

    @SneakyThrows
    @PostConstruct
    @EventListener(ContextClosedEvent.class)
    public void cleanup() {
        log.debug("cleanup begin");
        if (process != null) {
            process.getOutputStream().write("q".getBytes());
            process.getOutputStream().flush();
        }
        Thread.sleep(1000);
        File[] files = new File("./").listFiles((dir, name) -> (name.endsWith(".ts") && !name.equals("video.ts")) || name.equals("playlist.m3u8"));
        if (files != null) {
            for (File file : files) {
                FileUtils.deleteQuietly(file);
            }
        }
        log.debug("cleanup end");
    }

    @SneakyThrows
    public byte[] getHLSPlaylist() {
        while (fileCache.isEmpty()) {
            Thread.sleep(10);
        }
        return fileCache.get("playlist.m3u8");
    }

    @SneakyThrows
    public byte[] getData(Long segmentNumber) {
        String fileName = "video-" + segmentNumber + ".ts";
        while (!fileCache.containsKey(fileName)) {
            Thread.sleep(10);
        }
        return fileCache.get(fileName);
    }

}
