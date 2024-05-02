package cn.moonlord.ai.run;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StartRunner implements ApplicationRunner {

    @SneakyThrows
    @Async
    @Override
    public void run(ApplicationArguments args) {
        Runtime.getRuntime().exec("explorer.exe http://localhost:8080");
    }

}
