package cn.jualn.miniapp.infrastructure.async;

import cn.jualn.miniapp.infrastructure.async.job.AsyncJobMapper;
import cn.jualn.miniapp.infrastructure.async.outbox.OutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncStateMetricsTest {

    @Test
    void refreshPublishesDatabaseTruthWithCanonicalNames() {
        AsyncJobMapper jobs = mock(AsyncJobMapper.class);
        OutboxMapper outbox = mock(OutboxMapper.class);
        when(outbox.selectStateSnapshot(any(LocalDateTime.class))).thenReturn(new OutboxStateSnapshot(3, 41));
        when(jobs.selectStateSnapshot(any(LocalDateTime.class))).thenReturn(new JobStateSnapshot(2, 1, 4, 5, 61));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AsyncStateMetrics metrics = new AsyncStateMetrics(jobs, outbox, registry);

        metrics.refresh();

        assertEquals(3D, registry.get(AsyncMetricNames.OUTBOX_PENDING).gauge().value());
        assertEquals(41D, registry.get(AsyncMetricNames.OUTBOX_OLDEST_PENDING_AGE).gauge().value());
        assertEquals(61D, registry.get(AsyncMetricNames.JOB_OLDEST_OVERDUE_AGE).gauge().value());
        assertEquals(1D, registry.get(AsyncMetricNames.DATABASE_STATE_AVAILABLE).gauge().value());
        assertTrue(registry.getMeters().stream().allMatch(meter -> meter.getId().getName().startsWith("jualn.")));
    }

    @Test
    void refreshFailureMarksTruthGaugesUnknownInsteadOfReportingZero() {
        AsyncJobMapper jobs = mock(AsyncJobMapper.class);
        OutboxMapper outbox = mock(OutboxMapper.class);
        when(outbox.selectStateSnapshot(any(LocalDateTime.class))).thenThrow(new IllegalStateException("db down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AsyncStateMetrics metrics = new AsyncStateMetrics(jobs, outbox, registry);

        metrics.refresh();

        assertTrue(Double.isNaN(registry.get(AsyncMetricNames.OUTBOX_PENDING).gauge().value()));
        assertEquals(0D, registry.get(AsyncMetricNames.DATABASE_STATE_AVAILABLE).gauge().value());
    }
}
