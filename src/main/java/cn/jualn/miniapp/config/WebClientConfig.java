package cn.jualn.miniapp.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import cn.jualn.miniapp.module.documentimport.DocumentImportProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.xml.stream.XMLInputFactory;


/**
 * WebClient 配置
 * 用于调用微信接口、COS 等第三方 HTTP 接口
 * 底层 Reactor Netty 连接池，非阻塞，比 RestTemplate 性能更好
 * 同时配置 XmlMapper，用于解析微信部分 XML 格式响应
 */
@Slf4j
@Configuration
public class WebClientConfig {

    @Bean
    @Primary
    public WebClient webClient(@Qualifier("objectMapper") ObjectMapper objectMapper) {
        return build(objectMapper, Duration.ofSeconds(10));
    }

    @Bean("aiWebClient")
    public WebClient aiWebClient(@Qualifier("objectMapper") ObjectMapper objectMapper,
                                 DocumentImportProperties properties) {
        return build(objectMapper, properties.getAiReadIdleTimeout());
    }

    private WebClient build(ObjectMapper objectMapper, Duration readIdleTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(readIdleTimeout)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readIdleTimeout.toMillis(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(readIdleTimeout.toMillis(), TimeUnit.MILLISECONDS))
                );
        MediaType customJson = MediaType.parseMediaType("application/json;encoding=utf-8");
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                //最大响应体 2MB，微信接口响应一般很小
                .codecs(config -> {
                    config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024);
                    config.defaultCodecs().jackson2JsonEncoder(
                            new Jackson2JsonEncoder(objectMapper));
                    config.defaultCodecs().jackson2JsonDecoder(
                            new Jackson2JsonDecoder(objectMapper, MediaType.TEXT_PLAIN, customJson));
                })
                // 请求/响应日志过滤器（仅 dev 环境生效，生产级别为 WARN 不会打印）
                .filter(logFilter())
                .build();
    }

    /**
     * XmlMapper Bean
     * 用于解析微信 XML 格式响应（如消息推送回调）
     * 与主 ObjectMapper 隔离，避免全局 JSON 配置污染 XML 解析
     */
    @Bean
    public XmlMapper xmlMapper() {
        XmlMapper xmlMapper = new XmlMapper();
        XMLInputFactory inputFactory = xmlMapper.getFactory().getXMLInputFactory();
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // 未知字段不报错，微信 XML 字段可能随版本增加
        xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return xmlMapper;
    }

    private ExchangeFilterFunction logFilter() {
        return (request, next) -> {
            String safeUrl = sanitizeUrl(request.url());
            log.debug("[WebClient] 请求: {} {}", request.method(), safeUrl);

            return next.exchange(request)
                    .doOnNext(response -> log.debug("[WebClient] 响应: {}", response.statusCode()));
        };
    }

    private String sanitizeUrl(URI uri) {
        if (uri == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        if (uri.getScheme() != null) {
            builder.append(uri.getScheme()).append("://");
        }
        if (uri.getAuthority() != null) {
            builder.append(uri.getAuthority());
        }
        builder.append(uri.getPath() == null ? "" : uri.getPath());
        return builder.toString();
    }

}
