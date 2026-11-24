package cn.jualn.miniapp.module.post.controller;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@Component
public class PostNoStoreFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().matches("/v1/(post|comment|post-categories|topics|users/me/posts|users/me/post-comments|users/me/post-favorites|users/me/post-view-history|reports/me|admin/contents|admin/post-categories|admin/topics)(/.*)?");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Vary", "Authorization");
        chain.doFilter(request, response);
    }
}
