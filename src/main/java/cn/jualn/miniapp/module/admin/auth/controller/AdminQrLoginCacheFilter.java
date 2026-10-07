package cn.jualn.miniapp.module.admin.auth.controller;

import cn.jualn.miniapp.module.admin.auth.support.AdminQrLoginRoutes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(-1)
public class AdminQrLoginCacheFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String path = request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI) instanceof String original
                ? original : request.getRequestURI();
        if (AdminQrLoginRoutes.resource(path.substring(request.getContextPath().length()))) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
}
