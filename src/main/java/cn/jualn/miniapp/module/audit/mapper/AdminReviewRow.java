package cn.jualn.miniapp.module.audit.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminReviewRow {
    private Long taskId;
    private Integer targetType;
    private Long targetId;
    private String title;
    private String content;
    private Long authorId;
    private String authorName;
    private String avatarUrl;
    private Integer authorRole;
    private Integer authorStatus;
    private LocalDateTime authorCreatedAt;
    private String imageUrl;
    private Integer targetStatus;
    private Long parentId;
    private Long reportCount;
    private Long attachmentCount;
    private String wxTraceId;
    private String wxDetail;
    private LocalDateTime submittedAt;
    private Long manualLogId;
    private Integer manualResult;
    private Long manualUserId;
    private String manualRemark;
    private LocalDateTime manualCreatedAt;
}
