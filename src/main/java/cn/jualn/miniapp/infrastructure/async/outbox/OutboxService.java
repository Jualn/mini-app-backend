package cn.jualn.miniapp.infrastructure.async.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OutboxService {
    private final OutboxMapper mapper;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxMapper mapper,
                         @Qualifier("objectMapper") ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String append(String topic, int schemaVersion, String operationId, String aggregateType,
                         String aggregateId, Object payload) {
        if (topic == null || topic.isBlank() || schemaVersion < 1 || payload == null) {
            throw new IllegalArgumentException("Invalid outbox event");
        }
        OutboxEvent event = new OutboxEvent();
        event.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        event.setTopic(topic);
        event.setSchemaVersion(schemaVersion);
        event.setOperationId(operationId);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(write(payload));
        event.setNextAttemptAt(LocalDateTime.now());
        mapper.insert(event);
        return event.getMessageId();
    }

    private String write(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > 65536) throw new IllegalArgumentException("Outbox payload exceeds 64 KiB");
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox payload cannot be serialized", exception);
        }
    }
}
