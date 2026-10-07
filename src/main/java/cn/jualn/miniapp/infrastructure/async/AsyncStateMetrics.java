package cn.jualn.miniapp.infrastructure.async;

import cn.jualn.miniapp.infrastructure.async.job.AsyncJobMapper;
import cn.jualn.miniapp.infrastructure.async.outbox.OutboxMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class AsyncStateMetrics {
    private final AsyncJobMapper jobs;
    private final OutboxMapper outbox;
    private final AtomicReference<Double> outboxPending = unknown();
    private final AtomicReference<Double> outboxOldestAge = unknown();
    private final AtomicReference<Double> jobReady = unknown();
    private final AtomicReference<Double> jobRunning = unknown();
    private final AtomicReference<Double> jobRetryWaiting = unknown();
    private final AtomicReference<Double> jobDead = unknown();
    private final AtomicReference<Double> jobOldestOverdueAge = unknown();
    private final AtomicReference<Double> available = unknown();
    private final AtomicBoolean databaseUnavailable = new AtomicBoolean();

    public AsyncStateMetrics(AsyncJobMapper jobs, OutboxMapper outbox, MeterRegistry meters) {
        this.jobs = jobs;
        this.outbox = outbox;
        gauge(meters, AsyncMetricNames.OUTBOX_PENDING, "events", outboxPending);
        gauge(meters, AsyncMetricNames.OUTBOX_OLDEST_PENDING_AGE, "seconds", outboxOldestAge);
        gauge(meters, AsyncMetricNames.JOB_READY, "jobs", jobReady);
        gauge(meters, AsyncMetricNames.JOB_RUNNING, "jobs", jobRunning);
        gauge(meters, AsyncMetricNames.JOB_RETRY_WAITING, "jobs", jobRetryWaiting);
        gauge(meters, AsyncMetricNames.JOB_DEAD_CURRENT, "jobs", jobDead);
        gauge(meters, AsyncMetricNames.JOB_OLDEST_OVERDUE_AGE, "seconds", jobOldestOverdueAge);
        gauge(meters, AsyncMetricNames.DATABASE_STATE_AVAILABLE, "state", available);
    }

    @Scheduled(fixedDelayString = "${async-processing.metrics.refresh-interval:30000}")
    public void refresh() {
        try {
            LocalDateTime now = LocalDateTime.now();
            OutboxStateSnapshot outboxState = outbox.selectStateSnapshot(now);
            JobStateSnapshot jobState = jobs.selectStateSnapshot(now);
            outboxPending.set((double) outboxState.pending());
            outboxOldestAge.set((double) outboxState.oldestPendingAgeSeconds());
            jobReady.set((double) jobState.ready());
            jobRunning.set((double) jobState.running());
            jobRetryWaiting.set((double) jobState.retryWaiting());
            jobDead.set((double) jobState.dead());
            jobOldestOverdueAge.set((double) jobState.oldestOverdueAgeSeconds());
            available.set(1D);
            if (databaseUnavailable.compareAndSet(true, false)) {
                log.info("result=recovered dependency=database operation=async_state_metrics");
            }
        } catch (RuntimeException unavailable) {
            for (AtomicReference<Double> value : stateValues()) value.set(Double.NaN);
            available.set(0D);
            if (databaseUnavailable.compareAndSet(false, true)) {
                log.warn("result=degraded errorCategory=database operation=async_state_metrics exceptionType={}",
                        unavailable.getClass().getSimpleName());
            }
        }
    }

    private List<AtomicReference<Double>> stateValues() {
        return List.of(outboxPending, outboxOldestAge, jobReady, jobRunning,
                jobRetryWaiting, jobDead, jobOldestOverdueAge);
    }

    private static AtomicReference<Double> unknown() {
        return new AtomicReference<>(Double.NaN);
    }

    private static void gauge(MeterRegistry meters, String name, String baseUnit,
                              AtomicReference<Double> value) {
        Gauge.builder(name, value, AtomicReference::get).baseUnit(baseUnit).register(meters);
    }
}
