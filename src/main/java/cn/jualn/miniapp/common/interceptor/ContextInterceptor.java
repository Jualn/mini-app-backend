package cn.jualn.miniapp.common.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.lang.NonNull;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 上下文拦截器
 * <p>
 * 在请求进入业务层之前，将当前登录用户的 userId 写入 {@link UserContext}，
 * 方便在日志、异步任务、数据库审计等场景中直接获取当前用户。
 * 请求结束后务必清理 ThreadLocal，避免线程复用导致上下文串号。
 */
@Component
@lombok.RequiredArgsConstructor
public class ContextInterceptor implements AsyncHandlerInterceptor {
    private final cn.jualn.miniapp.module.admin.auth.service.AdminTokenService adminTokenService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        // 预检没有业务身份；ASYNC / ERROR 不应重复读取 Sa-Token 上下文。
        if (CorsUtils.isPreFlightRequest(request)
                || DispatcherType.ASYNC.equals(request.getDispatcherType())
                || DispatcherType.ERROR.equals(request.getDispatcherType())) {
            return true;
        }
        if (cn.jualn.miniapp.module.admin.auth.support.AdminQrLoginRoutes.resource(
                request.getRequestURI().substring(request.getContextPath().length()))) return true;

        if (StpUtil.isLogin()) {
            setUserId(StpUtil.getLoginId());
        } else if (AdminStpUtil.STP_LOGIC.isLogin()) {
            adminTokenService.requireValidLogin();
            setUserId(AdminStpUtil.STP_LOGIC.getLoginId());
        }
        return true;
    }

    private void setUserId(Object loginId) {
        if (loginId instanceof Number number) {
            UserContext.setUserId(number.longValue());
        } else if (loginId != null) {
            try {
                UserContext.setUserId(Long.parseLong(loginId.toString()));
            } catch (NumberFormatException ignored) {
                // 登录 id 不是数字时，不写入上下文，避免影响正常请求流程
            }
        }
    }

    @Override
    public void afterConcurrentHandlingStarted(@NonNull HttpServletRequest request,
                                               @NonNull HttpServletResponse response,
                                               @NonNull Object handler) {
        // SSE/DeferredResult 类的异步开始时，主线程即将释放
        // 这里做清理，afterCompletion 不会被调用（异步场景）
        UserContext.clear();
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                @Nullable Exception ex) {
        UserContext.clear();
    }
}
