package cn.jualn.miniapp.module.admin.system.service.impl;

import cn.jualn.miniapp.infrastructure.async.AsyncMetricNames;
import cn.jualn.miniapp.module.admin.system.bo.AdminSystemOverviewBO;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSystemServiceImplTest {

    @Test
    void returnsAvailabilityAndBoundedAsyncStateSnapshot() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        RedisConnection redis = mock(RedisConnection.class);
        when(redisFactory.getConnection()).thenReturn(redis);
        when(redis.ping()).thenReturn("PONG");

        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getLivenessState()).thenReturn(LivenessState.CORRECT);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);

        MockEnvironment environment = new MockEnvironment()
                .withProperty("async-processing.metrics.refresh-interval", "45000");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        gauge(meters, AsyncMetricNames.DATABASE_STATE_AVAILABLE, 1D);
        gauge(meters, AsyncMetricNames.STREAM_AVAILABLE, 1D);
        gauge(meters, AsyncMetricNames.OUTBOX_PENDING, 3D);
        gauge(meters, AsyncMetricNames.OUTBOX_OLDEST_PENDING_AGE, 12D);
        gauge(meters, AsyncMetricNames.JOB_READY, 4D);
        gauge(meters, AsyncMetricNames.JOB_RUNNING, 1D);
        gauge(meters, AsyncMetricNames.JOB_RETRY_WAITING, 2D);
        gauge(meters, AsyncMetricNames.JOB_DEAD_CURRENT, 0D);
        gauge(meters, AsyncMetricNames.JOB_OLDEST_OVERDUE_AGE, 7D);
        gauge(meters, AsyncMetricNames.STREAM_LENGTH, 8D);
        gauge(meters, AsyncMetricNames.STREAM_LAG, 2D);
        gauge(meters, AsyncMetricNames.STREAM_PENDING, 1D);
        gauge(meters, AsyncMetricNames.STREAM_CONSUMERS, 1D);
        gauge(meters, AsyncMetricNames.STREAM_OLDEST_PENDING_AGE, 5D);

        AdminSystemOverviewBO overview = new AdminSystemServiceImpl(
                dataSource, redisFactory, environment, availability, meters,
                new AdminRuntimeMetricsReader(meters)).getOverview();

        assertEquals("UP", overview.getHealth().getLiveness().getStatus());
        assertEquals("UP", overview.getHealth().getReadiness().getStatus());
        assertEquals(45L, overview.getAsyncRuntime().getSampleMaxAgeSeconds());
        assertTrue(overview.getAsyncRuntime().isDatabaseAvailable());
        assertTrue(overview.getAsyncRuntime().isStreamAvailable());
        assertEquals(3L, overview.getAsyncRuntime().getOutbox().getPending());
        assertEquals(2L, overview.getAsyncRuntime().getJobs().getRetryWaiting());
        assertEquals(2L, overview.getAsyncRuntime().getStream().getLag());
        assertEquals("CURRENT_PROCESS", overview.getRuntimeMetrics().getScope());
    }

    @Test
    void keepsUnavailableSamplesDistinctFromZeroAndMarksReadinessDown() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        when(redisFactory.getConnection()).thenThrow(new IllegalStateException("redis unavailable"));

        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getLivenessState()).thenReturn(LivenessState.CORRECT);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        gauge(meters, AsyncMetricNames.DATABASE_STATE_AVAILABLE, 0D);
        gauge(meters, AsyncMetricNames.STREAM_AVAILABLE, 0D);
        gauge(meters, AsyncMetricNames.OUTBOX_PENDING, Double.NaN);

        AdminSystemOverviewBO overview = new AdminSystemServiceImpl(
                dataSource, redisFactory, new MockEnvironment(), availability, meters,
                new AdminRuntimeMetricsReader(meters)).getOverview();

        assertEquals("UP", overview.getHealth().getLiveness().getStatus());
        assertEquals("DOWN", overview.getHealth().getReadiness().getStatus());
        assertFalse(overview.getAsyncRuntime().isDatabaseAvailable());
        assertFalse(overview.getAsyncRuntime().isStreamAvailable());
        assertNull(overview.getAsyncRuntime().getOutbox().getPending());
        assertNull(overview.getAsyncRuntime().getJobs().getReady());
    }

    private static void gauge(SimpleMeterRegistry meters, String name, double value) {
        AtomicReference<Double> sample = new AtomicReference<>(value);
        Gauge.builder(name, sample, AtomicReference::get).register(meters);
    }
}
