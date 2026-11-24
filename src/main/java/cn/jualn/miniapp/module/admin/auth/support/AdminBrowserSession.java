package cn.jualn.miniapp.module.admin.auth.support;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/** Cookie transport for the same-origin Admin Web session recovery endpoint. */
public final class AdminBrowserSession {
    public static final String HEADER = "X-Admin-Browser-Session";
    public static final String COOKIE = "__Host-jualn-admin-session";
    public static final String ROUTE = "/browser-session";
    public static final String PATH = "/v1/admin/auth" + ROUTE;

    private AdminBrowserSession() {}

    public static boolean sameOriginRequest(HttpServletRequest request) {
        return "1".equals(request.getHeader(HEADER))
                && "same-origin".equals(request.getHeader("Sec-Fetch-Site"));
    }

    public static void requireSameOrigin(HttpServletRequest request) {
        if (!sameOriginRequest(request))
            throw new BusinessException(ResultCode.FORBIDDEN, "浏览器会话仅支持同源请求");
    }

    public static ResponseCookie cookie(String token, long remainingSeconds) {
        return ResponseCookie.from(COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofSeconds(Math.max(0, remainingSeconds)))
                .build();
    }
}
