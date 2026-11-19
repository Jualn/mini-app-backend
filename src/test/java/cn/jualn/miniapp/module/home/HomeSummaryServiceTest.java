package cn.jualn.miniapp.module.home;

import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.home.controller.HomeController;
import cn.jualn.miniapp.module.home.service.HomeSummaryQueries;
import cn.jualn.miniapp.module.home.service.HomeSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HomeSummaryServiceTest {
    @Test
    void twoSlowModulesDoNotHideAConfirmedEmptyModuleAndAllUnavailableReturns503() throws Exception {
        var queries = mock(HomeSummaryQueries.class);
        var stopped = new CountDownLatch(2);
        when(queries.banners()).thenReturn(List.of());
        when(queries.latestArticles(any())).thenAnswer(call -> interrupted(stopped));
        when(queries.upcomingActivities(any())).thenAnswer(call -> interrupted(stopped));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(HomeSummaryQueries.class, () -> queries);
            context.registerBean(HomeSummaryService.class);
            context.refresh();
            var json = new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());
            var mvc = MockMvcBuilders.standaloneSetup(new HomeController(null, context.getBean(HomeSummaryService.class)))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();

            mvc.perform(get(HomeController.SUMMARY)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.banners.status").value("EMPTY"))
                    .andExpect(jsonPath("$.banners.items").isArray())
                    .andExpect(jsonPath("$.latestArticles.status").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.latestArticles.items").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.upcomingActivities.status").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.upcomingActivities.items").doesNotHaveJsonPath());
            org.junit.jupiter.api.Assertions.assertTrue(stopped.await(2, TimeUnit.SECONDS));

            when(queries.banners()).thenThrow(IllegalStateException.class);
            doThrow(IllegalStateException.class).when(queries).latestArticles(any());
            doThrow(IllegalStateException.class).when(queries).upcomingActivities(any());
            mvc.perform(get(HomeController.SUMMARY)).andExpect(status().isServiceUnavailable());
        }
    }

    private static List<?> interrupted(CountDownLatch stopped) throws InterruptedException {
        try {
            new CountDownLatch(1).await(10, TimeUnit.SECONDS);
            return List.of();
        } finally {
            stopped.countDown();
        }
    }
}
