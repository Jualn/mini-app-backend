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
