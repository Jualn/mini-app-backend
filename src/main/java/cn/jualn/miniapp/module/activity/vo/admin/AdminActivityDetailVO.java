package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminActivityDetailVO {

    private String id;
    private String title;
    private String content;
    private String location;
    private String category;
    private String status;
    private String auditStatus;
    private String rejectReason;
    private String organizer;
    private List<String> audienceCodes;
    private String contactName;
    private String contactPhoneMasked;
    private String joinMethod;
    private String qrcodeUrl;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private Integer capacity;
    private Boolean isPinned;
    private Integer commentCount;
    private Integer likeCount;
    private Integer viewCount;
    private Long subscriberCount;
    private Long notifyEnabledSubscriberCount;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Author author;
    private List<Attachment> attachments;
    private List<TimelineItem> timeline;

    @Data
    @Builder
    public static class Author {
        private String id;
        private String displayName;
        private String avatarUrl;
    }

    @Data
    @Builder
    public static class Attachment {
        private String id;
        private String typeCode;
        private String objectKey;
        private String url;
        private String name;
        private Integer sortOrder;
    }

    @Data
    @Builder
    public static class TimelineItem {
        private String label;
        private String description;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer sortOrder;
    }
}
