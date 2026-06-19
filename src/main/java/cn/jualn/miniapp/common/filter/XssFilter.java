package cn.jualn.miniapp.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Order(0)
public class XssFilter extends OncePerRequestFilter {

    private static final List<String> EXCLUDE_PATHS = List.of(
            "/wx/js-sdk-config",
            "/wx/mp/callback",
            "/wx/ma/callback"
    );

    /**
     * 不走 XSS 过滤的接口：
     * 1. multipart 文件上传；
     * 2. JS-SDK 签名接口：url 参数必须保持原样，不能把 & 转成 &amp;；
     * 3. 微信消息回调：body 是微信原始 XML，不能把 <xml> 转成 &lt;xml&gt;。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            return true;
        }

        String uri = request.getRequestURI();

        return EXCLUDE_PATHS.stream().anyMatch(uri::endsWith);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    FilterChain filterChain)
            throws IOException, ServletException {
        filterChain.doFilter(new XssHttpServletRequestWrapper(request), response);
    }
}


