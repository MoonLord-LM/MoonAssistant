package cn.moonlord.ai.run;

import lombok.SneakyThrows;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartRunner implements ApplicationRunner {

    @SneakyThrows
    @Override
    public void run(ApplicationArguments args) {
        Runtime.getRuntime().exec("explorer.exe http://localhost:8080");
    }

}
