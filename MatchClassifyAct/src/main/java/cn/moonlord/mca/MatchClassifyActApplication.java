package cn.moonlord.mca;

import cn.moonlord.mca.capture.WindowResizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@ConfigurationPropertiesScan
@EnableScheduling
@SpringBootApplication
public class MatchClassifyActApplication {

	public static void main(String[] args) {
		// 尽早设为 Per-Monitor DPI 感知：之后窗口 API 坐标均为物理像素，与采集器抓帧尺寸一致
		WindowResizer.ensureProcessDpiAware();
		// 窗口捕获与自动打开网页都需要真实桌面会话，禁用 headless
		System.setProperty("java.awt.headless", "false");
		SpringApplication.run(MatchClassifyActApplication.class, args);
	}

}
