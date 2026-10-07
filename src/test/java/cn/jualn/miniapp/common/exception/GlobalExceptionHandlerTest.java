package cn.jualn.miniapp.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.common.result.ResultCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private ListAppender<ILoggingEvent> logAppender;

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder())))
            .build();

    @AfterEach
    void clearTrace() {
        handlerLogger.detachAppender(logAppender);
        MDC.clear();
    }

    @BeforeEach
    void captureHandlerLogs() {
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @Test
    void validationFailureUsesStructuredProblemDetails() throws Exception {
        mvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/validation-error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].in").value("body"))
                .andExpect(jsonPath("$.errors[0].pointer").value("/title"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$", not(hasKey("code"))));

        assertEquals(0, errorLogCount());
    }

    @Test
    void businessFailureUsesStableTypeAndRealHttpStatus() throws Exception {
        MDC.put("traceId", "trace-123");
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/conflict"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("本场活动名额已满"))
                .andExpect(jsonPath("$.traceId").value("trace-123"));

        assertEquals(0, errorLogCount());
    }

    @Test
    void unexpectedFailureIsSanitized() throws Exception {
        mvc.perform(get("/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("/problems/internal-error"))
                .andExpect(jsonPath("$.detail").value("服务器内部错误"))
                .andExpect(jsonPath("$", not(hasKey("data"))));

        assertEquals(1, errorLogCount());
    }

    @Test
    void externalFailurePreservesCatalogStatusAndDoesNotExposeProviderDetail() throws Exception {
        mvc.perform(get("/test/external-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("/problems/service-unavailable"))
                .andExpect(jsonPath("$.title").value("Service unavailable"))
                .andExpect(jsonPath("$.detail").value("服务号通知模板不可用"))
                .andExpect(jsonPath("$", not(hasKey("provider"))))
                .andExpect(jsonPath("$", not(hasKey("code"))));

        assertEquals(1, errorLogCount());
    }

    @Test
    void everyInternalFailureCodeMapsToNonSuccessProblem() {
        for (ResultCode code : ResultCode.values()) {
            if (code == ResultCode.SUCCESS) {
                continue;
            }
            ApiProblemCatalog.Definition definition = ApiProblemCatalog.forResultCode(code);
            assertNotNull(definition.status());
            assertFalse(definition.status().is2xxSuccessful(), code.name());
            assertNotNull(definition.type());
            assertFalse(definition.type().isBlank(), code.name());
        }
    }

    private long errorLogCount() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .count();
    }

    @RestController
    static class FailureController {
        @PostMapping("/test/validation")
        void validate(@Valid @RequestBody ValidationRequest request) {
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new BusinessException(ResultCode.ACTIVITY_FULL, "本场活动名额已满");
        }

        @GetMapping("/test/failure")
        void failure() {
            throw new IllegalStateException("internal database topology");
        }

        @GetMapping("/test/external-unavailable")
        void externalUnavailable() {
            throw new ExternalServiceException(
                    ResultCode.WX_NOTICE_TEMPLATE_UNAVAILABLE,
                    "wechat",
                    "provider template id missing: secret-internal-detail");
        }
    }

    record ValidationRequest(@NotBlank String title) {
    }
}
