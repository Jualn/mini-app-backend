package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;

import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.web.ContextInterceptor;
import cn.jualn.miniapp.config.WebMvcConfig;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.person.controller.*;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Shared entry routing only; real Sa/Redis and current capability proofs require runtime acceptance. */
class PersonEntryRoutingTest {
    @Test void missingCapabilityIs403WithoutLegacyAdminLoginOrOrdinaryUserFallback() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
            var tokens=context.getBean(AdminTokenService.class);when(tokens.requireAuthenticatedSession()).thenReturn(9L);
            when(context.getBean(PersonAuthentication.class).actor()).thenReturn(9L);
            when(context.getBean(PersonFoundationService.class).stages(9)).thenThrow(PersonProblems.denied());
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new PersonNoStoreFilter()).build();
            mvc.perform(get("/v1/admin/identity-stages")).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.type").value("/problems/forbidden"));
            verify(tokens,times(2)).requireAuthenticatedSession();verify(tokens,never()).requireAdminAccess();verifyNoInteractions(context.getBean(UserAccountService.class));
        }
    }
    @Configuration @EnableWebMvc
    @Import({WebMvcConfig.class,ContextInterceptor.class,PersonController.class,PersonProblemAdvice.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal audit() {return mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class);}
        @Bean AdminTokenService tokens() { return mock(AdminTokenService.class); }
        @Bean UserAccountService users() { return mock(UserAccountService.class); }
        @Bean PersonFoundationService people() { return mock(PersonFoundationService.class); }
        @Bean PersonAuthentication authentication() { return mock(PersonAuthentication.class); }
    }
}
