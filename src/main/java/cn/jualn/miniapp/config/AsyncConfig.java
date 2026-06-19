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
 * 两个线程池职责分离：
 *   asyncExecutor    → @Async 业务异步任务（审核、计数回写等）
 *   consumerExecutor → Redis队列消费线程
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

    /**
     * Redis队列消费线程池
     */
    @Bean("consumerExecutor")
    public Executor consumerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);     // 核心线程数 = CPU核心数
        executor.setMaxPoolSize(4);      // 最大线程数稍高一些
        executor.setQueueCapacity(200);   // 等待队列，防止瞬时任务爆发
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("consumer-");
        executor.setDaemon(false);  // 守护线程 默认false 作用是，等任务执行完jvm才能退出，反之不用等
        executor.setTaskDecorator(new ContextCopyDecorator()); // 装饰器,用于给异步线程添加 traceId
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());// 满了由调用方线程执行，不丢任务
        executor.initialize();
        return executor;
    }

    @Bean("aiTaskExecutor")
    public Executor aiTaskExecutor() {
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

    /**
     * 异步任务未捕获异常处理
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
            log.error("@Async 未捕获异常 method={}", method.getName(), throwable);
    }
}
