package cn.jualn.miniapp.third.wx.client;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real loopback HTTP, synthetic provider replies, no WeChat account or application Redis. */
class WxMiniProgramCodeTest {
    private HttpServer server;
    private WxClient client;
    private final Queue<Reply> replies = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicInteger posts = new AtomicInteger(), tokens = new AtomicInteger();
    private volatile String sentBody;
    private SimpleMeterRegistry registry;
    private record Reply(int status, String contentType, byte[] body) {}
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Reply reply;
            if (exchange.getRequestURI().getPath().equals("/cgi-bin/token")) {
                tokens.incrementAndGet();
                reply = json(200, "{\"access_token\":\"synthetic-refreshed-token\",\"expires_in\":7200}");
            } else {
                posts.incrementAndGet(); sentBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                reply = replies.poll();
                if (reply == null) reply = json(500, "{}");
            }
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
            exchange.sendResponseHeaders(reply.status(), reply.body().length);
            exchange.getResponseBody().write(reply.body()); exchange.close();
        });
        server.start();
        WebClient web = WebClient.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(2097152))
                .filter((request, next) -> next.exchange(ClientRequest.from(request).url(URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort() + request.url().getRawPath()
                                + (request.url().getRawQuery() == null ? "" : "?" + request.url().getRawQuery()))).build())).build();
        WxProperties props = new WxProperties(null, new WxProperties.Ma("synthetic-ma", "synthetic-secret", null, null));
        RedisService redis = mock(RedisService.class);
        when(redis.getString(RedisKeyConstant.wxAccessToken("ma", "synthetic-ma"))).thenReturn("synthetic-stale-token");
        registry = new SimpleMeterRegistry(); client = new WxClient(web, props, redis, registry);
    }
    @AfterEach void cleanup() { server.stop(0); registry.close(); }
    private Reply json(int status, String json) { return new Reply(status, "application/json", json.getBytes(StandardCharsets.UTF_8)); }
    private byte[] image(String format, int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out); return out.toByteArray();
    }
    private byte[] generate() { return client.generateMiniProgramCode("subpkg_setting/pages/admin-login-confirm/index", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef", "trial", true); }

    @Test void pngAndJpegAreBoundedPngAndUseExactPageSceneEnvironment() throws Exception {
        for (String format : List.of("png", "jpeg")) {
            replies.add(new Reply(200, "image/" + format, image(format, 430, 430)));
            byte[] result = generate();
            assertEquals((byte) 0x89, result[0]); assertEquals(430, ImageIO.read(new java.io.ByteArrayInputStream(result)).getWidth());
            var body = new ObjectMapper().readTree(sentBody);
            assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef", body.get("scene").textValue());
            assertEquals("subpkg_setting/pages/admin-login-confirm/index", body.get("page").textValue());
            assertEquals("trial", body.get("env_version").textValue()); assertTrue(body.get("check_path").booleanValue());
            assertEquals(430, body.get("width").intValue()); assertFalse(body.get("is_hyaline").booleanValue());
        }
        assertEquals(2, posts.get()); assertEquals(0, tokens.get());
    }
    @Test void unpublishedPageCanDisablePathCheckAndRefreshPreservesIt() throws Exception {
        replies.add(json(200, "{\"errcode\":40001}"));
        replies.add(new Reply(200, "image/png", image("png", 430, 430)));
        assertNotNull(client.generateMiniProgramCode("subpkg_setting/pages/admin-login-confirm/index",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef", "develop", false));
        var body = new ObjectMapper().readTree(sentBody);
        assertFalse(body.get("check_path").booleanValue());
        assertEquals("develop", body.get("env_version").textValue());
        assertEquals("subpkg_setting/pages/admin-login-confirm/index", body.get("page").textValue());
        assertEquals(2, posts.get()); assertEquals(1, tokens.get());
    }
    @Test void explicitTokenExpiryRefreshesOnceWithinSameGeneration() throws Exception {
        replies.add(json(200, "{\"errcode\":40001,\"errmsg\":\"synthetic sensitive text\"}"));
        replies.add(new Reply(200, "image/png", image("png", 430, 430)));
        assertNotNull(generate()); assertEquals(2, posts.get()); assertEquals(1, tokens.get());
    }
    @Test void secondTokenExpiryStopsWithoutGeneralPostRetryAndDoesNotLeakProviderText() {
        replies.add(json(200, "{\"errcode\":42001,\"errmsg\":\"synthetic-sensitive-body\"}"));
        replies.add(json(200, "{\"errcode\":42001,\"errmsg\":\"synthetic-sensitive-body\"}"));
        var failure = assertThrows(ExternalServiceException.class, this::generate);
        assertEquals("42001", failure.getProviderCode());
        assertFalse(failure.toString().contains("sensitive-body")); assertNull(failure.getCause());
        assertEquals(2, posts.get()); assertEquals(1, tokens.get());
    }
    @Test void businessErrorLogsProviderCodeWithoutCredentialsOrProviderText() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WxClient.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            replies.add(json(200, "{\"errcode\":45009,\"errmsg\":\"synthetic-sensitive-body\"}"));
            var failure = assertThrows(ExternalServiceException.class, this::generate);
            assertEquals("45009", failure.getProviderCode());
            var events = appender.list.stream().filter(event -> event.getFormattedMessage().contains("operation=miniCode")).toList();
            assertEquals(1, events.size());
            String message = events.get(0).getFormattedMessage();
            assertTrue(message.contains("code=45009"));
            assertFalse(message.contains("synthetic-sensitive-body"));
            assertFalse(message.contains("synthetic-stale-token"));
            assertFalse(message.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef"));
            assertNull(events.get(0).getThrowableProxy());
            assertEquals(1, posts.get());
        } finally {
            logger.detachAppender(appender); appender.stop();
        }
    }
    @Test void httpFailuresBusinessErrorsAndMalformedImagesNeverRetry() throws Exception {
        List<Reply> invalid = List.of(json(400, "{}"), json(500, "{}"), json(200, "{\"errcode\":45009}"),
                new Reply(200, "image/png", new byte[]{1,2,3}), new Reply(200, "image/png", image("png", 2049, 1)),
                new Reply(200, "image/png", new byte[2097153]), new Reply(200, "text/html", image("png", 1, 1)));
        for (Reply reply : invalid) {
            int before = posts.get(); replies.add(reply); assertThrows(ExternalServiceException.class, this::generate);
            assertEquals(before + 1, posts.get());
        }
        assertEquals(0, tokens.get());
    }
    @Test void totalDeadlineCoversTokenAndProviderChainAndDoesNotRetry() {
        WebClient stalled = WebClient.builder().exchangeFunction(request -> reactor.core.publisher.Mono.never()).build();
        WxProperties props = new WxProperties(null, new WxProperties.Ma("synthetic-ma", "synthetic-secret", null, null));
        RedisService redis = mock(RedisService.class);
        when(redis.getString(any())).thenReturn("synthetic-token");
        WxClient stalledClient = new WxClient(stalled, props, redis, registry);
        long started = System.nanoTime();
        assertThrows(ExternalServiceException.class, () -> stalledClient.generateMiniProgramCode("page", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef", "trial", true));
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        assertTrue(elapsed >= 24 && elapsed < 30);
        assertEquals(1, registry.get("jualn.admin.qr.login.wechat.code.duration").tags("result", "failure", "error.category", "timeout").timer().count());
    }
}
