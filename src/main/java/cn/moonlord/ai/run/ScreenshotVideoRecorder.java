package cn.moonlord.ai.run;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
        String command = "ffmpeg.exe -y -f gdigrab -i desktop -s 1280x720 -r 5 -c:v libx264 -hls_list_size 0 -g 10 -f segment -segment_list playlist.m3u8 -segment_time 10 video-%d.ts";
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
                    Thread.sleep(1000);
                    long size = fileCache.values().stream().mapToLong(bytes -> bytes.length).sum() / 1024 / 1024;
                    log.debug("run ffmpeg collect files count: {}, size: {} MB", fileCache.keySet().size(), size);

                    try {
                        File playlist = new File("playlist.m3u8");
                        if (playlist.canRead()) {
                            Long playlistLastModified = playlist.lastModified();
                            if (playlistLastModified > fileCacheTime.getOrDefault("playlist.m3u8", 0L)) {
                                byte[] playlistData = FileUtils.readFileToByteArray(playlist);
                                fileCache.put("playlist.m3u8", playlistData);
                                fileCacheTime.put("playlist.m3u8", playlistLastModified);

                                List<String> tsFileNames = Stream.of(new String(playlistData).split("\n")).filter(line -> !line.isEmpty()).filter(line -> !line.startsWith("#")).toList();
                                for (String tsFileName : tsFileNames) {
                                    File tsFile = new File(tsFileName);
                                    if (tsFile.canRead()) {
                                        Long lastModified = tsFile.lastModified();
                                        if (lastModified > fileCacheTime.getOrDefault(tsFileName, 0L)) {
                                            byte[] data = FileUtils.readFileToByteArray(tsFile);
                                            fileCache.put(tsFileName, data);
                                            fileCacheTime.put(tsFileName, lastModified);
                                            FileUtils.writeByteArrayToFile(new File("video.ts"), data, true);
                                            FileUtils.deleteQuietly(new File(tsFileName));
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
        process.waitFor();
    }

    @SneakyThrows
    @PostConstruct
    @PreDestroy
    public void cleanup() {
        if (process != null) {
            process.getOutputStream().write("q".getBytes());
            process.getOutputStream().flush();
        }
        File[] files = new File("./").listFiles((dir, name) -> name.endsWith(".ts") && !name.equals("video.ts"));
        if (files != null) {
            for (File file : files) {
                FileUtils.deleteQuietly(file);
            }
        }
    }

    @SneakyThrows
    public String getHLSPlaylist() {
        while (fileCache.isEmpty()) {
            Thread.sleep(1000);
        }
        return new String(fileCache.get("playlist.m3u8"));
    }

    @SneakyThrows
    public byte[] getData(Long segmentNumber) {
        String fileName = "video-" + segmentNumber + ".ts";
        while (!fileCache.containsKey(fileName)) {
            Thread.sleep(1000);
        }
        return fileCache.get(fileName);
    }

}
