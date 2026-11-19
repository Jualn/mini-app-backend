package cn.jualn.miniapp.module.audit.controller.admin;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ContentSafetyNoStoreFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/v1/admin/reviews") || path.startsWith("/v1/admin/reviews/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Vary", "Authorization");
        }
        chain.doFilter(request, response);
    }
}
