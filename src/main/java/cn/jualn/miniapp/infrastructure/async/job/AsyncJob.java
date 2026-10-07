package cn.jualn.miniapp.infrastructure.async.job;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AsyncJob {
    private Long id;
    private String jobType;
    private Integer schemaVersion;
    private String operationId;
    private String dedupeKey;
    private String subjectType;
    private String subjectId;
    private String payload;
    private String status;
    private LocalDateTime nextRunAt;
    private Integer attempt;
    private Integer maxAttempts;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastErrorCategory;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime deadAt;
}
