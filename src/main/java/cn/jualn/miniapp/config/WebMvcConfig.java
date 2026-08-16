package cn.jualn.miniapp.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.interceptor.ContextInterceptor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMvc 配置
 * Sa-Token 拦截器已在 SaTokenConfig 中注册，此处不重复
 * 主要配置：跨域（本地开发调试用，生产通过 Nginx 处理跨域）
 * <p>
 * 注意：生产环境建议关闭此处跨域配置，统一由 Nginx 的 add_header 处理，
 * 避免重复 header 导致浏览器报错
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ContextInterceptor contextInterceptor;

    @Value("${app.cors.allowed-origin-patterns:*}")
    private String[] corsAllowedOriginPatterns;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                new HandlerInterceptor() {
                    private final SaInterceptor delegate =
                            new SaInterceptor(handle -> {
                        // 需要登录的接口
                        SaRouter.match("/v1/**", "/timeline/**")
                                // 白名单：不需要登录
                                .notMatch("/v1/auth")
                                .notMatch("/v1/auth/login")
                                .notMatch("/v1/users/public/**")
                                .notMatch("/v1/interact/like/count")
                                .notMatch("/v1/audit/callback/**") // 微信回调
                                .notMatch("/v1/wx/mp/callback")
                                .notMatch("/v1/wx/ma/callback")

                                // 其余全部校验登录
                                .check(r -> StpUtil.checkLogin());
                    });

                    @Override
                    public boolean preHandle(@NonNull HttpServletRequest req,
                                             @NonNull HttpServletResponse res,
                                             @NonNull Object handler)
                            throws Exception {
                        // async dispatch 是 Spring 内部行为，已经鉴权过了，直接放行
                        if (DispatcherType.ASYNC.equals(req.getDispatcherType())) {
                            return true;
                        }
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
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);                // 预检请求缓存1小时

        registry.addMapping("/third/**")
                .allowedOriginPatterns(corsAllowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);                // 预检请求缓存1小时
    }
}
