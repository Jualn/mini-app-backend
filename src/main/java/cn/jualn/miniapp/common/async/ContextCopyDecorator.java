package cn.jualn.miniapp.common.async;

import cn.jualn.miniapp.common.constant.UserContext;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;

import java.util.Map;

public class ContextCopyDecorator implements TaskDecorator {

    @NonNull
    @Override
    public Runnable decorate(@NonNull Runnable runnable) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        Long userId = UserContext.getUserId();

        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            Long currentUserId = UserContext.getUserId();

            if (mdcContext != null) MDC.setContextMap(mdcContext); // 把调用者线程的 MDC 设置到异步线程上
            if (userId != null) UserContext.setUserId(userId);

            try {
                runnable.run(); // 执行异步任务
            } finally {
                // 恢复原本线程池线程的MDC/Context，以防污染。也就是回到执行前的状态
                if (previous != null) MDC.setContextMap(previous);
                else MDC.clear();
                if (currentUserId != null) UserContext.setUserId(currentUserId);
                else UserContext.clear();
            }
        };
    }
}
