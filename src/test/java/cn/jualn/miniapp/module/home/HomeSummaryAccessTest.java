package cn.jualn.miniapp.module.home;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.config.WebMvcConfig;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.home.controller.HomeController;
import cn.jualn.miniapp.module.home.service.HomeSummaryAccess;
import cn.jualn.miniapp.module.home.service.HomeSummaryQueries;
import cn.jualn.miniapp.module.home.service.HomeSummaryService;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.web.ContextInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HomeSummaryAccessTest {
    @Test
    void authenticatesBeforeQueriesAndReturnsGlobalFailures() throws Exception {
        var accounts = mock(UserAccountService.class);
        var queries = mock(HomeSummaryQueries.class);
        var original = StpUtil.getStpLogic();
        var logic = mock(StpLogic.class);
        StpUtil.setStpLogic(logic);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(UserAccountService.class, () -> accounts);
            context.registerBean(HomeSummaryAccess.class);
            context.registerBean(HomeSummaryQueries.class, () -> queries);
            context.registerBean(HomeSummaryService.class);
            context.refresh();
            var admin = mock(AdminTokenService.class);
            var registry = new InterceptorRegistry();
            new WebMvcConfig(new ContextInterceptor(admin), admin, accounts, context.getBean(HomeSummaryAccess.class))
                    .addInterceptors(registry);
            List<HandlerInterceptor> interceptors = ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
            var mvc = MockMvcBuilders.standaloneSetup(new HomeController(null, context.getBean(HomeSummaryService.class)))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .addInterceptors(interceptors.toArray(HandlerInterceptor[]::new)).build();

            mvc.perform(get(HomeController.SUMMARY)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"));
            when(logic.getTokenValue()).thenReturn("unmapped");
            mvc.perform(get(HomeController.SUMMARY)).andExpect(status().isUnauthorized());

            when(logic.getTokenValue()).thenReturn("current");
            when(logic.getLoginIdByToken("current")).thenReturn("7");
            when(logic.getExtra("current", UserAccountService.SESSION_VERSION)).thenReturn("2");
            doThrow(new BusinessException(ResultCode.USER_BANNED)).when(accounts).assertLoginAllowed(7L);
            mvc.perform(get(HomeController.SUMMARY)).andExpect(status().isForbidden());
            doNothing().when(accounts).assertLoginAllowed(7L);
            doThrow(new BusinessException(ResultCode.UNAUTHORIZED)).when(accounts).requireSessionVersion(7L, "2");
            mvc.perform(get(HomeController.SUMMARY)).andExpect(status().isUnauthorized());
            doNothing().when(accounts).requireSessionVersion(7L, "2");

            var stopped = new CountDownLatch(1);
            when(logic.getLoginIdByToken("current")).thenAnswer(call -> {
                try {
                    new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                    return "7";
                } finally {
                    stopped.countDown();
                }
            });
            mvc.perform(get(HomeController.SUMMARY)).andExpect(status().isServiceUnavailable());
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
            verifyNoInteractions(queries);

            doReturn("7").when(logic).getLoginIdByToken("current");
            when(queries.banners()).thenAnswer(call -> {
                org.junit.jupiter.api.Assertions.assertEquals(7L, cn.jualn.miniapp.common.constant.UserContext.getUserId());
                return List.of();
            });
            when(queries.latestArticles(any())).thenReturn(List.of());
            when(queries.upcomingActivities(any())).thenReturn(List.of());
            mvc.perform(get(HomeController.SUMMARY)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.banners.status").value("EMPTY"));
        } finally {
            StpUtil.setStpLogic(original);
        }
    }
}
