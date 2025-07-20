package cn.moonlord.ai;

import lombok.SneakyThrows;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync(proxyTargetClass = true)
@EnableScheduling
@SpringBootApplication
public class MoonAssistantApplication {

	@SneakyThrows
	public static void main(String[] args) {
		SpringApplication.run(MoonAssistantApplication.class, args);
	}

}
