package cn.jualn.miniapp.module.admin.operation.controller;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+20)
public class AdminAuditNoStoreFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(AdminAuditHttpAccess.resource(request.getRequestURI().substring(request.getContextPath().length())))response.setHeader("Cache-Control","no-store");
        chain.doFilter(request,response);
    }
}
