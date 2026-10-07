package cn.jualn.miniapp.common.async;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextCopyDecoratorTest {

    @AfterEach
    void clearContexts() {
        MDC.clear();
        UserContext.clear();
    }

    @Test
    void propagatesOnlyDefinedCorrelationFieldsAndRestoresWorkerScope() {
        MDC.put(ObservabilityContext.TRACE_ID, "source-trace");
        MDC.put(ObservabilityContext.OPERATION_ID, "operation-1");
        MDC.put("unbounded", "must-not-propagate");
        UserContext.setUserId(10L);

        Runnable decorated = new ContextCopyDecorator().decorate(() -> {
            assertEquals("source-trace", MDC.get(ObservabilityContext.TRACE_ID));
            assertEquals("operation-1", MDC.get(ObservabilityContext.OPERATION_ID));
            assertNull(MDC.get("unbounded"));
            assertEquals(10L, UserContext.getUserId());
        });

        MDC.clear();
        MDC.put(ObservabilityContext.TRACE_ID, "worker-trace");
        MDC.put("workerLocal", "preserved");
        UserContext.setUserId(20L);
        decorated.run();

        assertEquals("worker-trace", MDC.get(ObservabilityContext.TRACE_ID));
        assertNull(MDC.get(ObservabilityContext.OPERATION_ID));
        assertEquals("preserved", MDC.get("workerLocal"));
        assertEquals(20L, UserContext.getUserId());
    }
}
