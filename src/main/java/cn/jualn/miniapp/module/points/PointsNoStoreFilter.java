package cn.jualn.miniapp.module.points;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Also covers authentication/interceptor errors before ControllerAdvice can run. */
@Component
public class PointsNoStoreFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/v1/users/me/points/") && !PointsHttpAccess.resource(path);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");chain.doFilter(request,response);
    }
}
