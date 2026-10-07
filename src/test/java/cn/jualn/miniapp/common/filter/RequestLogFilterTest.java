package cn.jualn.miniapp.common.filter;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLogFilterTest {

    private final RequestLogFilter filter = new RequestLogFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void installsValidTraceIdAndRestoresPreviousScope() throws Exception {
        MDC.put(ObservabilityContext.TRACE_ID, "outer-trace");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/activities/42");
        request.addHeader(RequestLogFilter.TRACE_ID_HEADER, "caller_trace-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                observed.set(MDC.get(ObservabilityContext.TRACE_ID)));

        assertEquals("caller_trace-1", observed.get());
        assertEquals("caller_trace-1", response.getHeader(RequestLogFilter.TRACE_ID_HEADER));
        assertEquals("outer-trace", MDC.get(ObservabilityContext.TRACE_ID));
    }

    @Test
    void invalidTraceIdAndRequestIdFallbackAreRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader(RequestLogFilter.TRACE_ID_HEADER, "bad trace\nvalue");
        request.addHeader("X-Request-Id", "legacy-request-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                observed.set(MDC.get(ObservabilityContext.TRACE_ID)));

        assertTrue(observed.get().matches("[a-f0-9]{32}"));
        assertNotEquals("legacy-request-id", observed.get());
        assertEquals(observed.get(), response.getHeader(RequestLogFilter.TRACE_ID_HEADER));
        assertNull(MDC.get(ObservabilityContext.TRACE_ID));
    }

    @Test
    void restoresScopeWhenDownstreamFails() {
        MDC.put(ObservabilityContext.OPERATION_ID, "operation-1");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> {
                    throw new ServletException("failure");
                }));

        assertNull(MDC.get(ObservabilityContext.TRACE_ID));
        assertEquals("operation-1", MDC.get(ObservabilityContext.OPERATION_ID));
    }
}
