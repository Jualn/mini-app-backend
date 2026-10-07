package cn.jualn.miniapp.infrastructure.async.message;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

public record MessageEnvelope(
        String messageId,
        String topic,
        int schemaVersion,
        OffsetDateTime createdAt,
        String operationId,
        JsonNode payload) {
}
