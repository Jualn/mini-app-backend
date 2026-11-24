package cn.jualn.miniapp.module.content.bo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminContentListBO {
    private cn.jualn.miniapp.module.content.bo.ManagementTarget managementTarget;
    private Long id;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.GrowthRecordBO.Link growthRecord;
    private cn.jualn.miniapp.module.content.bo.AdminPostProjection.Organization postOrganization;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.PostCommunityBO.Lifecycle communityLifecycle;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.MentorQaBO.Summary qaSummary;
    private String type;
    private String title;
    private String contentPreview;
    private String authorName;
    private Long authorId;
    private String status;
    private String auditStatus;
    private Boolean pinned;
    private Boolean featured;
    private Long reportCount;
    private Long viewCount;
    private Long likeCount;
    private Long commentCount;
    private Long shareCount;
    private LocalDateTime publishedAt;
    private LocalDateTime updatedAt;
    private Integer rawStatus;
    private List<String> availableActions;
}
