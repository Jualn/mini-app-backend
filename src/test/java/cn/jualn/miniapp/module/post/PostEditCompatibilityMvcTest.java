package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.post.bo.*;
import cn.jualn.miniapp.module.post.controller.PostController;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.post.service.*;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC/JSON/converter/error boundary; service dependencies substituted, no database/provider. */
class PostEditCompatibilityMvcTest {
    final PostEditingService editing = mock(PostEditingService.class);
    final GrowthRecordAccess access = mock(GrowthRecordAccess.class);
    final GrowthRecordService growth = mock(GrowthRecordService.class);

    MockMvc mvc(org.springframework.web.servlet.HandlerInterceptor... interceptors) {
        var controller = new PostController(mock(PostService.class), mock(PostCommunityService.class),
                mock(PostQueries.class), mock(TestMediaService.class), Mappers.getMapper(PostConverter.class),
                editing, access, growth);
        return MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptors).setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder()))).build();
    }

    MockHttpServletRequestBuilder edit(boolean nativePost, String body, String etag) {
        var request = request(nativePost ? HttpMethod.POST : HttpMethod.PATCH,
                nativePost ? "/v1/post/123:edit" : "/v1/post/123").contentType("application/json").content(body);
        return etag == null ? request : request.header("If-Match", etag);
    }

    PostVersionedBO<PostEditableBO> value(String type) {
        return new PostVersionedBO<>(new PostEditableBO(123L, type, null, "text", List.of(),
                "MENTOR_QA".equals(type) ? 8L : null, List.of()), "\"new\"");
    }

    @Test void normalAndMentorUseTheSameConverterServicesAndUnwrappedResponse() throws Exception {
        for (String type : List.of("NORMAL", "MENTOR_QA")) {
            when(editing.edit(eq(123L), any(), eq("\"old\""))).thenReturn(value(type));
            for (boolean nativePost : List.of(false, true)) {
                mvc().perform(edit(nativePost, "{\"title\":null,\"topicIds\":[]}", "\"old\""))
                        .andExpect(status().isOk()).andExpect(header().string("ETag", "\"new\""))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Vary", "Authorization"))
                        .andExpect(jsonPath("$.id").value("123")).andExpect(jsonPath("$.postType").value(type))
                        .andExpect(jsonPath("$.code").doesNotExist());
            }
        }
        var command = org.mockito.ArgumentCaptor.forClass(PostEditBO.class);
        verify(editing, times(4)).edit(eq(123L), command.capture(), eq("\"old\""));
        for (var body : command.getAllValues()) {
            assertEquals(Set.of("title", "topicIds"), body.fields());
            assertNull(body.title()); assertEquals(List.of(), body.topicIds());
        }
        verifyNoInteractions(growth);
    }

    @Test void growthUsesTheOriginalPersonAndMediaEditingPipeline() throws Exception {
        when(access.link(123L)).thenReturn(new GrowthRecordBO.Link(123L,"person","branch","GROWTH"));
        var saved = new GrowthRecordBO.Editable(123L,"person","branch","GROWTH",null,"text",List.of(9L),null,List.of());
        when(growth.edit(eq(123L), any(), eq("\"old\""))).thenReturn(new PostVersionedBO<>(saved,"\"new\""));
        for (boolean nativePost : List.of(false,true)) {
            mvc().perform(edit(nativePost,"{\"title\":null,\"mediaIds\":[\"9\"]}","\"old\""))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.postId").value("123"))
                    .andExpect(jsonPath("$.personId").value("person")).andExpect(jsonPath("$.mediaIds[0]").value("9"))
                    .andExpect(header().string("ETag","\"new\"")).andExpect(header().string("Cache-Control","no-store"));
        }
        var command=org.mockito.ArgumentCaptor.forClass(GrowthRecordService.Edit.class);
        verify(growth,times(2)).edit(eq(123L),command.capture(),eq("\"old\""));
        command.getAllValues().forEach(body -> {
            assertEquals(Set.of("title","mediaIds"),body.fields()); assertNull(body.title()); assertEquals(List.of(9L),body.mediaIds());
        });
        verifyNoInteractions(editing);
    }

    @Test void methodsShareOneVersionDomainAndPreserveMissingMalformedAndStaleTags() throws Exception {
        var tag=new AtomicReference<>("\"old\"");
        when(editing.edit(eq(123L),any(),nullable(String.class))).thenAnswer(call -> {
            PostProblems.match(call.getArgument(2),tag.get()); tag.set("\"new\""); return value("NORMAL");
        });
        for (boolean nativePost : List.of(false,true)) {
            mvc().perform(edit(nativePost,"{\"content\":\"draft\"}",null)).andExpect(status().is(428))
                    .andExpect(jsonPath("$.type").value("/problems/revision-required"));
            for (String malformed : List.of("*","W/\"old\"","\"old\",\"other\""))
                mvc().perform(edit(nativePost,"{\"content\":\"draft\"}",malformed)).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors[0].pointer").value("If-Match"));
        }
        mvc().perform(edit(true,"{\"content\":\"draft\"}","\"old\"")).andExpect(status().isOk());
        mvc().perform(edit(false,"{\"content\":\"stale\"}","\"old\"")).andExpect(status().is(412));
        tag.set("\"old\"");
        mvc().perform(edit(false,"{\"content\":\"draft\"}","\"old\"")).andExpect(status().isOk());
        mvc().perform(edit(true,"{\"content\":\"stale\"}","\"old\"")).andExpect(status().is(412));
    }

    @Test void sharedBusinessErrorsAreNotTranslatedToAcceptedOrSuccess() throws Exception {
        for (var problem : List.of(
                new ContractProblemException(HttpStatus.UNAUTHORIZED,"/problems/unauthorized","login"),
                new ContractProblemException(HttpStatus.FORBIDDEN,"/problems/forbidden","owner"),
                new ContractProblemException(HttpStatus.NOT_FOUND,"/problems/not-found","missing"),
                ContractProblemException.conflict("mentor-question-content-locked","protected"),
                new ContractProblemException(HttpStatus.UNPROCESSABLE_ENTITY,"/problems/content-rejected","rejected"),
                new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/qualification-unavailable","unknown"))) {
            when(editing.edit(eq(123L),any(),nullable(String.class))).thenThrow(problem);
            for (boolean nativePost : List.of(false,true))
                mvc().perform(edit(nativePost,"{\"content\":\"draft\"}",null))
                        .andExpect(status().is(problem.getStatus().value()))
                        .andExpect(jsonPath("$.type").value(problem.getType()));
        }
    }

    @Test void growthDenialAndConflictKeepTheOriginalErrorBoundary() throws Exception {
        when(access.link(123L)).thenReturn(new GrowthRecordBO.Link(123L,"person","branch","GROWTH"));
        when(growth.edit(eq(123L),any(),nullable(String.class)))
                .thenThrow(new ContractProblemException(HttpStatus.FORBIDDEN,"/problems/forbidden","binding"));
        for (boolean nativePost : List.of(false,true))
            mvc().perform(edit(nativePost,"{\"content\":\"draft\"}",null)).andExpect(status().isForbidden());
        verifyNoInteractions(editing);
    }

    @Test void noLegacyPostOrMethodOverrideAndNoCrossDomainFieldExpansion() throws Exception {
        for (boolean nativePost : List.of(false,true)) {
            mvc().perform(edit(nativePost,"{\"mediaIds\":[\"9\"]}","\"old\""))
                    .andExpect(status().isBadRequest());
            mvc().perform(edit(nativePost,"{\"authorId\":9}","\"old\""))
                    .andExpect(status().isBadRequest());
            mvc().perform(edit(nativePost,"{\"postType\":\"NORMAL\"}","\"old\""))
                    .andExpect(status().isConflict());
        }
        mvc().perform(post("/v1/post/123").header("X-HTTP-Method-Override","PATCH")
                .contentType("application/json").content("{\"content\":\"draft\"}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(editing,growth);
    }

    @Test void bothMethodsUseTheRealOrdinaryAuthenticationAccountAndSessionInterceptors() throws Exception {
        var prior = cn.dev33.satoken.SaManager.getSaTokenContext();
        var priorMatcher = cn.dev33.satoken.strategy.SaStrategy.instance.routeMatcher;
        cn.dev33.satoken.SaManager.setSaTokenContext(new cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet());
        new cn.dev33.satoken.spring.SaTokenContextRegister();
        try (var token = mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            var users = mock(cn.jualn.miniapp.module.user.service.UserAccountService.class);
            var admin = mock(cn.jualn.miniapp.module.admin.auth.service.AdminTokenService.class);
            var registry = new org.springframework.web.servlet.config.annotation.InterceptorRegistry();
            new cn.jualn.miniapp.config.WebMvcConfig(new cn.jualn.miniapp.web.ContextInterceptor(admin), admin, users,
                    org.mockito.Mockito.mock(cn.jualn.miniapp.module.home.service.HomeSummaryAccess.class))
                    .addInterceptors(registry);
            List<org.springframework.web.servlet.HandlerInterceptor> interceptors =
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
            var http = mvc(interceptors.toArray(new org.springframework.web.servlet.HandlerInterceptor[0]));
            token.when(cn.dev33.satoken.stp.StpUtil::checkLogin).thenThrow(
                    cn.dev33.satoken.exception.NotLoginException.newInstance("login", "-1", "synthetic", null));
            for (boolean nativePost : List.of(false, true))
                http.perform(edit(nativePost, "{\"content\":\"draft\"}", "\"old\""))
                        .andExpect(status().isUnauthorized());
            verifyNoInteractions(editing, growth, access, users);

            token.when(cn.dev33.satoken.stp.StpUtil::checkLogin).thenAnswer(call -> null);
            token.when(cn.dev33.satoken.stp.StpUtil::isLogin).thenReturn(true);
            token.when(cn.dev33.satoken.stp.StpUtil::getLoginId).thenReturn(7L);
            token.when(cn.dev33.satoken.stp.StpUtil::getLoginIdAsLong).thenReturn(7L);
            token.when(() -> cn.dev33.satoken.stp.StpUtil.getExtra(
                    cn.jualn.miniapp.module.user.service.UserAccountService.SESSION_VERSION)).thenReturn("current");
            when(editing.edit(eq(123L), any(), eq("\"old\""))).thenReturn(value("NORMAL"));
            for (boolean nativePost : List.of(false, true))
                http.perform(edit(nativePost, "{\"content\":\"draft\"}", "\"old\""))
                        .andExpect(status().isOk());
            verify(users, times(2)).assertLoginAllowed(7L);
            verify(users, times(2)).requireSessionVersion(7L, "current");

            doThrow(new ContractProblemException(HttpStatus.FORBIDDEN, "/problems/forbidden", "account"))
                    .when(users).assertLoginAllowed(7L);
            for (boolean nativePost : List.of(false, true))
                http.perform(edit(nativePost, "{\"content\":\"draft\"}", "\"old\""))
                        .andExpect(status().isForbidden());
            verify(editing, times(2)).edit(eq(123L), any(), eq("\"old\""));
        } finally {
            cn.dev33.satoken.SaManager.setSaTokenContext(prior);
            cn.dev33.satoken.strategy.SaStrategy.instance.routeMatcher = priorMatcher;
        }
    }
}
