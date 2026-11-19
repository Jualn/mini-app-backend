package cn.jualn.miniapp.common.filter;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final int MAX_TRACE_ID_LENGTH = 64;
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
    private static final String STATE = RequestLogFilter.class.getName() + ".state";
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

    @Value("${app.logging.slow-request-ms:2000}")
    private long slowRequestMs = 2000;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws IOException, ServletException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        RequestState state = (RequestState) request.getAttribute(STATE);
        if (state == null) {
            state = new RequestState(request, response, resolveTraceId(request.getHeader(TRACE_ID_HEADER)));
            request.setAttribute(STATE, state);
        }

        try {
            MDC.clear();
            MDC.put(ObservabilityContext.TRACE_ID, state.traceId);
            response.setHeader(TRACE_ID_HEADER, state.traceId);
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            state.failed = true;
            state.escaped = true;
            log.error("result=failure errorCategory=internal boundary=http-filter exceptionType={}",
                    failure.getClass().getSimpleName(), failure);
            throw failure;
        } finally {
            try {
                if (request.isAsyncStarted()) {
                    state.async = true;
                    if (state.listening.compareAndSet(false, true)) {
                        try {
                            request.getAsyncContext().addListener(state);
                        } catch (IllegalStateException completed) {
                            state.complete();
                        }
                    }
                } else {
                    state.complete();
                }
            } finally {
                MDC.clear();
                if (previous != null) MDC.setContextMap(previous);
            }
        }
    }

    private final class RequestState implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String traceId;
        private final long started = System.nanoTime();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean listening = new AtomicBoolean();
        private volatile boolean failed;
        private volatile boolean escaped;
        private volatile boolean async;

        private RequestState(HttpServletRequest request, HttpServletResponse response, String traceId) {
            this.request = request;
            this.response = response;
            this.traceId = traceId;
        }

        private void complete() {
            if (!completed.compareAndSet(false, true)) return;
            Map<String, String> previous = MDC.getCopyOfContextMap();
            MDC.clear();
            MDC.put(ObservabilityContext.TRACE_ID, traceId);
            try {
                long duration = (System.nanoTime() - started) / 1_000_000;
                // An exception outside MVC can reach the container after this boundary returns.
                // Do not invent its eventual HTTP status, or turn a committed SSE 200 into a 500.
                int status = escaped && !response.isCommitted() ? 0 : response.getStatus();
                Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                // Only framework route templates; never fall back to raw paths, queries or headers.
                String route = template instanceof String path ? path : "unmatched";
                String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
                boolean failure = failed || status >= 500;
                boolean slow = !async && duration >= slowRequestMs;
                var event = failure || slow
                        ? log.atWarn() : log.atDebug();
                event.addKeyValue("method", method).addKeyValue("route", route)
                        .addKeyValue("status", status).addKeyValue("durationMs", duration)
                        .addKeyValue("result", failure ? "failure" : status >= 400 ? "rejected" : "success")
                        .log(slow && !failure ? "HTTP slow request completed" : "HTTP request completed");
            } finally {
                MDC.clear();
                if (previous != null) MDC.setContextMap(previous);
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            failed = true;
        }

        @Override
        public void onError(AsyncEvent event) {
            failed = true;
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
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
