package cn.jualn.miniapp.infrastructure.async.job;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.AsyncMetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class JobWorker {
    private static final int CLAIM_BATCH = 2;
    private final JobClaimService claimService;
    private final AsyncJobMapper mapper;
    private final JobHandlerRegistry handlers;
    private final JobFailureClassifier classifier;
    private final RetrySchedule retrySchedule;
    private final Executor executor;
    private final MeterRegistry meters;
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    public JobWorker(JobClaimService claimService, AsyncJobMapper mapper, JobHandlerRegistry handlers,
                     JobFailureClassifier classifier, RetrySchedule retrySchedule,
                     @Qualifier("jobExecutor") Executor executor, MeterRegistry meters) {
        this.claimService = claimService;
        this.mapper = mapper;
        this.handlers = handlers;
        this.classifier = classifier;
        this.retrySchedule = retrySchedule;
        this.executor = executor;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${async-processing.job.poll-interval:1000}")
    public void poll() {
        String owner = instanceId + "-" + UUID.randomUUID().toString().substring(0, 8);
        List<AsyncJob> jobs = claimService.claimDue(owner, CLAIM_BATCH);
        for (AsyncJob job : jobs) {
            try {
                executor.execute(() -> execute(job));
            } catch (RejectedExecutionException rejected) {
                String jobType = handlers.metricType(job.getJobType(), job.getSchemaVersion());
                String result = mapper.markRetry(job.getId(), owner, LocalDateTime.now().plusSeconds(10),
                        "internal", "executor_saturated") == 1 ? "rejected" : "stale_owner";
                meters.counter(AsyncMetricNames.JOB_EXECUTION, "job.type", jobType,
                        "result", result, "error.category", "internal").increment();
                if ("rejected".equals(result)) {
                    meters.counter(AsyncMetricNames.JOB_RETRY, "job.type", jobType,
                            "error.category", "internal").increment();
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${async-processing.job.recovery-interval:30000}")
    public void recoverExpired() {
        int count = claimService.recoverExpired(20, retrySchedule);
        if (count > 0) {
            meters.counter(AsyncMetricNames.JOB_RECOVERY, "result", "recovered").increment(count);
            log.warn("result=retry errorCategory=internal recoveredJobs={}", count);
        }
    }

    private void execute(AsyncJob job) {
        Timer.Sample sample = Timer.start(meters);
        String result = "failure";
        String errorCategory = "none";
        String jobType = handlers.metricType(job.getJobType(), job.getSchemaVersion());
        Map<String, String> previous = ObservabilityContext.capture();
        Map<String, String> context = new LinkedHashMap<>();
        context.put(ObservabilityContext.TRACE_ID, ObservabilityContext.newTraceId());
        if (job.getOperationId() != null) context.put(ObservabilityContext.OPERATION_ID, job.getOperationId());
        context.put(ObservabilityContext.JOB_ID, String.valueOf(job.getId()));
        context.put(ObservabilityContext.ATTEMPT, String.valueOf(job.getAttempt()));
        ObservabilityContext.install(context);
        try {
            JobExecutionContext execution = new JobExecutionContext(job.getId(), job.getLeaseOwner(), mapper,
                    Duration.ofSeconds(90));
            handlers.dispatch(job, execution);
            if (mapper.markSucceeded(job.getId(), job.getLeaseOwner(), LocalDateTime.now()) != 1) {
                log.warn("result=failure errorCategory=conflict reason=stale_job_owner jobType={}", jobType);
                result = "stale_owner";
                errorCategory = "conflict";
            } else {
                result = "success";
            }
        } catch (Exception error) {
            FailureResult failure = handleFailure(job, jobType, error);
            result = failure.result();
            errorCategory = failure.category();
        } finally {
            ObservabilityContext.install(previous);
            sample.stop(Timer.builder(AsyncMetricNames.JOB_DURATION).tag("job.type", jobType)
                    .tag("result", result).tag("error.category", errorCategory).register(meters));
            Counter.builder(AsyncMetricNames.JOB_EXECUTION).tag("job.type", jobType)
                    .tag("result", result).tag("error.category", errorCategory).register(meters).increment();
        }
    }

    private FailureResult handleFailure(AsyncJob job, String jobType, Exception error) {
        JobFailureClassifier.Failure failure = classifier.classify(error);
        String message = sanitize(error);
        boolean exhausted = job.getAttempt() >= job.getMaxAttempts();
        if (failure.kind() == JobFailureClassifier.Kind.PERMANENT
                || failure.kind() == JobFailureClassifier.Kind.UNKNOWN || exhausted) {
            if (mapper.markDead(job.getId(), job.getLeaseOwner(), failure.category(), message, LocalDateTime.now()) != 1) {
                log.warn("result=failure errorCategory=conflict reason=stale_job_owner jobType={}", jobType);
                return new FailureResult("stale_owner", "conflict");
            }
            meters.counter(AsyncMetricNames.JOB_DEAD, "job.type", jobType,
                    "error.category", failure.category()).increment();
            log.error("result=dead errorCategory={} jobType={} exceptionType={}",
                    failure.category(), jobType, error.getClass().getSimpleName());
            return new FailureResult(failure.kind() == JobFailureClassifier.Kind.UNKNOWN ? "unknown" : "dead",
                    failure.category());
        }
        if (mapper.markRetry(job.getId(), job.getLeaseOwner(),
                LocalDateTime.now().plus(retrySchedule.delay(job.getAttempt())), failure.category(), message) != 1) {
            log.warn("result=failure errorCategory=conflict reason=stale_job_owner jobType={}", jobType);
            return new FailureResult("stale_owner", "conflict");
        }
        meters.counter(AsyncMetricNames.JOB_RETRY, "job.type", jobType,
                "error.category", failure.category()).increment();
        log.warn("result=retry errorCategory={} jobType={} exceptionType={}",
                failure.category(), jobType, error.getClass().getSimpleName());
        return new FailureResult("retry", failure.category());
    }

    private String sanitize(Throwable error) {
        return error.getClass().getSimpleName();
    }

    private record FailureResult(String result, String category) {
    }
}
