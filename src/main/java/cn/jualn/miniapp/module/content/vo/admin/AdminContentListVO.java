package cn.jualn.miniapp.module.content.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminContentListVO {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private ManagementTarget managementTarget;
    public record ManagementTarget(String namespace, BusinessTarget businessTarget) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record BusinessTarget(String type, String organizationId) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.GrowthRecordBO.Link growthRecord;
    private String id;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.content.bo.AdminPostProjection.Organization postOrganization;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.PostCommunityBO.Lifecycle communityLifecycle;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.MentorQaBO.Summary qaSummary;
    private String type;
    private String title;
    private String contentPreview;
    private String authorName;
    private String authorId;
    private String status;
    private String auditStatus;
    private Boolean isPinned;
    private Boolean isFeatured;
    private Long reportCount;
    private Metrics metrics;
    private LocalDateTime publishedAt;
    private LocalDateTime updatedAt;
    private List<String> availableActions;

    @Data
    public static class Metrics {
        private Long views;
        private Long likes;
        private Long comments;
        private Long shares;
    }
}
