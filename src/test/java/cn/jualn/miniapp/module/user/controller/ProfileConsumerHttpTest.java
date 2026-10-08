package cn.jualn.miniapp.module.user.controller;

import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.audit.service.ProfileSafetyCheckService;
import cn.jualn.miniapp.module.user.bo.EffectiveProfileBO;
import cn.jualn.miniapp.module.user.bo.UserProfileUpdateBO;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.service.UserService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Real loopback HTTP and consumer pipeline. Service/media/auth are controlled test doubles;
 * not evidence of database persistence or WeChat/COS acceptance. */
class ProfileConsumerHttpTest {
    @Test void nativeConsumerUsesCanonicalPostAndFinalResponse() throws Exception {
        Path consumer = Path.of("../mini-program/scripts/test-profile-http-flow.mjs").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(consumer), "Run in the coordinated workspace with the mini-program consumer");
        UserService service = mock(UserService.class);
        var state = new AtomicReference<>(EffectiveProfileBO.builder().userId(7L).nickname("同学甲")
                .avatarUrl(null).bio("原简介").platformOperator(false).build());
        when(service.getEffectiveProfile(any())).thenAnswer(invocation -> state.get());
        when(service.updateEffectiveProfile(any())).thenAnswer(invocation -> {
            UserProfileUpdateBO command = invocation.getArgument(0);
            if ("reject".equals(command.getNickname())) throw ProfileSafetyCheckService.rejected();
            if ("offline".equals(command.getNickname())) throw ProfileSafetyCheckService.unavailable();
            var old = state.get();
            var saved = EffectiveProfileBO.builder().userId(old.getUserId())
                    .nickname(command.getNickname() == null ? old.getNickname() : command.getNickname())
                    .bio(command.getBio() == null ? old.getBio() : command.getBio())
                    .avatarUrl(command.getAvatarObjectKey() == null ? old.getAvatarUrl() : "https://example.com/final-avatar")
                    .backgroundUrl(command.getBackgroundObjectKey() == null ? old.getBackgroundUrl() : "https://example.com/final-background")
                    .platformOperator(old.isPlatformOperator()).build();
            state.set(saved);
            return saved;
        });
        var mvc = MockMvcBuilders.standaloneSetup(new UserController(service, Mappers.getMapper(UserConverter.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder()))).build();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/users/me/profile", exchange -> {
            try {
                var builder = request(HttpMethod.valueOf(exchange.getRequestMethod()), URI.create(exchange.getRequestURI().toString()))
                        .contentType("application/json").content(exchange.getRequestBody().readAllBytes());
                var response = mvc.perform(builder).andReturn().getResponse();
                byte[] body = response.getContentAsByteArray();
                exchange.getResponseHeaders().set("Content-Type", response.getContentType());
                exchange.sendResponseHeaders(response.getStatus(), body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception failure) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        Process process = null;
        Path output = Files.createTempFile("profile-consumer-http-", ".log");
        try {
            process = new ProcessBuilder("node", consumer.toString(), "http://127.0.0.1:" + server.getAddress().getPort())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Consumer HTTP test must finish within its budget");
            System.out.print(Files.readString(output));
            assertEquals(0, process.exitValue(), "Consumer pipeline assertions failed");
            verify(service, times(7)).updateEffectiveProfile(any());
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            server.stop(0);
            Files.deleteIfExists(output);
        }
    }
}
