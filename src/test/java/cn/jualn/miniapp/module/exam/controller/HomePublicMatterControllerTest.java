package cn.jualn.miniapp.module.exam.controller;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.exam.bo.HomePublicMatterRemindersBO;
import cn.jualn.miniapp.module.exam.converter.HomePublicMatterReminderConverter;
import cn.jualn.miniapp.module.exam.service.HomePublicMatterReminderService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HomePublicMatterControllerTest {
    private final HomePublicMatterReminderService service = mock(HomePublicMatterReminderService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new HomePublicMatterController(service, new HomePublicMatterReminderConverter()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder())))
            .build();

    @Test
    void returnsCanonicalBodyWithoutLegacyResultEnvelope() throws Exception {
        OffsetDateTime evaluatedAt = OffsetDateTime.parse("2026-09-12T10:00:00+08:00");
        when(service.listForUser(42L)).thenReturn(new HomePublicMatterRemindersBO(
                evaluatedAt,
                HomePublicMatterRemindersBO.Source.SUBSCRIPTIONS,
                List.of(new HomePublicMatterRemindersBO.PublicMatterReminderBO(
                        9007199254740993L,
                        "研究生考试",
                        "报名截止",
                        OffsetDateTime.parse("2026-09-13T18:00:00+08:00")))));

        try (MockedStatic<StpUtil> authentication = mockStatic(StpUtil.class)) {
            authentication.when(StpUtil::getLoginIdAsLong).thenReturn(42L);
            mvc.perform(get("/v1/home/public-matter-reminders"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.evaluatedAt").value("2026-09-12T10:00:00+08:00"))
                    .andExpect(jsonPath("$.source").value("SUBSCRIPTIONS"))
                    .andExpect(jsonPath("$.items[0].publicMatterId").value("9007199254740993"))
                    .andExpect(jsonPath("$.items[0].name").value("研究生考试"))
                    .andExpect(jsonPath("$.items[0].nodeName").value("报名截止"))
                    .andExpect(jsonPath("$", not(hasKey("data"))));
        }
        verify(service).listForUser(42L);
    }

    @Test
    void unauthenticatedResponseUsesProblemDetailsContract() throws Exception {
        MockMvc securedMvc = MockMvcBuilders.standaloneSetup(
                        new HomePublicMatterController(service, new HomePublicMatterReminderConverter()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder())))
                .addInterceptors(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                             jakarta.servlet.http.HttpServletResponse response,
                                             Object handler) {
                        throw mock(NotLoginException.class);
                    }
                })
                .build();

        securedMvc.perform(get("/v1/home/public-matter-reminders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/unauthorized"))
                .andExpect(jsonPath("$.title").value("Authentication required"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.traceId").doesNotExist());
    }
}
