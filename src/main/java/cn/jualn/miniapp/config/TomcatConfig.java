package cn.jualn.miniapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 只能是开发环境，如果prod启用，会导致开启两个 8080的Tomcat，无法启动
 * 生产环境请勿启用，开发环境启用后会同时监听 http 和 https 端口，方便本地测试，
 * 手机端访问无法通过ssl证书访问 https 端口。
 * 生产环境请务必关闭 http 端口，或使用防火墙等
 */
@Configuration
@Profile("dev")
public class TomcatConfig {

    @Value("${server.port}")
    private int serverPort;

    @Value("${http.port:8080}")
    private int httpPort;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> servletContainer() {
        return factory -> {
            // 添加额外 HTTP connector, 如果服务为 8080 不允许再开启一个8080的Tomcat
            if (httpPort != serverPort) {
                factory.addAdditionalTomcatConnectors(createHttpConnector());
            }
        };
    }

    private org.apache.catalina.connector.Connector createHttpConnector() {
        org.apache.catalina.connector.Connector connector =
                new org.apache.catalina.connector.Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setScheme("http");
        connector.setPort(8080);   // HTTP端口
        connector.setSecure(false);
        return connector;
    }
}
