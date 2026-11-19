package cn.jualn.miniapp.module.admin.auth;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.web.ContextInterceptor;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.config.*;
import cn.jualn.miniapp.module.admin.auth.store.AdminQrLoginStore;
import cn.jualn.miniapp.module.admin.auth.bo.qrlogin.*;
import cn.jualn.miniapp.module.admin.auth.controller.*;
import cn.jualn.miniapp.module.admin.auth.converter.AdminQrLoginConverter;
import cn.jualn.miniapp.module.admin.auth.service.*;
import cn.jualn.miniapp.module.admin.auth.support.AdminBrowserSession;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status.*;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminQrLoginMvcTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AdminQrLoginService service;
    private AdminTokenService tokens;
    private cn.dev33.satoken.dao.SaTokenDao oldDao;
    private cn.dev33.satoken.context.SaTokenContext oldContext;
    private SaTokenConfig oldConfig;
    private cn.dev33.satoken.stp.StpLogic oldLogic;
    private cn.dev33.satoken.fun.strategy.SaRouteMatchFunction oldMatcher;
    private final String root = "/v1/admin/auth/qr-login-sessions";
    private final String scene = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef";

    @Configuration @EnableWebMvc @Import({WebMvcConfig.class, JacksonConfig.class, GlobalExceptionHandler.class,
            AdminQrLoginController.class, AdminAuthController.class, AdminQrLoginConverter.class, ProtectedController.class})
    static class Config {
        @Bean org.springframework.http.converter.json.Jackson2ObjectMapperBuilder jacksonBuilder() {
            return new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        }
        @Bean WebMvcConfigurer jsonConverters(com.fasterxml.jackson.databind.ObjectMapper mapper) {
            return new WebMvcConfigurer() {
                @Override public void extendMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
                    for (var converter : converters) if (converter instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter json) {
                        json.setObjectMapper(mapper);
                    }
                }
            };
        }
        @Bean AdminQrLoginService qrService() { return mock(AdminQrLoginService.class); }
        @Bean AdminTokenService tokenService() { return mock(AdminTokenService.class); }
        @Bean cn.jualn.miniapp.module.user.service.UserAccountService users(){return mock(cn.jualn.miniapp.module.user.service.UserAccountService.class);}
        @Bean ContextInterceptor context(AdminTokenService tokens) { return new ContextInterceptor(tokens); }
    }
    @RestController static class ProtectedController {
        @GetMapping("/v1/admin/synthetic-protected") public String admin() { return "ok"; }
    }
    @BeforeEach void setup() {
        oldMatcher = cn.dev33.satoken.strategy.SaStrategy.instance.routeMatcher;
        cn.dev33.satoken.strategy.SaStrategy.instance.routeMatcher = (pattern, path) -> new org.springframework.util.AntPathMatcher().match(pattern, path);
        oldLogic = StpUtil.getStpLogic();
        StpUtil.setStpLogic(new cn.dev33.satoken.jwt.StpLogicJwtForSimple());
        oldDao = SaManager.getSaTokenDao(); oldContext = SaManager.getSaTokenContext(); oldConfig = SaManager.getConfig();
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization").setTokenPrefix("Bearer")
                .setJwtSecretKey("synthetic-mvc-admin-qr-test-secret-1234567890"));
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh();
        service = context.getBean(AdminQrLoginService.class); tokens = context.getBean(AdminTokenService.class);
        clearInvocations(tokens);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilter(new AdminQrLoginCacheFilter()).build();
    }
    @AfterEach void teardown() {
        context.close(); StpUtil.setStpLogic(oldLogic); SaManager.setSaTokenDao(oldDao); SaManager.setSaTokenContext(oldContext); SaManager.setConfig(oldConfig);
        cn.dev33.satoken.strategy.SaStrategy.instance.routeMatcher = oldMatcher;
    }
    private AdminQrSessionBO state(cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status status) {
        return new AdminQrSessionBO("synthetic-session", status, 1791367320000L, 1500,
                status == CONFIRMED || status == CONSUMED ? 1791367300000L : null,
                status == CONSUMED ? 1791367301000L : null);
    }

    @Test void legacyRoutesAreAbsentAndSessionManagementRemains() throws Exception {
        mvc.perform(post("/v1/admin/auth/qr-sessions")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/auth/qr-sessions/synthetic")).andExpect(status().isNotFound());
        mvc.perform(delete("/v1/admin/auth/qr-sessions/synthetic")).andExpect(status().isNotFound());
        mvc.perform(post("/v1/admin/auth/qr-confirmations/synthetic")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/auth/me")).andExpect(status().isOk());
        mvc.perform(post("/v1/admin/auth/logout")).andExpect(status().isOk());
        verify(tokens).logoutCurrent();
        verifyNoInteractions(service);
    }

    @Test void webRoutesUseDirectObjectsNumericIntervalIsoTimePngAndBareToken() throws Exception {
        when(service.createSession(any())).thenReturn(new AdminQrCreatedBO(state(PENDING), "synthetic-secret"));
        mvc.perform(post(root)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", root + "/synthetic-session"))
                .andExpect(jsonPath("$.session.pollIntervalMs").isNumber()).andExpect(jsonPath("$.session.expiresAt").isString())
                .andExpect(jsonPath("$.session.confirmedAt").doesNotExist()).andExpect(jsonPath("$.code").doesNotExist());
        when(service.querySession(any(), any(), any())).thenReturn(state(CONFIRMED));
        mvc.perform(get(root + "/synthetic-session").header("X-Admin-Login-Secret", "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedAt").isString()).andExpect(jsonPath("$.token").doesNotExist());
        when(service.readCode(any(), any(), any())).thenReturn(new byte[]{1,2,3});
        mvc.perform(get(root + "/synthetic-session/code").header("X-Admin-Login-Secret", "owner"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(new byte[]{1,2,3}));
        when(service.consumeSession(any(), any(), any(), any())).thenReturn(new AdminQrLoginResultBO("synthetic-token",
                new AdminQrIdentityBO("9", "同学", "同", null, null, null, "ASSIGNMENT"), System.currentTimeMillis() + 60_000));
        mvc.perform(post(root + "/synthetic-session:consume").header("X-Admin-Login-Secret", "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value("synthetic-token")).andExpect(jsonPath("$.profile.id").value("9")).andExpect(jsonPath("$.profile.authorizationModel").value("ASSIGNMENT"))
                .andExpect(jsonPath("$.profile.roleCode").doesNotExist()).andExpect(jsonPath("$.profile.permissions").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(service).consumeSession(any(), any(), any(), eq("RBAC_V1"));
        when(service.cancelSession(any(), any(), any())).thenReturn(state(CANCELLED));
        mvc.perform(post(root + "/synthetic-session:cancel").header("X-Admin-Login-Secret", "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verifyNoInteractions(tokens);
    }

    @Test void sameOriginBrowserCanRestoreAndClearTheExistingSession() throws Exception {
        var result = new AdminQrLoginResultBO("synthetic-token",
                new AdminQrIdentityBO("9", "同学", "同", null, null, null, "ASSIGNMENT"), System.currentTimeMillis() + 60_000);
        var cookie = new Cookie(AdminBrowserSession.COOKIE, result.token());
        when(service.consumeSession(any(), any(), any(), any())).thenReturn(result);
        when(tokens.resumeBrowserSession(result.token())).thenReturn(result);
        mvc.perform(post(root + "/synthetic-session:consume")
                        .header(AdminBrowserSession.HEADER, "1").header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(service);
        mvc.perform(post(root + "/synthetic-session:consume")
                        .header(AdminBrowserSession.HEADER, "1").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString(AdminBrowserSession.COOKIE + "=" + result.token())))
                .andExpect(header().string("Set-Cookie", containsString("Secure; HttpOnly; SameSite=Strict")));
        mvc.perform(post(AdminBrowserSession.PATH).cookie(cookie)
                        .header(AdminBrowserSession.HEADER, "1").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tokens);
        mvc.perform(post(AdminBrowserSession.PATH)
                        .header(AdminBrowserSession.HEADER, "1").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(AdminBrowserSession.PATH).cookie(cookie)
                        .header(AdminBrowserSession.HEADER, "1").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value(result.token()))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/v1/admin/auth/logout").cookie(cookie).header("Authorization", "Bearer other-session")
                        .header(AdminBrowserSession.HEADER, "1").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/v1/admin/auth/logout").cookie(cookie).header("Authorization", "Bearer " + result.token())
                        .header(AdminBrowserSession.HEADER, "1").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test void mobileRequiresOrdinaryLoginAndCannotUseAdminToken() throws Exception {
        String mobile = "/v1/admin/auth/qr-login-scans";
        for (String action : List.of("", ":confirm", ":reject")) {
            mvc.perform(post(mobile + action).contentType(MediaType.APPLICATION_JSON).content("{\"sceneCode\":\"" + scene + "\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
        String admin = AdminStpUtil.STP_LOGIC.createLoginSession(9L);
        mvc.perform(post(mobile).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sceneCode\":\"" + scene + "\"}")).andExpect(status().isUnauthorized());
        String ordinary = StpUtil.getStpLogic().createLoginSession(7L);
        when(service.scanSession(any(), anyLong(), any())).thenReturn(state(SCANNED));
        when(service.confirmSession(any(), anyLong(), any())).thenReturn(state(CONFIRMED));
        when(service.rejectSession(any(), anyLong(), any())).thenReturn(state(REJECTED));
        for (String action : List.of("", ":confirm", ":reject")) {
            mvc.perform(post(mobile + action).header("Authorization", "Bearer " + ordinary).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sceneCode\":\"" + scene + "\"}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.target").value("ADMIN_WEB")).andExpect(jsonPath("$.token").doesNotExist());
        }
        verify(service).confirmSession(eq(scene), eq(7L), any());
    }

    @Test void unknownPropertiesNonStringSceneAndBodiesAreRejected() throws Exception {
        String ordinary = StpUtil.getStpLogic().createLoginSession(7L);
        for (String body : List.of("{\"sceneCode\":\"" + scene + "\",\"userId\":7}",
                "{\"sceneCode\":12345678901234567890123456789012}", "{\"sceneCode\":null}", "{\"sceneCode\":true}", "{}")) {
            mvc.perform(post("/v1/admin/auth/qr-login-scans").header("Authorization", "Bearer " + ordinary)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        for (String path : List.of(root, root + "/synthetic:consume", root + "/synthetic:cancel")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void privateCredentialErrorsAllHaveNoStoreAndRetryAfterIsExposed() throws Exception {
        when(service.querySession(any(), isNull(), any())).thenThrow(AdminQrLoginStore.problem(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid-web-credential"));
        mvc.perform(get(root + "/synthetic").cookie(new jakarta.servlet.http.Cookie("owner", "synthetic")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.type").value("/problems/qr-login-invalid-web-credential"))
                .andExpect(header().string("Cache-Control", "no-store"));
        when(service.createSession(any())).thenThrow(new AdminQrLoginStore.RateLimited(12));
        mvc.perform(post(root).header("Origin", "https://synthetic.example"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "12"))
                .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("Retry-After")))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(options(root).header("Origin", "https://synthetic.example").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Admin-Login-Secret,Authorization")).andExpect(status().isOk());
        when(tokens.requireAdminAccess()).thenThrow(new cn.jualn.miniapp.common.exception.BusinessException(cn.jualn.miniapp.common.result.ResultCode.UNAUTHORIZED));
        mvc.perform(get("/v1/admin/synthetic-protected")).andExpect(status().isUnauthorized());
        verify(tokens).requireAdminAccess();
    }

    @Test void onlyTrustedRestrictedAdminSessionsCarryExitReason() throws Exception {
        var users = mock(cn.jualn.miniapp.module.user.service.UserService.class);
        var accounts = context.getBean(cn.jualn.miniapp.module.user.service.UserAccountService.class);
        var revoked = mock(cn.jualn.miniapp.module.admin.auth.store.AdminAuthStore.class);
        var qr = mock(AdminQrLoginStore.class);
        var authorization = mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class);
        var actual = new AdminTokenService(users, accounts,
                new cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy(), revoked,
                new cn.jualn.miniapp.module.admin.auth.config.AdminAuthProperties(), qr,
                context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class), authorization);
        when(tokens.requireAdminAccess()).thenAnswer(call -> actual.requireAdminAccess());
        String session = "ABCDEFGHIJKLMNOPQRSTUV";
        var logic = AdminStpUtil.STP_LOGIC;
        String token = logic.createTokenValue(9L, "default-device", 300, java.util.Map.of(
                AdminTokenService.QR_MARKER, session,
                cn.jualn.miniapp.module.user.service.UserAccountService.SESSION_VERSION, "1"));
        logic.createLoginSession(9L, new cn.dev33.satoken.stp.parameter.SaLoginParameter()
                .setToken(token).setTimeout(300).setIsWriteHeader(false));
        when(qr.activated(eq(session), anyString(), eq(9L))).thenReturn(true);

        String path = "/v1/admin/synthetic-protected";
        mvc.perform(get(path).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").doesNotExist());
        when(revoked.isTokenRevoked(anyString())).thenReturn(true);
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").doesNotExist());
        verifyNoInteractions(accounts);

        when(revoked.isTokenRevoked(anyString())).thenReturn(false);
        var unauthorized = new cn.jualn.miniapp.common.exception.BusinessException(
                cn.jualn.miniapp.common.result.ResultCode.UNAUTHORIZED);
        doThrow(unauthorized).when(accounts).requireSessionVersion(eq(9L), any());
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").doesNotExist());
        when(accounts.isAdminSessionRestricted(9L)).thenReturn(true);
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.type").value("/problems/unauthorized"))
                .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_RESTRICTED"));

        doNothing().when(accounts).requireSessionVersion(eq(9L), any());
        var profile = new cn.jualn.miniapp.module.user.bo.UserProfileBO();
        profile.setStatus(cn.jualn.miniapp.module.user.enums.UserStatus.NORMAL);
        when(users.getUserProfile(9L)).thenReturn(profile);
        when(authorization.current(9L)).thenReturn(new cn.jualn.miniapp.module.authorization.model.RbacModels.Decision(
                9, cn.jualn.miniapp.module.authorization.model.RbacModels.Source.ASSIGNMENT,
                "v", "c", java.time.LocalDateTime.now(), true, List.of(), List.of()));
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").doesNotExist());

        clearInvocations(accounts);
        SaManager.getSaTokenDao().updateTimeout(logic.splicingKeyTokenValue(token), 1);
        Thread.sleep(1100);
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").doesNotExist());
        verifyNoInteractions(accounts);
    }

    @Test void allExpectedErrorsAndPngFailureUseProblemDetailsAndNoStore() throws Exception {
        for (var http : List.of(org.springframework.http.HttpStatus.FORBIDDEN, org.springframework.http.HttpStatus.NOT_FOUND,
                org.springframework.http.HttpStatus.CONFLICT, org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new cn.jualn.miniapp.common.exception.ContractProblemException(http, "/problems/synthetic", "Synthetic failure"))
                    .when(service).createSession(any());
            mvc.perform(post(root)).andExpect(status().is(http.value())).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        doThrow(new cn.jualn.miniapp.common.exception.SystemException("Synthetic cache failure")).when(service).readCode(any(), any(), any());
        mvc.perform(get(root + "/synthetic/code").header("X-Admin-Login-Secret", "owner"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.type").value("/problems/internal-error"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
