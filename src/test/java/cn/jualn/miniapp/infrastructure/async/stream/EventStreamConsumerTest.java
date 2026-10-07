package cn.jualn.miniapp.infrastructure.async.stream;

import cn.jualn.miniapp.infrastructure.async.message.DeadMessageService;
import cn.jualn.miniapp.infrastructure.async.message.EventHandlerRegistry;
import cn.jualn.miniapp.infrastructure.async.message.MessageEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventStreamConsumerTest {

    @Test
    void existingConsumerGroupWrappedBySpringIsAnIdempotentSuccess() {
        Fixture fixture = fixture();
        RuntimeException busyGroup = new RuntimeException("BUSYGROUP Consumer Group name already exists");
        when(fixture.stream.createGroup(eq(EventStreamTransport.STREAM_KEY), any(ReadOffset.class),
                eq(EventStreamTransport.GROUP)))
                .thenThrow(new RedisSystemException("Error in execution", busyGroup));

        assertDoesNotThrow(fixture.consumer::ensureGroup);
    }

    @Test
    void unrelatedRedisFailureStillPropagatesFromGroupInitialization() {
        Fixture fixture = fixture();
        RedisSystemException failure = new RedisSystemException("Connection reset", new RuntimeException("I/O"));
        when(fixture.stream.createGroup(eq(EventStreamTransport.STREAM_KEY), any(ReadOffset.class),
                eq(EventStreamTransport.GROUP))).thenThrow(failure);

        RedisSystemException thrown = assertThrows(RedisSystemException.class, fixture.consumer::ensureGroup);

        assertSame(failure, thrown);
    }

    @Test
    void transientFailureStaysPendingBeforeDeliveryBudgetIsExhausted() {
        Fixture fixture = fixture();

        fixture.consumer.process(fixture.record, 7);

        verify(fixture.deadMessages, never()).record(any(), any(), anyInt(), any(), any());
        verify(fixture.stream, never()).acknowledge(any(), any(), any(RecordId.class));
    }

    @Test
    void exhaustedFailurePersistsTerminalEvidenceBeforeAcknowledge() {
        Fixture fixture = fixture();

        fixture.consumer.process(fixture.record, 8);

        verify(fixture.deadMessages).record(eq("1-0"), eq(fixture.envelope), eq(8),
                eq("internal"), any(IllegalStateException.class));
        verify(fixture.stream).acknowledge(EventStreamTransport.STREAM_KEY,
                EventStreamTransport.GROUP, fixture.record.getId());
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(stream);
        EventStreamTransport transport = mock(EventStreamTransport.class);
        EventHandlerRegistry handlers = mock(EventHandlerRegistry.class);
        DeadMessageService deadMessages = mock(DeadMessageService.class);
        MessageEnvelope envelope = new MessageEnvelope(
                "11111111111111111111111111111111", "audit.completed", 1,
                OffsetDateTime.now(), null, new ObjectMapper().createObjectNode());
        Map<Object, Object> values = Map.of("payload", "{}");
        MapRecord<String, Object, Object> record = StreamRecords.<String, Object, Object>mapBacked(values)
                .withStreamKey(EventStreamTransport.STREAM_KEY)
                .withId(RecordId.of("1-0"));
        when(transport.decode(record)).thenReturn(envelope);
        when(handlers.metricTopic("audit.completed", 1)).thenReturn("audit.completed");
        org.mockito.Mockito.doThrow(new IllegalStateException("handler failed"))
                .when(handlers).dispatch(envelope);
        Executor unused = command -> { };
        EventStreamConsumer consumer = new EventStreamConsumer(redis, transport, handlers,
                deadMessages, unused, new SimpleMeterRegistry());
        return new Fixture(consumer, stream, deadMessages, envelope, record);
    }

    private record Fixture(EventStreamConsumer consumer,
                           StreamOperations<String, Object, Object> stream,
                           DeadMessageService deadMessages,
                           MessageEnvelope envelope,
                           MapRecord<String, Object, Object> record) {
    }
}
