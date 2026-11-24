package cn.jualn.miniapp.common.async;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;

import java.util.Map;

public class ContextCopyDecorator implements TaskDecorator {

    @NonNull
    @Override
    public Runnable decorate(@NonNull Runnable runnable) {
        Map<String, String> observabilityContext = ObservabilityContext.capture();
        Long userId = UserContext.getUserId();

        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            Long currentUserId = UserContext.getUserId();

            MDC.clear();
            ObservabilityContext.install(observabilityContext);
            if (MDC.get(ObservabilityContext.TRACE_ID) == null) {
                MDC.put(ObservabilityContext.TRACE_ID, ObservabilityContext.newTraceId());
            }
            if (userId != null) UserContext.setUserId(userId);
            else UserContext.clear();

            try {
                runnable.run(); // 执行异步任务
            } finally {
                // 恢复原本线程池线程的MDC/Context，以防污染。也就是回到执行前的状态
                MDC.clear();
                if (previous != null) MDC.setContextMap(previous);
                if (currentUserId != null) UserContext.setUserId(currentUserId);
                else UserContext.clear();
            }
        };
    }
}
