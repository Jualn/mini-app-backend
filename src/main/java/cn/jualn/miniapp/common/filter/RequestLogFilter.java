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
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@Order(1)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final List<String> IGNORE_PREFIXES = List.of(
            // 过滤api文档的访问请求，没必要进业务请求日志
            "/v3/api-docs",
            "/doc.html",
            "/swagger",
            "/webjars",
            "/favicon",

            // 常见公网扫描路径，没必要进业务请求日志
            "/.well-known/",
            "/wp-admin",
            "/wp-login.php",
            "/xmlrpc.php",
            "/wordpress",
            "/phpmyadmin",
            "/adminer",
            "/.env",
            "/.git",
            "/vendor",
            "/boaform",
            "/cgi-bin"
    );

    private static final List<String> IGNORE_EQUALS = List.of(
            "/"
    );

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (IGNORE_EQUALS.contains(uri)) {
            return true;
        }

        for (String prefix : IGNORE_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws IOException, ServletException {

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
                    traceId,
                    request.getMethod(),
                    request.getRequestURI(),
                    getClientIp(request));

            // 3. 放行
            filterChain.doFilter(request, response);

        } finally {
            long cost = System.currentTimeMillis() - start;
            int status = response.getStatus();

            String msg = "<<<[Response] [{}] {} {} status={}, cost={}ms";

            if (status >= 500) {
                log.warn(msg,
                        MDC.get("traceId"),
                        request.getMethod(),
                        request.getRequestURI(),
                        status,
                        cost);
            } else {
                log.info(msg,
                        MDC.get("traceId"),
                        request.getMethod(),
                        request.getRequestURI(),
                        status,
                        cost);
            }

            // 4. 清除 MDC（解决线程池复用导致的数据污染）
            MDC.clear();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }

        return request.getRemoteAddr();
    }
}
