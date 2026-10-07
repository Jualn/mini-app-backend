package cn.jualn.miniapp.module.admin.system.service.impl;

import cn.jualn.miniapp.infrastructure.async.AsyncMetricNames;
import cn.jualn.miniapp.module.admin.system.bo.AdminSystemOverviewBO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminRuntimeMetricsReaderTest {

    @Test
    void aggregatesCurrentProcessHttpResourceAndAsyncMeters() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Timer.builder("http.server.requests")
                .tags("method", "GET", "uri", "/v1/admin/activities", "status", "200")
                .register(meters)
                .record(Duration.ofMillis(20));
        Timer.builder("http.server.requests")
                .tags("method", "GET", "uri", "/v1/admin/activities", "status", "500")
                .register(meters)
                .record(Duration.ofMillis(40));
        Timer.builder("http.server.requests")
                .tags("method", "POST", "uri", "/v1/admin/activities", "status", "201")
                .register(meters)
                .record(Duration.ofMillis(10));

        gauge(meters, "jvm.memory.used", "area", "heap", 128D);
        gauge(meters, "jvm.memory.max", "area", "heap", 512D);
        gauge(meters, "jvm.threads.live", null, null, 24D);
        gauge(meters, "process.cpu.usage", null, null, 0.25D);
        gauge(meters, "system.cpu.usage", null, null, 0.5D);
        gauge(meters, "hikaricp.connections.active", "pool", "primary", 2D);
        gauge(meters, "hikaricp.connections.idle", "pool", "primary", 3D);
        gauge(meters, "hikaricp.connections.pending", "pool", "primary", 1D);
        gauge(meters, "hikaricp.connections.max", "pool", "primary", 5D);
        Timer.builder("jvm.gc.pause").tag("action", "minor").register(meters)
                .record(Duration.ofMillis(7));

        counter(meters, AsyncMetricNames.OUTBOX_PUBLISH, "result", "success", 3D);
        counter(meters, AsyncMetricNames.OUTBOX_PUBLISH, "result", "failure", 1D);
        counter(meters, AsyncMetricNames.OUTBOX_RETRY, null, null, 1D);
        counter(meters, AsyncMetricNames.JOB_EXECUTION, "result", "success", 2D);
        counter(meters, AsyncMetricNames.JOB_RETRY, null, null, 1D);
        Timer.builder(AsyncMetricNames.JOB_DURATION).register(meters).record(Duration.ofMillis(12));
        counter(meters, AsyncMetricNames.STREAM_DELIVERY, "result", "success", 4D);
        counter(meters, AsyncMetricNames.STREAM_DELIVERY, "result", "retry", 1D);
        counter(meters, AsyncMetricNames.STREAM_RECLAIM, null, null, 2D);
        Timer.builder(AsyncMetricNames.STREAM_DURATION).register(meters).record(Duration.ofMillis(9));

        AdminSystemOverviewBO.RuntimeMetricsBO snapshot = new AdminRuntimeMetricsReader(meters).snapshot();

        assertEquals("CURRENT_PROCESS", snapshot.getScope());
        assertNotNull(snapshot.getStartedAt());
        assertTrue(snapshot.getUptimeSeconds() >= 0L);
        assertTrue(snapshot.getHttp().isAvailable());
        assertEquals(3L, snapshot.getHttp().getRequestCount());
        assertEquals(1L, snapshot.getHttp().getServerErrorCount());
        assertEquals(2, snapshot.getHttp().getRoutes().size());
        assertEquals(128L, snapshot.getResources().getHeapUsedBytes());
        assertEquals(2L, snapshot.getResources().getHikariActive());
        assertEquals(1L, snapshot.getResources().getGcPauseCount());
        assertEquals(4L, snapshot.getAsyncEvents().getOutbox().getPublishAttempts());
        assertEquals(3L, snapshot.getAsyncEvents().getOutbox().getSuccessCount());
        assertEquals(2L, snapshot.getAsyncEvents().getJobs().getExecutionCount());
        assertEquals(5L, snapshot.getAsyncEvents().getStream().getDeliveryCount());
        assertEquals(2L, snapshot.getAsyncEvents().getStream().getReclaimCount());
        assertNotNull(snapshot.getAsyncEvents().getJobs().getAverageDurationMs());
    }

    @Test
    void distinguishesUnavailableRuntimeGaugesAndMissingTimersFromZeroCounters() {
        AdminSystemOverviewBO.RuntimeMetricsBO snapshot =
                new AdminRuntimeMetricsReader(new SimpleMeterRegistry()).snapshot();

        assertFalse(snapshot.getHttp().isAvailable());
        assertEquals(0L, snapshot.getHttp().getRequestCount());
        assertTrue(snapshot.getHttp().getRoutes().isEmpty());
        assertNull(snapshot.getResources().getHeapUsedBytes());
        assertNull(snapshot.getResources().getProcessCpuUsage());
        assertEquals(0L, snapshot.getResources().getGcPauseCount());
        assertEquals(0L, snapshot.getAsyncEvents().getJobs().getExecutionCount());
        assertNull(snapshot.getAsyncEvents().getJobs().getAverageDurationMs());
    }

    private static void gauge(SimpleMeterRegistry meters, String name, String tagKey, String tagValue, double value) {
        AtomicReference<Double> sample = new AtomicReference<>(value);
        Gauge.Builder<AtomicReference<Double>> builder = Gauge.builder(name, sample, AtomicReference::get);
        if (tagKey != null) builder.tag(tagKey, tagValue);
        builder.register(meters);
    }

    private static void counter(
            SimpleMeterRegistry meters, String name, String tagKey, String tagValue, double value) {
        Counter.Builder builder = Counter.builder(name);
        if (tagKey != null) builder.tag(tagKey, tagValue);
        builder.register(meters).increment(value);
    }
}
