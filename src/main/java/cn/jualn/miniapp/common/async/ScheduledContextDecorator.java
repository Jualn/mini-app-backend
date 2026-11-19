package cn.jualn.miniapp.common.async;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Each actual scheduled invocation gets a fresh trace, including repeated executions.
 */
public final class ScheduledContextDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(@NonNull Runnable runnable) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            MDC.clear();
            MDC.put(ObservabilityContext.TRACE_ID, ObservabilityContext.newTraceId());
            try {
                runnable.run();
            } finally {
                MDC.clear();
                if (previous != null) MDC.setContextMap(previous);
            }
        };
    }
}
