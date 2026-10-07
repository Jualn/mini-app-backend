package cn.jualn.miniapp.infrastructure.async.outbox;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.AsyncMetricNames;
import cn.jualn.miniapp.infrastructure.async.job.RetrySchedule;
import cn.jualn.miniapp.infrastructure.async.message.EventHandlerRegistry;
import cn.jualn.miniapp.infrastructure.async.stream.EventStreamTransport;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {
    private static final int MAX_ATTEMPTS = 8;
    private final OutboxClaimService claimService;
    private final OutboxMapper mapper;
    private final EventStreamTransport transport;
    private final EventHandlerRegistry handlers;
    private final RetrySchedule retries;
    private final MeterRegistry meters;
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    @Scheduled(fixedDelayString = "${async-processing.outbox.poll-interval:1000}")
    public void publishDue() {
        String owner = instanceId + "-" + UUID.randomUUID().toString().substring(0, 8);
        for (OutboxEvent event : claimService.claim(owner, 20)) {
            Map<String, String> previous = ObservabilityContext.capture();
            Map<String, String> context = new LinkedHashMap<>();
            context.put(ObservabilityContext.TRACE_ID, ObservabilityContext.newTraceId());
            context.put(ObservabilityContext.MESSAGE_ID, event.getMessageId());
            context.put(ObservabilityContext.ATTEMPT, String.valueOf(event.getAttempt()));
            if (event.getOperationId() != null) {
                context.put(ObservabilityContext.OPERATION_ID, event.getOperationId());
            }
            ObservabilityContext.install(context);
            String topic = handlers.metricTopic(event.getTopic(), event.getSchemaVersion());
            try {
                transport.publish(event);
                if (mapper.markPublished(event.getId(), owner, LocalDateTime.now()) == 1) {
                    meters.counter(AsyncMetricNames.OUTBOX_PUBLISH, "topic", topic, "result", "success").increment();
                } else {
                    meters.counter(AsyncMetricNames.OUTBOX_PUBLISH, "topic", topic, "result", "stale_owner").increment();
                    log.warn("result=failure errorCategory=conflict reason=stale_outbox_owner topic={}", topic);
                }
            } catch (IllegalArgumentException permanent) {
                int updated = mapper.markDead(event.getId(), owner, "validation", sanitize(permanent), LocalDateTime.now());
                meters.counter(AsyncMetricNames.OUTBOX_PUBLISH, "topic", topic, "result", "failure").increment();
                if (updated == 1) {
                    meters.counter(AsyncMetricNames.OUTBOX_DEAD, "topic", topic,
                            "error.category", "validation").increment();
                    log.error("result=dead errorCategory=validation topic={} exceptionType={}",
                            topic, permanent.getClass().getSimpleName());
                } else {
                    log.warn("result=failure errorCategory=conflict reason=stale_outbox_owner topic={}", topic);
                }
            } catch (RuntimeException retryableOrUnknown) {
                if (event.getAttempt() >= MAX_ATTEMPTS) {
                    if (mapper.markDead(event.getId(), owner, "redis", sanitize(retryableOrUnknown), LocalDateTime.now()) == 1) {
                        meters.counter(AsyncMetricNames.OUTBOX_DEAD, "topic", topic,
                                "error.category", "redis").increment();
                        log.error("result=dead errorCategory=redis topic={} exceptionType={}",
                                topic, retryableOrUnknown.getClass().getSimpleName());
                    } else {
                        log.warn("result=failure errorCategory=conflict reason=stale_outbox_owner topic={}", topic);
                    }
                } else {
                    if (mapper.markRetry(event.getId(), owner, LocalDateTime.now().plus(retries.delay(event.getAttempt())),
                            "redis", sanitize(retryableOrUnknown)) == 1) {
                        meters.counter(AsyncMetricNames.OUTBOX_RETRY, "topic", topic,
                                "error.category", "redis").increment();
                        log.warn("result=retry errorCategory=redis topic={}", topic);
                    } else {
                        log.warn("result=failure errorCategory=conflict reason=stale_outbox_owner topic={}", topic);
                    }
                }
                meters.counter(AsyncMetricNames.OUTBOX_PUBLISH, "topic", topic, "result", "failure").increment();
            } finally {
                ObservabilityContext.install(previous);
            }
        }
    }

    private String sanitize(Throwable error) {
        return error.getClass().getSimpleName();
    }
}
