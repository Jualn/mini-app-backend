package cn.jualn.miniapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingLifecycleTest {
    @Test void contextCloseCancelsFutureWorkAndCompletesRunningWork() throws Exception {
        var context = new AnnotationConfigApplicationContext(SchedulingConfig.class);
        var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var closer = Executors.newSingleThreadExecutor();
        var future = scheduler.schedule(() -> fail("Future task ran during shutdown"), Instant.now().plusSeconds(3600));
        var running = scheduler.submit(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); completed.countDown(); }
            catch (InterruptedException interrupted) { throw new AssertionError("Running work was interrupted", interrupted); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closed = closer.submit(context::close);
            release.countDown();
            closed.get(5, TimeUnit.SECONDS);
            running.get(5, TimeUnit.SECONDS);
            assertEquals(0, completed.getCount());
            assertTrue(future.isCancelled());
            assertTrue(scheduler.getScheduledThreadPoolExecutor().isTerminated());
        } finally {
            release.countDown(); context.close(); closer.shutdownNow();
            assertTrue(closer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
