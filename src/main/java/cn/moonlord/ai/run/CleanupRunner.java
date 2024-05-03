package cn.moonlord.ai.run;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import java.io.File;

@Slf4j
@Component
public class CleanupRunner {

    @SneakyThrows
    @PostConstruct
    @PreDestroy
    public void cleanup() {
        File[] files = new File("./").listFiles((dir, name) -> name.endsWith(".ts"));
        if (files != null) {
            for (File file : files) {
                FileUtils.deleteQuietly(file);
            }
        }
    }

}
