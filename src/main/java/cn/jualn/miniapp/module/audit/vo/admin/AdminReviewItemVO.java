package cn.jualn.miniapp.module.audit.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminReviewItemVO {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private ManagementTarget managementTarget;
    public record ManagementTarget(String namespace, BusinessTarget businessTarget) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record BusinessTarget(String type, String organizationId) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.GrowthRecordBO.Link growthRecord;
    private String taskId;
    private String targetId;
    private String targetType;
    private String title;
    private String contentPreview;
    private Author author;
    private String riskLevel;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer riskScore;
    private String machineConclusion;
    private List<String> matchedLabels;
    private String sourceType;
    private LocalDateTime submittedAt;
    private Long waitingMinutes;
    private Long reportCount;
    private Long attachmentCount;
    private String status;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private SafetyReviewProjection.Round review;

    @Data
    public static class Author {
        private String id;
        private String nickname;
        private String avatarUrl;
        private String roleCode;
        private String accountStatus;
        private LocalDateTime joinedAt;
    }
}
