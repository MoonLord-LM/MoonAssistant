package cn.moonlord.mca.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/**
 * 内嵌 Web 服务器（Tomcat）的编码固化配置。
 *
 * <p>把请求 URI / 查询串统一按 UTF-8 解码：汇总分析产物目录名是中文分类标注
 * （如 {@code summary/主界面-自动出征/}），前端取图时把目录名做「UTF-8 → Base64」
 * 后放在查询串 {@code dir} 里传输。这里显式声明 URI 解码字符集为 UTF-8，
 * 避免个别环境下容器按本机默认字符集（如 GBK）解码，把含中文的 Base64/参数解坏。
 *
 * <p>对应 Spring Boot 内置属性 {@code server.tomcat.uri-encoding}（框架默认即 UTF-8，
 * 此处以代码显式固化，便于统一维护与排障）。</p>
 */
@Configuration
public class WebServerConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> uriEncodingCustomizer() {
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.setURIEncoding(StandardCharsets.UTF_8.name()));
    }

}
