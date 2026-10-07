package cn.jualn.miniapp.infrastructure.async.stream;

import cn.jualn.miniapp.infrastructure.async.message.MessageEnvelope;
import cn.jualn.miniapp.infrastructure.async.outbox.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class EventStreamTransport {
    public static final String STREAM_KEY = "jualn:async:events:v1";
    public static final String GROUP = "backend-events-v1";
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public EventStreamTransport(RedisTemplate<String, Object> redisTemplate,
                                @Qualifier("objectMapper") ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String publish(OutboxEvent event) {
        Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("messageId", event.getMessageId());
        fields.put("topic", event.getTopic());
        fields.put("schemaVersion", String.valueOf(event.getSchemaVersion()));
        fields.put("createdAt", event.getCreatedAt().atOffset(ZoneOffset.ofHours(8)).toString());
        if (event.getOperationId() != null) fields.put("operationId", event.getOperationId());
        fields.put("payload", event.getPayload());
        return String.valueOf(redisTemplate.opsForStream()
                .add(StreamRecords.newRecord().in(STREAM_KEY).ofMap(fields)));
    }

    public MessageEnvelope decode(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        try {
            return new MessageEnvelope(
                    required(fields, "messageId"), required(fields, "topic"),
                    Integer.parseInt(required(fields, "schemaVersion")),
                    OffsetDateTime.parse(required(fields, "createdAt")),
                    optional(fields, "operationId"),
                    objectMapper.readTree(required(fields, "payload")));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new IllegalArgumentException("Invalid event envelope", exception);
        }
    }

    private String required(Map<Object, Object> values, String key) {
        String value = optional(values, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing envelope field: " + key);
        return value;
    }

    private String optional(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
