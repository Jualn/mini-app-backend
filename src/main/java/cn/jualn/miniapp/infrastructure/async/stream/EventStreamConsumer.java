package cn.jualn.miniapp.infrastructure.async.stream;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.async.AsyncMetricNames;
import cn.jualn.miniapp.infrastructure.async.message.DeadMessageService;
import cn.jualn.miniapp.infrastructure.async.message.EventHandlerRegistry;
import cn.jualn.miniapp.infrastructure.async.message.MessageEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class EventStreamConsumer implements SmartLifecycle {
    private static final Duration BLOCK = Duration.ofSeconds(2);
    private static final Duration RECLAIM_IDLE = Duration.ofSeconds(120);
    private static final int MAX_DELIVERY_ATTEMPTS = 8;
    private final RedisTemplate<String, Object> redisTemplate;
    private final EventStreamTransport transport;
    private final EventHandlerRegistry handlers;
    private final DeadMessageService deadMessages;
    private final Executor executor;
    private final MeterRegistry meters;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean redisUnavailable = new AtomicBoolean();
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    public EventStreamConsumer(RedisTemplate<String, Object> redisTemplate, EventStreamTransport transport,
                               EventHandlerRegistry handlers, DeadMessageService deadMessages,
                               @Qualifier("eventConsumerExecutor") Executor executor, MeterRegistry meters) {
        this.redisTemplate = redisTemplate;
        this.transport = transport;
        this.handlers = handlers;
        this.deadMessages = deadMessages;
        this.executor = executor;
        this.meters = meters;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        for (int index = 0; index < 2; index++) {
            String consumerName = instanceId + "-" + index;
            executor.execute(() -> consumeLoop(consumerName));
        }
    }

    private void consumeLoop(String consumerName) {
        long backoff = 1000;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                ensureGroup();
                reclaim(consumerName);
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        Consumer.from(EventStreamTransport.GROUP, consumerName),
                        StreamReadOptions.empty().count(10).block(BLOCK),
                        StreamOffset.create(EventStreamTransport.STREAM_KEY, ReadOffset.lastConsumed()));
                if (records != null) for (MapRecord<String, Object, Object> record : records) process(record, 1);
                if (redisUnavailable.compareAndSet(true, false)) {
                    log.info("result=recovered dependency=redis workerType=event-stream");
                }
                backoff = 1000;
            } catch (DataAccessException redisFailure) {
                if (redisUnavailable.compareAndSet(false, true)) {
                    log.warn("result=degraded errorCategory=redis workerType=event-stream exceptionType={}",
                            redisFailure.getClass().getSimpleName());
                }
                sleep(backoff);
                backoff = Math.min(backoff * 2, 30000);
            } catch (RuntimeException unexpected) {
                log.error("result=failure errorCategory=internal workerType=event-stream", unexpected);
                sleep(1000);
            }
        }
    }

    void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(EventStreamTransport.STREAM_KEY, ReadOffset.from("0-0"),
                    EventStreamTransport.GROUP);
        } catch (RuntimeException exception) {
            if (!isExistingGroup(exception)) throw exception;
        }
    }

    private boolean isExistingGroup(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) return true;
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return false;
    }

    private void reclaim(String consumerName) {
        PendingMessages pending = redisTemplate.opsForStream().pending(
                EventStreamTransport.STREAM_KEY, EventStreamTransport.GROUP, Range.unbounded(), 10);
        if (pending == null || pending.isEmpty()) return;
        List<RecordId> ids = new ArrayList<>();
        Map<String, Long> deliveries = new LinkedHashMap<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(RECLAIM_IDLE) >= 0) {
                ids.add(message.getId());
                deliveries.put(message.getIdAsString(), message.getTotalDeliveryCount() + 1);
            }
        }
        if (ids.isEmpty()) return;
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().claim(
                EventStreamTransport.STREAM_KEY, EventStreamTransport.GROUP, consumerName,
                RECLAIM_IDLE, ids.toArray(RecordId[]::new));
        for (MapRecord<String, Object, Object> record : records) {
            meters.counter(AsyncMetricNames.STREAM_RECLAIM, "result", "claimed").increment();
            process(record, deliveries.getOrDefault(record.getId().getValue(), 2L).intValue());
        }
    }

    void process(MapRecord<String, Object, Object> record, int attempt) {
        Timer.Sample sample = Timer.start(meters);
        MessageEnvelope envelope;
        try {
            envelope = transport.decode(record);
        } catch (IllegalArgumentException poison) {
            deadMessages.record(record.getId().getValue(), null, attempt, "validation", poison);
            log.error("result=dead errorCategory=validation streamRecordId={} exceptionType={}",
                    record.getId(), poison.getClass().getSimpleName());
            acknowledge(record);
            meters.counter(AsyncMetricNames.STREAM_DELIVERY, "topic", "unreadable",
                    "result", "dead", "error.category", "validation").increment();
            sample.stop(Timer.builder(AsyncMetricNames.STREAM_DURATION).tag("topic", "unreadable")
                    .tag("result", "dead").tag("error.category", "validation").register(meters));
            return;
        }
        String topic = handlers.metricTopic(envelope.topic(), envelope.schemaVersion());
        String result = "failure";
        String errorCategory = "internal";
        Map<String, String> previous = ObservabilityContext.capture();
        Map<String, String> context = new LinkedHashMap<>();
        context.put(ObservabilityContext.TRACE_ID, ObservabilityContext.newTraceId());
        context.put(ObservabilityContext.MESSAGE_ID, envelope.messageId());
        context.put(ObservabilityContext.ATTEMPT, String.valueOf(attempt));
        if (envelope.operationId() != null) context.put(ObservabilityContext.OPERATION_ID, envelope.operationId());
        ObservabilityContext.install(context);
        try {
            handlers.dispatch(envelope);
            acknowledge(record);
            result = "success";
            errorCategory = "none";
        } catch (EventHandlerRegistry.UnsupportedEventException | IllegalArgumentException | BusinessException poison) {
            deadMessages.record(record.getId().getValue(), envelope, attempt, "validation", poison);
            log.error("result=dead errorCategory=validation topic={} exceptionType={}",
                    topic, poison.getClass().getSimpleName());
            acknowledge(record);
            result = "dead";
            errorCategory = "validation";
        } catch (RuntimeException failure) {
            if (attempt >= MAX_DELIVERY_ATTEMPTS) {
                deadMessages.record(record.getId().getValue(), envelope, attempt, "internal", failure);
                log.error("result=dead errorCategory=internal topic={} exceptionType={}",
                        topic, failure.getClass().getSimpleName());
                acknowledge(record);
                result = "dead";
            } else {
                log.warn("result=retry errorCategory=internal topic={} exceptionType={}",
                        topic, failure.getClass().getSimpleName());
                result = "retry";
            }
        } finally {
            ObservabilityContext.install(previous);
            meters.counter(AsyncMetricNames.STREAM_DELIVERY, "topic", topic,
                    "result", result, "error.category", errorCategory).increment();
            sample.stop(Timer.builder(AsyncMetricNames.STREAM_DURATION).tag("topic", topic)
                    .tag("result", result).tag("error.category", errorCategory).register(meters));
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(EventStreamTransport.STREAM_KEY,
                EventStreamTransport.GROUP, record.getId());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void stop() { running.set(false); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
