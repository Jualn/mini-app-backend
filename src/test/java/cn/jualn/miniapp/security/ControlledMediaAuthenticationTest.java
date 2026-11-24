package cn.jualn.miniapp.security;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.config.WebMvcConfig;
import cn.jualn.miniapp.web.ContextInterceptor;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.module.media.service.MediaContentService;
import cn.jualn.miniapp.module.media.bo.MediaContentBO;
import cn.jualn.miniapp.module.media.controller.MediaContentController;
import cn.jualn.miniapp.third.cos.client.CosClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.HandlerInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlledMediaAuthenticationTest {
    @Test void publicImageNeedsNoSessionButCookieContextDoesNotAuthorizePrivatePreview() throws Exception {
        try(var sa=mockStatic(StpUtil.class)) {
            sa.when(StpUtil::isLogin).thenReturn(true);sa.when(StpUtil::getLoginId).thenReturn(7L);
            var user=mock(UserAccountService.class);var media=mock(MediaContentService.class);when(media.require(1,null,false)).thenReturn(new MediaContentBO("post/retained/7/a","image/png",3));
            assertEquals(200,mvc(user,media).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head("/v1/media/files/1/content")).andReturn().getResponse().getStatus());
            verify(media).require(1,null,false);verifyNoInteractions(user);
        }
    }
    @Test void explicitInvalidBearerIsNotDowngradedToAnonymous() throws Exception {
        try(var sa=mockStatic(StpUtil.class)) {
            sa.when(StpUtil::checkLogin).thenThrow(cn.dev33.satoken.exception.NotLoginException.newInstance("login","-1","synthetic",null));
            var user=mock(UserAccountService.class);var media=mock(MediaContentService.class);
            var result=mvc(user,media).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head("/v1/media/files/1/content").header("Authorization","Bearer invalid")).andReturn().getResponse();
            assertEquals(401,result.getStatus());verifyNoInteractions(media);
        }
    }
    @Test void authorPreviewRequiresCurrentAccountAndSessionVersionBeforeOwnerRead() throws Exception {
        try(var sa=mockStatic(StpUtil.class)) {
            sa.when(StpUtil::getLoginIdAsLong).thenReturn(7L);sa.when(StpUtil::isLogin).thenReturn(true);sa.when(StpUtil::getLoginId).thenReturn(7L);sa.when(()->StpUtil.getExtra(UserAccountService.SESSION_VERSION)).thenReturn("current");
            var user=mock(UserAccountService.class);var media=mock(MediaContentService.class);when(media.require(1,7L,false)).thenReturn(new MediaContentBO("post/retained/7/a","image/png",3));
            assertEquals(200,mvc(user,media).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head("/v1/media/files/1/content").header("Authorization","Bearer valid")).andReturn().getResponse().getStatus());
            verify(user).assertLoginAllowed(7L);verify(user).requireSessionVersion(7L,"current");verify(media).require(1,7L,false);
        }
    }
    @Test void bearerHeadAndRangePreflightAreAllowedWithoutAuthenticatingOptions() throws Exception {
        var admin=mock(AdminTokenService.class);var users=mock(UserAccountService.class);var config=new WebMvcConfig(new ContextInterceptor(admin),admin,users,null);
        org.springframework.test.util.ReflectionTestUtils.setField(config,"corsAllowedOriginPatterns",new String[]{"https://client.example"});
        var registry=new org.springframework.web.servlet.config.annotation.CorsRegistry();config.addCorsMappings(registry);
        java.util.Map<String,org.springframework.web.cors.CorsConfiguration> mappings=org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry,"getCorsConfigurations");
        var source=new org.springframework.web.cors.UrlBasedCorsConfigurationSource();mappings.forEach(source::registerCorsConfiguration);
        var media=mock(MediaContentService.class);var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new MediaContentController(media,mock(CosClient.class))).addFilters(new org.springframework.web.filter.CorsFilter(source)).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/v1/media/files/1/content").header("Origin","https://client.example").header("Access-Control-Request-Method","HEAD").header("Access-Control-Request-Headers","Authorization,Range")).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertEquals("https://client.example",response.getHeader("Access-Control-Allow-Origin"));assertTrue(response.getHeader("Access-Control-Allow-Methods").contains("HEAD"));verifyNoInteractions(media,users);
    }
    @Test void adminMediaAuthenticationFailureAlreadyCarriesNoStore() throws Exception {
        var registry=new InterceptorRegistry();new WebMvcConfig(new ContextInterceptor(mock(AdminTokenService.class)),mock(AdminTokenService.class),mock(UserAccountService.class),null).addInterceptors(registry);
        java.util.List<HandlerInterceptor> interceptors=org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry,"getInterceptors");
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        try {interceptors.get(0).preHandle(new org.springframework.mock.web.MockHttpServletRequest("GET","/v1/admin/media/files/1/content"),response,new Object());} catch(RuntimeException missingAuthentication) { /* No application/Sa session in this fixture. */ }
        assertEquals("no-store",response.getHeader("Cache-Control"));
    }
    org.springframework.test.web.servlet.MockMvc mvc(UserAccountService users,MediaContentService media) {
        var admin=mock(AdminTokenService.class);var context=new ContextInterceptor(admin);var registry=new InterceptorRegistry();new WebMvcConfig(context,admin,users,null).addInterceptors(registry);
        java.util.List<HandlerInterceptor> interceptors=org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry,"getInterceptors");
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new MediaContentController(media,mock(CosClient.class))).setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()).addInterceptors(interceptors.toArray(new HandlerInterceptor[0])).build();
    }
}
