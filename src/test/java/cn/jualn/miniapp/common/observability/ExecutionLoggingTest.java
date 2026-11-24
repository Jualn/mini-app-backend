package cn.jualn.miniapp.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import cn.jualn.miniapp.common.async.ContextCopyDecorator;
import cn.jualn.miniapp.config.AsyncConfig;
import cn.jualn.miniapp.config.SchedulingConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionLoggingTest {
    @Test
    void realSpringAsyncAndSchedulerInstallCorrelateAndCleanReusedThreads() throws Exception {
        List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        };
        capture.setContext(root.getLoggerContext());
        capture.start();
        root.addAppender(capture);
        MDC.put("traceId", "registration-http");
        MDC.put("operationId", "registration-operation");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class, SchedulingConfig.class);
            context.registerBean(Fixture.class);
            context.refresh();
            Fixture fixture = context.getBean(Fixture.class);
            MDC.put("traceId", "request-async-1");
            MDC.put("operationId", "logical-operation-1");
            MDC.put("Authorization", "synthetic-secret");
            fixture.work("propagated");
            MDC.clear();
            fixture.work("independent");
            MDC.put("traceId", "request-async-failed");
            fixture.fail();
            MDC.clear();
            assertTrue(Fixture.tasks.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.stream().noneMatch(e -> e.getLoggerName().equals(AsyncConfig.class.getName()))
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            ILoggingEvent propagated = event(events, "propagated");
            assertEquals("request-async-1", propagated.getMDCPropertyMap().get("traceId"));
            assertEquals("logical-operation-1", propagated.getMDCPropertyMap().get("operationId"));
            assertFalse(propagated.getMDCPropertyMap().containsKey("Authorization"));
            ILoggingEvent independent = event(events, "independent");
            assertTrue(independent.getMDCPropertyMap().get("traceId").matches("[a-f0-9]{32}"));
            assertFalse(independent.getMDCPropertyMap().containsKey("operationId"));
            var failures = events.stream().filter(e -> e.getLoggerName().equals(AsyncConfig.class.getName())).toList();
            assertEquals(1, failures.size());
            assertEquals("request-async-failed", failures.get(0).getMDCPropertyMap().get("traceId"));
            assertNotNull(failures.get(0).getThrowableProxy());
            List<ILoggingEvent> ticks = events.stream().filter(e -> e.getFormattedMessage().equals("scheduled-tick")).toList();
            assertTrue(ticks.size() >= 2);
            assertEquals(ticks.size(), ticks.stream().map(e -> e.getMDCPropertyMap().get("traceId")).distinct().count());
            assertTrue(ticks.stream().allMatch(e -> !e.getMDCPropertyMap().containsKey("operationId")));
            var scheduledFailure = events.stream().filter(e -> e.getLoggerName().equals(SchedulingConfig.class.getName())).findFirst().orElseThrow();
            assertEquals(ticks.get(0).getMDCPropertyMap().get("traceId"), scheduledFailure.getMDCPropertyMap().get("traceId"));
            assertNull(MDC.get("traceId"));
        } finally {
            root.detachAppender(capture);
            capture.stop();
            MDC.clear();
        }
    }

    @Test
    void inlineCallerRunsFailureRestoresEntireCallerScope() {
        MDC.put("traceId", "caller");
        MDC.put("local-only", "preserved");
        Runnable task = new ContextCopyDecorator().decorate(() -> {
            assertNull(MDC.get("local-only"));
            MDC.put("traceId", "changed");
            MDC.put("unexpected", "must-be-cleared");
            throw new IllegalStateException("synthetic");
        });
        try {
            assertThrows(IllegalStateException.class, task::run);
            assertEquals("caller", MDC.get("traceId"));
            assertEquals("preserved", MDC.get("local-only"));
            assertNull(MDC.get("unexpected"));
        } finally { MDC.clear(); }
    }

    private ILoggingEvent event(List<ILoggingEvent> events, String message) {
        return events.stream().filter(e -> e.getFormattedMessage().equals(message)).findFirst().orElseThrow();
    }

    static class Fixture {
        static CountDownLatch tasks;
        private final AtomicInteger ticks = new AtomicInteger();
        private final org.slf4j.Logger log = LoggerFactory.getLogger(Fixture.class);
        Fixture() { tasks = new CountDownLatch(4); }
        @Async public void work(String message) { log.info(message); tasks.countDown(); }
        @Async public void fail() { throw new IllegalStateException("synthetic-secret"); }
        @Scheduled(fixedDelay = 25)
        public void tick() {
            int invocation = ticks.incrementAndGet();
            if (invocation > 2) return;
            log.info("scheduled-tick");
            tasks.countDown();
            if (invocation == 1) throw new IllegalStateException("synthetic-secret");
        }
    }
}
