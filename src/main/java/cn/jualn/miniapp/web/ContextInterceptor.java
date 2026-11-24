package cn.jualn.miniapp.web;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.home.controller.HomeController;
import cn.jualn.miniapp.common.web.AdminQueryNoStoreFilter;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.admin.auth.support.AdminBrowserSession;
import cn.jualn.miniapp.module.admin.auth.support.AdminQrLoginRoutes;
import cn.jualn.miniapp.module.admin.operation.controller.AdminAuditHttpAccess;
import cn.jualn.miniapp.module.authorization.web.RbacHttpAccess;
import cn.jualn.miniapp.module.person.PersonHttpAccess;
import cn.jualn.miniapp.module.person.controller.PersonNoStoreFilter;
import cn.jualn.miniapp.module.points.PointsHttpAccess;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.lang.NonNull;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 上下文拦截器
 * <p>
 * 在请求进入业务层之前，将当前登录用户的 userId 写入 {@link UserContext}，
 * 方便在日志、异步任务、数据库审计等场景中直接获取当前用户。
 * 请求结束后务必清理 ThreadLocal，避免线程复用导致上下文串号。
 */
@Component
@RequiredArgsConstructor
public class ContextInterceptor implements AsyncHandlerInterceptor {
    private final AdminTokenService adminTokenService;

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
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (request.getMethod().equals("POST") && (path.equals("/v1/auth/logout")
                || path.equals("/v1/admin/auth/logout") || path.equals(AdminBrowserSession.PATH)))
            return true;
        if (AdminQrLoginRoutes.resource(
                request.getRequestURI().substring(request.getContextPath().length()))) return true;

        if (request.getAttribute(HomeController.AUTHENTICATED_ACTOR) instanceof Long actor) {
            setUserId(actor);
        } else if (AdminAuditHttpAccess.resource(
                request.getRequestURI().substring(
                        request.getContextPath().length()))) {

            setUserId(AdminAuditHttpAccess.authenticate(adminTokenService));
        } else if (AdminQueryNoStoreFilter.resource(request.getRequestURI().substring(
                request.getContextPath().length()))) {

            setUserId(AdminQuery.read(() ->
                    RbacHttpAccess.authenticate(adminTokenService)));
        } else if (PersonNoStoreFilter.resource(request.getRequestURI().substring(
                request.getContextPath().length()))) {

            setUserId(PersonHttpAccess.authenticate(adminTokenService));
        } else if (PointsHttpAccess.resource(request.getRequestURI().substring(
                request.getContextPath().length()))) {

            setUserId(PointsHttpAccess.authenticate(adminTokenService));
        } else if (RbacHttpAccess.resource(request.getRequestURI().substring(
                request.getContextPath().length()))) {

            setUserId(RbacHttpAccess.authenticate(adminTokenService));
        } else if (StpUtil.isLogin()) {

            setUserId(StpUtil.getLoginId());
        } else if (request.getRequestURI().substring(
                request.getContextPath().length()).startsWith("/v1/admin/")
                && AdminStpUtil.STP_LOGIC.isLogin()) {

            adminTokenService.requireAdminAccess();
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
