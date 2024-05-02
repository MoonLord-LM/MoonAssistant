package cn.moonlord.ai;

import lombok.SneakyThrows;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MoonAssistantApplication {

	@SneakyThrows
	public static void main(String[] args) {
		SpringApplication.run(MoonAssistantApplication.class, args);
	}

}
