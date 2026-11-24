package cn.jualn.miniapp.module.content.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminContentDetailBO extends AdminContentListBO {
    private cn.jualn.miniapp.module.content.bo.AdminPostProjection.QaContext qaContext;
    private java.util.List<cn.jualn.miniapp.module.post.bo.PostCommunityBO.Management> managementHistory;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String editableEtag;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.List<cn.jualn.miniapp.module.media.bo.GrowthMediaBO.File> growthMedia;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.GrowthRecordBO.Lifecycle growthLifecycle;
    private String content;
    private Author author;
    private List<Attachment> attachments;
    private List<ContextItem> context;
    private List<HistoryItem> history;
    private String rejectReason;
    private String imageUrl;
    private Integer targetType;
    private Long targetId;
    private Long parentId;

    @Data
    public static class Author {
        private String avatarText;
        private String statusLabel;
        private LocalDateTime joinedAt;
        private Long publishedCount;
        private Long violationCount;
    }

    @Data
    public static class Attachment {
        private String id;
        private String type;
        private String name;
        private String url;
    }

    @Data
    public static class ContextItem {
        private String label;
        private String value;
    }

    @Data
    public static class HistoryItem {
        private String title;
        private String detail;
        private String operator;
        private LocalDateTime createdAt;
        private String tone;
    }
}
