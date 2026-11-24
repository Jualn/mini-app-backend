package cn.jualn.miniapp.infrastructure.async.outbox;

import cn.jualn.miniapp.infrastructure.async.job.RetrySchedule;
import cn.jualn.miniapp.infrastructure.async.message.EventHandlerRegistry;
import cn.jualn.miniapp.infrastructure.async.stream.EventStreamTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import cn.jualn.miniapp.infrastructure.async.AsyncMetricNames;

class OutboxPublisherTest {
    @Test
    void publisherAttemptsUseFreshTracesAndKeepDurableMessageIdentity() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        OutboxMapper mapper = mock(OutboxMapper.class);
        EventStreamTransport transport = mock(EventStreamTransport.class);
        EventHandlerRegistry handlers = mock(EventHandlerRegistry.class);
        OutboxEvent event = event(41L, 1);
        event.setOperationId("logical-operation");
        when(claims.claim(any(), eq(20))).thenReturn(List.of(event));
        when(handlers.metricTopic("audit.completed", 1)).thenReturn("audit.completed");
        when(mapper.markPublished(eq(41L), any(), any())).thenReturn(1);
        java.util.List<java.util.Map<String, String>> attempts = new java.util.ArrayList<>();
        when(transport.publish(event)).thenAnswer(invocation -> {
            attempts.add(cn.jualn.miniapp.common.observability.ObservabilityContext.capture());
            return "1-0";
        });
        var publisher = new OutboxPublisher(claims, mapper, transport, handlers,
                new RetrySchedule(), new SimpleMeterRegistry());
        org.slf4j.MDC.put("traceId", "scheduler-trace");
        try {
            publisher.publishDue();
            event.setAttempt(2);
            publisher.publishDue();
            org.junit.jupiter.api.Assertions.assertNotEquals(attempts.get(0).get("traceId"), attempts.get(1).get("traceId"));
            for (int i = 0; i < 2; i++) {
                assertEquals(event.getMessageId(), attempts.get(i).get("messageId"));
                assertEquals("logical-operation", attempts.get(i).get("operationId"));
                assertEquals(Integer.toString(i + 1), attempts.get(i).get("attempt"));
            }
            assertEquals("scheduler-trace", org.slf4j.MDC.get("traceId"));
            org.junit.jupiter.api.Assertions.assertNull(org.slf4j.MDC.get("messageId"));
        } finally { org.slf4j.MDC.clear(); }
    }

    @Test
    void xaddUnknownKeepsSameEventPendingForBackoff() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        OutboxMapper mapper = mock(OutboxMapper.class);
        EventStreamTransport transport = mock(EventStreamTransport.class);
        EventHandlerRegistry handlers = mock(EventHandlerRegistry.class);
        OutboxEvent event = event(41L, 2);
        when(claims.claim(any(), eq(20))).thenReturn(List.of(event));
        when(transport.publish(event)).thenThrow(new RuntimeException("response lost"));
        when(handlers.metricTopic("audit.completed", 1)).thenReturn("audit.completed");
        when(mapper.markRetry(eq(41L), any(String.class), any(LocalDateTime.class),
                eq("redis"), any(String.class))).thenReturn(1);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxPublisher publisher = new OutboxPublisher(claims, mapper, transport, handlers,
                new RetrySchedule(), meters);

        publisher.publishDue();

        verify(mapper).markRetry(eq(41L), any(String.class), any(LocalDateTime.class),
                eq("redis"), any(String.class));
        assertEquals(1D, meters.get(AsyncMetricNames.OUTBOX_RETRY)
                .tag("topic", "audit.completed").tag("error.category", "redis").counter().count());
    }

    private OutboxEvent event(long id, int attempt) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setMessageId("11111111111111111111111111111111");
        event.setTopic("audit.completed");
        event.setSchemaVersion(1);
        event.setAttempt(attempt);
        event.setLeaseOwner("owner");
        return event;
    }
}
