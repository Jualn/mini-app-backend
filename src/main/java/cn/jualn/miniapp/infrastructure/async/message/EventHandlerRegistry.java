package cn.jualn.miniapp.infrastructure.async.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class EventHandlerRegistry {
    private final ObjectMapper mapper;
    private final Map<Key, EventHandler<?>> handlers = new HashMap<>();

    public EventHandlerRegistry(@Qualifier("objectMapper") ObjectMapper mapper,
                                List<EventHandler<?>> candidates) {
        this.mapper = mapper;
        for (EventHandler<?> handler : candidates) {
            if (handlers.put(new Key(handler.topic(), handler.schemaVersion()), handler) != null) {
                throw new IllegalStateException("Duplicate event handler: " + handler.topic());
            }
        }
    }

    public void dispatch(MessageEnvelope envelope) {
        EventHandler<?> handler = handlers.get(new Key(envelope.topic(), envelope.schemaVersion()));
        if (handler == null) throw new UnsupportedEventException(envelope.topic(), envelope.schemaVersion());
        dispatchTyped(handler, envelope);
    }

    public String metricTopic(String topic, int version) {
        return handlers.containsKey(new Key(topic, version)) ? topic : "unsupported";
    }

    private <T> void dispatchTyped(EventHandler<T> handler, MessageEnvelope envelope) {
        handler.handle(mapper.convertValue(envelope.payload(), handler.payloadType()), envelope);
    }

    private record Key(String topic, int version) {}
    public static final class UnsupportedEventException extends RuntimeException {
        public UnsupportedEventException(String topic, int version) {
            super("Unsupported event topic/version: " + topic + "/" + version);
        }
    }
}
