package cn.jualn.miniapp.module.points;

import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.web.ContextInterceptor;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.config.WebMvcConfig;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.points.controller.*;
import cn.jualn.miniapp.module.points.service.*;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Shared interceptor routing only. Session store and owning policy are test doubles. */
class PointsEntryRoutingTest {
    @Test void validAdminSessionWithoutPointsCapabilityIs403WithoutLegacyGrantLoginCheck() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
            var tokens=context.getBean(AdminTokenService.class);
            when(tokens.requireAuthenticatedSession()).thenReturn(9L);
            when(context.getBean(PointsDependencies.class).actor(any())).thenThrow(new BusinessException(ResultCode.FORBIDDEN));
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new PointsNoStoreFilter()).build();
            mvc.perform(get("/v1/admin/points/statistics/persons")).andExpect(status().isForbidden())
                    .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.type").value("/problems/forbidden"));
            verify(tokens,times(2)).requireAuthenticatedSession();verify(tokens,never()).requireAdminAccess();
            verifyNoInteractions(context.getBean(UserAccountService.class));
        }
    }
    @Test void acceptanceSettingsUseAdminSessionAndTheirOwnManagementBoundary() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
            var tokens=context.getBean(AdminTokenService.class);
            when(tokens.requireAuthenticatedSession()).thenReturn(9L);
            when(context.getBean(PersonAuthentication.class).actor()).thenReturn(9L);
            when(context.getBean(PointAcceptanceSettingsService.class).list(9L,1,20))
                    .thenThrow(new BusinessException(ResultCode.FORBIDDEN));
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new PointsNoStoreFilter()).build();
            mvc.perform(get("/v1/admin/points/accepting-organizations")).andExpect(status().isForbidden())
                    .andExpect(header().string("Cache-Control","no-store"));
            verify(tokens,times(2)).requireAuthenticatedSession();verify(tokens,never()).requireAdminAccess();
            verifyNoInteractions(context.getBean(PointsDependencies.class),context.getBean(UserAccountService.class));
        }
    }
    @Configuration @EnableWebMvc
    @Import({WebMvcConfig.class,ContextInterceptor.class,PointsController.class,PointAcceptanceSettingsController.class,PointsProblemAdvice.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal audit() {return mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class);}
        @Bean AdminTokenService tokens() { return mock(AdminTokenService.class); }
        @Bean UserAccountService users() { return mock(UserAccountService.class); }
        @Bean cn.jualn.miniapp.module.points.mapper.PointsR3Mapper queries() {return mock(cn.jualn.miniapp.module.points.mapper.PointsR3Mapper.class);}
        @Bean PointsAcceptancePolicy acceptance() {return mock(PointsAcceptancePolicy.class);}
        @Bean PointsService points() { return mock(PointsService.class); }
        @Bean PointsDependencies dependencies() { return mock(PointsDependencies.class); }
        @Bean PointAcceptanceSettingsService settings() { return mock(PointAcceptanceSettingsService.class); }
        @Bean PersonAuthentication authentication() { return mock(PersonAuthentication.class); }
    }
}
