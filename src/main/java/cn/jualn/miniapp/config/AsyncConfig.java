package cn.jualn.miniapp.config;

import cn.jualn.miniapp.common.async.ContextCopyDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置
 * 各线程池按职责隔离：普通 {@code @Async}、持久化 Job Worker、Redis Stream Consumer。
 */
@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 业务异步线程池（@Async 默认使用）
     * 2核2G：核心2，最大4，队列200
     */
    @Bean("asyncExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(new ContextCopyDecorator());
        // 队列满了由调用方线程执行，不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean("aiTaskExecutor")
    public ThreadPoolTaskExecutor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);     // AI任务可能比较重，适当增加线程数
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(100);   // AI任务相对较少，队列容量可以适当小一些
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("ai-task-");
        executor.setDaemon(false);
        executor.setTaskDecorator(new ContextCopyDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("jobExecutor")
    public Executor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("async-job-");
        executor.setTaskDecorator(new ContextCopyDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean("eventConsumerExecutor")
    public Executor eventConsumerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("event-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * 异步任务未捕获异常处理
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
            log.error("@Async 未捕获异常 method={}", method.getName(), throwable);
    }
}
