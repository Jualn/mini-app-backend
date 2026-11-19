package cn.jualn.miniapp.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminQueryNoStoreFilter extends OncePerRequestFilter {
    public static boolean resource(String path) {
        return Set.of("/v1/admin/attachments/batch", "/v1/admin/users/names", "/v1/admin/organizations/names",
                "/v1/admin/dashboard/summary", "/v1/admin/authorization/me/duty-summary").contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (resource(request.getRequestURI().substring(request.getContextPath().length()))
                || request.getRequestURI().substring(request.getContextPath().length()).matches("/v1/admin/activities/[^/]+/registrations/[^/]+:invalidate")) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
