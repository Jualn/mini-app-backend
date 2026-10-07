package cn.jualn.miniapp.common.filter;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@Order(1)
public class RequestLogFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final int MAX_TRACE_ID_LENGTH = 64;
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws IOException, ServletException {
        Map<String, String> previous = ObservabilityContext.capture();
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));

        try {
            ObservabilityContext.clear();
            MDC.put(ObservabilityContext.TRACE_ID, traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            ObservabilityContext.install(previous);
        }
    }

    private String resolveTraceId(String candidate) {
        if (candidate != null
                && candidate.length() <= MAX_TRACE_ID_LENGTH
                && VALID_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return ObservabilityContext.newTraceId();
    }
}
