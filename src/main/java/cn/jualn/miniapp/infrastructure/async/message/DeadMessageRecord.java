package cn.jualn.miniapp.infrastructure.async.message;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeadMessageRecord {
    private long id;
    private String streamKey;
    private String groupName;
    private String recordId;
    private String messageId;
    private String operationId;
    private String topic;
    private String errorCategory;
    private String errorDetail;
    private int deliveryCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastSeenAt;
}
