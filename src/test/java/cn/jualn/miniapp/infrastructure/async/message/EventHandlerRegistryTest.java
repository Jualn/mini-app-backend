package cn.jualn.miniapp.infrastructure.async.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventHandlerRegistryTest {
    record Payload(String value) {}

    @Test
    void dispatchesByTopicAndSchemaVersion() {
        AtomicReference<String> handled = new AtomicReference<>();
        EventHandlerRegistry registry = new EventHandlerRegistry(new ObjectMapper(), List.of(handler(handled)));

        registry.dispatch(envelope(1));

        assertEquals("ok", handled.get());
    }

    @Test
    void rejectsUnknownSchemaVersion() {
        EventHandlerRegistry registry = new EventHandlerRegistry(new ObjectMapper(),
                List.of(handler(new AtomicReference<>())));

        assertThrows(EventHandlerRegistry.UnsupportedEventException.class, () -> registry.dispatch(envelope(2)));
        assertEquals("unsupported", registry.metricTopic("attacker-controlled-" + System.nanoTime(), 99));
    }

    private MessageEnvelope envelope(int version) {
        return new MessageEnvelope("message-1", "sample.completed", version, OffsetDateTime.now(), null,
                new ObjectMapper().createObjectNode().put("value", "ok"));
    }

    private EventHandler<Payload> handler(AtomicReference<String> handled) {
        return new EventHandler<>() {
            @Override public String topic() { return "sample.completed"; }
            @Override public int schemaVersion() { return 1; }
            @Override public Class<Payload> payloadType() { return Payload.class; }
            @Override public void handle(Payload payload, MessageEnvelope envelope) { handled.set(payload.value()); }
        };
    }
}
