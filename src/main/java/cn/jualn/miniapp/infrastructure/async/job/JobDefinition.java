package cn.jualn.miniapp.infrastructure.async.job;

import java.time.LocalDateTime;

public record JobDefinition(
        String jobType,
        int schemaVersion,
        String operationId,
        String dedupeKey,
        String subjectType,
        String subjectId,
        Object payload,
        LocalDateTime nextRunAt,
        int maxAttempts) {
}
