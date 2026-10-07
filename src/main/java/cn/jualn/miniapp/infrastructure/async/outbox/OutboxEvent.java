package cn.jualn.miniapp.infrastructure.async.outbox;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboxEvent {
    private Long id;
    private String messageId;
    private String topic;
    private Integer schemaVersion;
    private String operationId;
    private String aggregateType;
    private String aggregateId;
    private String payload;
    private String status;
    private Integer attempt;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastErrorCategory;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    private LocalDateTime deadAt;
}
