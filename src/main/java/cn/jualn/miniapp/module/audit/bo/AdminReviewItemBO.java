package cn.jualn.miniapp.module.audit.bo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminReviewItemBO {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.content.bo.ManagementTarget managementTarget;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.GrowthRecordBO.Link growthRecord;
    private Long taskId;
    private Long targetId;
    private String targetType;
    private String title;
    private String contentPreview;
    private Author author;
    private String riskLevel;
    private Integer riskScore;
    private String machineConclusion;
    private List<String> matchedLabels;
    private String sourceType;
    private LocalDateTime submittedAt;
    private Long reportCount;
    private Long attachmentCount;
    private String status;
    private SafetyReviewProjection.Round review;

    @Data
    public static class Author {
        private Long id;
        private String nickname;
        private String avatarUrl;
        private String roleCode;
        private String accountStatus;
        private LocalDateTime joinedAt;
    }
}
