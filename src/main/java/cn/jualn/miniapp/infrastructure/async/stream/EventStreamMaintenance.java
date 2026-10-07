package cn.jualn.miniapp.infrastructure.async.stream;

import cn.jualn.miniapp.infrastructure.async.AsyncMetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class EventStreamMaintenance {
    private final RedisTemplate<String, Object> redisTemplate;
    private final AtomicReference<Double> length = unknown();
    private final AtomicReference<Double> lag = unknown();
    private final AtomicReference<Double> pending = unknown();
    private final AtomicReference<Double> oldestPendingAge = unknown();
    private final AtomicReference<Double> consumers = unknown();
    private final AtomicReference<Double> available = unknown();

    public EventStreamMaintenance(RedisTemplate<String, Object> redisTemplate, MeterRegistry meters) {
        this.redisTemplate = redisTemplate;
        gauge(meters, AsyncMetricNames.STREAM_LENGTH, "events", length);
        gauge(meters, AsyncMetricNames.STREAM_LAG, "events", lag);
        gauge(meters, AsyncMetricNames.STREAM_PENDING, "events", pending);
        gauge(meters, AsyncMetricNames.STREAM_OLDEST_PENDING_AGE, "seconds", oldestPendingAge);
        gauge(meters, AsyncMetricNames.STREAM_CONSUMERS, "consumers", consumers);
        gauge(meters, AsyncMetricNames.STREAM_AVAILABLE, "state", available);
    }

    @Scheduled(fixedDelayString = "${async-processing.metrics.refresh-interval:30000}")
    public void refreshPending() {
        try {
            Long streamLength = redisTemplate.opsForStream().size(EventStreamTransport.STREAM_KEY);
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                    .pending(EventStreamTransport.STREAM_KEY, EventStreamTransport.GROUP);
            StreamInfo.XInfoGroup group = findGroup();
            PendingMessages oldest = redisTemplate.opsForStream().pending(
                    EventStreamTransport.STREAM_KEY, EventStreamTransport.GROUP, Range.unbounded(), 1);
            length.set(streamLength == null ? 0D : streamLength.doubleValue());
            pending.set(summary == null ? 0D : (double) summary.getTotalPendingMessages());
            consumers.set(group.consumerCount().doubleValue());
            lag.set(readLag(group));
            oldestPendingAge.set(oldestAge(oldest));
            available.set(1D);
        } catch (RuntimeException unavailableOrUninitialized) {
            for (AtomicReference<Double> value : stateValues()) value.set(Double.NaN);
            available.set(0D);
        }
    }

    private StreamInfo.XInfoGroup findGroup() {
        for (StreamInfo.XInfoGroup group : redisTemplate.opsForStream().groups(EventStreamTransport.STREAM_KEY)) {
            if (EventStreamTransport.GROUP.equals(group.groupName())) return group;
        }
        throw new IllegalStateException("Event stream consumer group is not initialized");
    }

    private double readLag(StreamInfo.XInfoGroup group) {
        Object value = group.getRaw().get("lag");
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private double oldestAge(PendingMessages messages) {
        if (messages == null || messages.isEmpty()) return 0D;
        PendingMessage oldest = messages.iterator().next();
        return oldest.getElapsedTimeSinceLastDelivery().toMillis() / 1000D;
    }

    private List<AtomicReference<Double>> stateValues() {
        return List.of(length, lag, pending, oldestPendingAge, consumers);
    }

    private static AtomicReference<Double> unknown() {
        return new AtomicReference<>(Double.NaN);
    }

    private static void gauge(MeterRegistry meters, String name, String baseUnit,
                              AtomicReference<Double> value) {
        Gauge.builder(name, value, AtomicReference::get).baseUnit(baseUnit).register(meters);
    }

    @Scheduled(cron = "${async-processing.stream.trim-cron:0 40 4 * * *}")
    public void trimAcknowledgedHistory() {
        try {
            String safeBoundary = retentionBoundary();
            if (safeBoundary == null || "0-0".equals(safeBoundary)) return;
            byte[] key = EventStreamTransport.STREAM_KEY.getBytes(StandardCharsets.UTF_8);
            byte[] minId = safeBoundary.getBytes(StandardCharsets.UTF_8);
            redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute("XTRIM", key,
                    "MINID".getBytes(StandardCharsets.UTF_8), minId));
        } catch (RuntimeException failure) {
            log.warn("result=retry errorCategory=redis operation=stream_trim exceptionType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private String retentionBoundary() {
        String cutoff = (Instant.now().minus(Duration.ofDays(3)).toEpochMilli()) + "-0";
        String boundary = cutoff;
        for (StreamInfo.XInfoGroup group : redisTemplate.opsForStream().groups(EventStreamTransport.STREAM_KEY)) {
            if (EventStreamTransport.GROUP.equals(group.groupName())) {
                boundary = earlier(boundary, group.lastDeliveredId());
            }
        }
        PendingMessagesSummary summary = redisTemplate.opsForStream()
                .pending(EventStreamTransport.STREAM_KEY, EventStreamTransport.GROUP);
        if (summary != null && summary.getTotalPendingMessages() > 0) {
            boundary = earlier(boundary, summary.minMessageId());
        }
        return boundary;
    }

    private String earlier(String left, String right) {
        if (right == null) return left;
        String[] a = left.split("-", 2);
        String[] b = right.split("-", 2);
        int milliseconds = Long.compare(Long.parseLong(a[0]), Long.parseLong(b[0]));
        if (milliseconds != 0) return milliseconds < 0 ? left : right;
        return Long.parseLong(a[1]) <= Long.parseLong(b[1]) ? left : right;
    }
}
