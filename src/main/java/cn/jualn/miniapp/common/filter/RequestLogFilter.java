package cn.jualn.miniapp.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@Order(1)
public class RequestLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws IOException, ServletException {

        String uri = request.getRequestURI();

        // 过滤api文档的访问请求
        if (uri.startsWith("/v3/api-docs")
                || uri.startsWith("/doc.html")
                || uri.startsWith("/swagger")
                || uri.startsWith("/webjars")
                || uri.startsWith("/favicon")) {
            filterChain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();

        try {
            // 1. 获取 traceId （可考虑从请求头透传）
            String traceId = MDC.get("traceId");
            if (traceId == null) {
                traceId = UUID.randomUUID().toString().replace("-", "");
                MDC.put("traceId", traceId);
            }

            // 2. 打印请求入口日志
            log.info(">>>[Request] [{}] {} {} from={}",
                    traceId, request.getMethod(), request.getRequestURI(),request.getRemoteAddr());

            // 3. 放行
            filterChain.doFilter(request, response);

        } finally {
            long cost = System.currentTimeMillis() - start;

            log.info("<<<[Response] [{}] {} {} status={}, cost={}ms",
                    MDC.get("traceId"),
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    cost);

            // 4. 清除 MDC（解决线程池复用导致的数据污染）
            MDC.clear();
        }
    }
}
