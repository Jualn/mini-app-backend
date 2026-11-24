package cn.jualn.miniapp.config;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.home.controller.HomeController;
import cn.jualn.miniapp.module.home.service.HomeSummaryAccess;
import cn.jualn.miniapp.common.web.AdminQueryNoStoreFilter;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.module.authorization.web.RbacHttpAccess;
import cn.jualn.miniapp.module.person.PersonHttpAccess;
import cn.jualn.miniapp.module.person.controller.PersonNoStoreFilter;
import cn.jualn.miniapp.module.points.PointsHttpAccess;
import cn.jualn.miniapp.web.ContextInterceptor;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.admin.auth.support.AdminBrowserSession;
import cn.jualn.miniapp.module.admin.auth.support.AdminQrLoginRoutes;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMvc 配置
 * 注册普通/admin 鉴权、当前账号资格和用户上下文拦截器；
 * SaTokenConfig 提供 JWT Simple 生成逻辑。本类同时维护跨域配置。
 * <p>
 * 注意：生产环境建议关闭此处跨域配置，统一由 Nginx 的 add_header 处理，
 * 避免重复 header 导致浏览器报错
 */
@Configuration
@Import(HomeSummaryAccess.class)
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ContextInterceptor contextInterceptor;
    private final AdminTokenService adminTokenService;
    private final cn.jualn.miniapp.module.user.service.UserAccountService userService;
    private final HomeSummaryAccess homeSummaryAccess;

    @Value("${app.cors.allowed-origin-patterns:*}")
    private String[] corsAllowedOriginPatterns;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                new HandlerInterceptor() {
                    private final SaInterceptor delegate =
                            new SaInterceptor(handle -> {
                                // 管理端使用独立 admin loginType；新扫码入口在下方精确分流。
                                SaRouter.match("/v1/admin/**")
                                        .check(r -> adminTokenService.requireAdminAccess());

                                // 小程序需要登录的接口。
                                SaRouter.match("/v1/**", "/timeline/**")
                                        .notMatch("/v1/admin/**")
                                        // 白名单：不需要登录
                                        .notMatch("/v1/auth")
                                        .notMatch("/v1/auth/login")
                                        .notMatch("/v1/users/public/**")
                                        .notMatch("/v1/interact/like/count")
                                        .notMatch("/v1/audit/callback/**") // 微信回调
                                        .notMatch("/v1/wx/mp/callback")
                                        .notMatch("/v1/wx/ma/callback")

                                        // 其余全部校验登录
                                        .check(r -> {
                                            StpUtil.checkLogin();
                                            userService.assertLoginAllowed(StpUtil.getLoginIdAsLong());
                                            userService.requireSessionVersion(StpUtil.getLoginIdAsLong(),
                                                    StpUtil.getExtra(cn.jualn.miniapp.module.user.service.UserAccountService.SESSION_VERSION));
                                        });
                            });

                    @Override
                    public boolean preHandle(@NonNull HttpServletRequest req,
                                             @NonNull HttpServletResponse res,
                                             @NonNull Object handler)
                            throws Exception {
                        // CORS 预检不携带业务 Token；ASYNC / ERROR 是容器内部二次分发。
                        // 真实业务请求仍会在 REQUEST 分发中执行完整鉴权。
                        if (CorsUtils.isPreFlightRequest(req)
                                || DispatcherType.ASYNC.equals(req.getDispatcherType())
                                || DispatcherType.ERROR.equals(req.getDispatcherType())) {
                            return true;
                        }
                        String path = req.getRequestURI().substring(req.getContextPath().length());
                        if (path.startsWith("/v1/users/me/notification") || path.equals("/v1/admin/notification-channel-status")
                                || path.equals("/v1/admin/notification-delivery-summary"))
                            res.setHeader("Cache-Control", "no-store");
                        if (path.matches("/v1/(admin/)?media/files/[^/]+/content"))
                            res.setHeader("Cache-Control", "no-store");
                        if (path.matches("/v1/media/files/[1-9][0-9]*/content") && (req.getMethod().equals("GET") || req.getMethod().equals("HEAD"))) {
                            res.setHeader("Cache-Control", "no-store");
                            if (req.getHeader("Authorization") != null) {
                                StpUtil.checkLogin();
                                long actor = StpUtil.getLoginIdAsLong();
                                userService.assertLoginAllowed(actor);
                                userService.requireSessionVersion(actor, StpUtil.getExtra(cn.jualn.miniapp.module.user.service.UserAccountService.SESSION_VERSION));
                            }
                            return true;
                        }
                        if (path.startsWith("/v1/auth") || path.startsWith("/v1/admin/auth/"))
                            res.setHeader("Cache-Control", "no-store");
                        if (req.getMethod().equals("POST") && (path.equals("/v1/auth/logout")
                                || path.equals("/v1/admin/auth/logout")
                                || path.equals(AdminBrowserSession.PATH))) {
                            res.setHeader("Cache-Control", "no-store");
                            return true;
                        }
                        if (HomeController.SUMMARY.equals(path)) {
                            long started = System.nanoTime();
                            req.setAttribute(HomeController.REQUEST_STARTED, started);
                            res.setHeader("Cache-Control", "no-store");
                            req.setAttribute(HomeController.AUTHENTICATED_ACTOR,
                                    homeSummaryAccess.requireActor(StpUtil.getTokenValue(), started));
                            return true;
                        }
                        if (HomeController.BANNERS.equals(path) || HomeController.OPTIONS.equals(path)) {
                            res.setHeader("Cache-Control", "no-store");
                            req.setAttribute(HomeController.AUTHENTICATED_ACTOR, RbacHttpAccess.authenticate(adminTokenService));
                            return true;
                        }
                        if (AdminQueryNoStoreFilter.resource(path)) {
                            res.setHeader("Cache-Control", "no-store");
                            AdminQuery.read(() ->
                                    RbacHttpAccess.authenticate(adminTokenService));
                            return true;
                        }
                        if (PersonNoStoreFilter.resource(path)) {
                            res.setHeader("Cache-Control", "no-store");
                            PersonHttpAccess.authenticate(adminTokenService);
                            return true;
                        }
                        if (PointsHttpAccess.resource(path)) {
                            res.setHeader("Cache-Control", "no-store");
                            PointsHttpAccess.authenticate(adminTokenService);
                            return true;
                        }
                        if (RbacHttpAccess.resource(path)) {
                            res.setHeader("Cache-Control", "no-store");
                            RbacHttpAccess.authenticate(adminTokenService);
                            return true;
                        }
                        if (AdminQrLoginRoutes.mobile(path, req.getMethod())) {
                            StpUtil.checkLogin();
                            userService.assertLoginAllowed(StpUtil.getLoginIdAsLong());
                            userService.requireSessionVersion(StpUtil.getLoginIdAsLong(),
                                    StpUtil.getExtra(cn.jualn.miniapp.module.user.service.UserAccountService.SESSION_VERSION));
                            return true;
                        }
                        if (AdminQrLoginRoutes.web(path, req.getMethod())) return true;
                        return delegate.preHandle(req, res, handler);
                    }
                }
        ).addPathPatterns("/**");

        registry.addInterceptor(contextInterceptor)
                .addPathPatterns("/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOriginPatterns(corsAllowedOriginPatterns)
                .allowedMethods("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id", "ETag", "Location", "Content-Disposition", "Retry-After", "Content-Range", "Accept-Ranges")
                .allowCredentials(true)
                .maxAge(3600);                // 预检请求缓存1小时

        registry.addMapping("/third/**")
                .allowedOriginPatterns(corsAllowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id", "ETag", "Location", "Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);                // 预检请求缓存1小时
    }
}
