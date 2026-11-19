package cn.jualn.miniapp.infrastructure.async.stream;

import cn.jualn.miniapp.infrastructure.async.message.DeadMessageService;
import cn.jualn.miniapp.infrastructure.async.message.EventHandlerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventStreamConsumerLifecycleTest {
    private EventStreamConsumer consumer(Executor executor) {
        return spy(new EventStreamConsumer(mock(RedisTemplate.class), mock(EventStreamTransport.class),
                mock(EventHandlerRegistry.class), mock(DeadMessageService.class), executor, new SimpleMeterRegistry()));
    }

    @Test void stopWaitsForBothConsumersToReleaseTheirRedisOperations() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var consumer = consumer(executor);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new DataAccessResourceFailureException("synthetic unavailable Redis");
        }).when(consumer).ensureGroup();
        try {
            consumer.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            consumer.stop(stopped::countDown);
            assertFalse(consumer.isRunning());
            assertEquals(1, stopped.getCount(), "Redis operations still hold their connections");
            assertThrows(IllegalStateException.class, consumer::start);
            release.countDown();
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown(); consumer.stop(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void stopWakesConsumersWaitingInRedisRecoveryBackoff() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var failures = new CountDownLatch(2);
        var stopped = new CountDownLatch(1);
        var consumer = consumer(executor);
        doAnswer(call -> {
            failures.countDown();
            throw new DataAccessResourceFailureException("synthetic unavailable Redis");
        }).when(consumer).ensureGroup();
        try {
            consumer.start();
            assertTrue(failures.await(5, TimeUnit.SECONDS));
            consumer.stop(stopped::countDown);
            assertTrue(stopped.await(500, TimeUnit.MILLISECONDS), "Stop must wake the one-second backoff");
        } finally {
            consumer.stop(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void destructionStopsWorkersEvenWithoutTheLifecycleStopPhase() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(2);
        var consumer = consumer(executor);
        doAnswer(call -> {
            entered.countDown();
            throw new DataAccessResourceFailureException("synthetic unavailable Redis");
        }).when(consumer).ensureGroup();
        try {
            consumer.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            consumer.destroy();
            assertFalse(consumer.isRunning());
            // A second lifecycle callback must complete synchronously after destroy returned.
            var completed = new CountDownLatch(1);
            consumer.stop(completed::countDown);
            assertEquals(0, completed.getCount());
        } finally {
            consumer.stop(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void queuedConsumersAndRejectedStartupStillReportActualCompletion() {
        var queued = new ArrayList<Runnable>();
        var consumer = consumer(task -> {
            if (!queued.isEmpty()) throw new RejectedExecutionException("synthetic rejection");
            queued.add(task);
        });
        var stopped = new CountDownLatch(1);
        assertThrows(RejectedExecutionException.class, consumer::start);
        consumer.stop(stopped::countDown);
        assertEquals(1, stopped.getCount());
        queued.get(0).run();
        assertEquals(0, stopped.getCount());
        verify(consumer, never()).ensureGroup();
        consumer.stop(stopped::countDown);
    }
}
