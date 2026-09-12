package cn.jualn.miniapp.common.exception;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.common.result.ResultCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder())))
            .build();

    @AfterEach
    void clearTrace() {
        MDC.clear();
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
    }

    @Test
    void unexpectedFailureIsSanitized() throws Exception {
        mvc.perform(get("/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("/problems/internal-error"))
                .andExpect(jsonPath("$.detail").value("服务器内部错误"))
                .andExpect(jsonPath("$", not(hasKey("data"))));
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
    }

    record ValidationRequest(@NotBlank String title) {
    }
}
