package cn.jualn.miniapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 定时任务配置
 * 默认 Spring Scheduling 是单线程，多个定时任务会互相阻塞
 * 配置线程池让多个任务并行执行
 */
@EnableScheduling
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 2核机器，定时任务不多，2个线程够用
        // 当前任务：互动计数回写（可按需扩展）
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("schedule-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        // 定时任务异常不影响其他任务继续执行
        scheduler.setErrorHandler(throwable ->
            org.slf4j.LoggerFactory
                .getLogger(SchedulingConfig.class)
                .error("定时任务异常", throwable)
        );
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setScheduler(taskScheduler());
    }
}
