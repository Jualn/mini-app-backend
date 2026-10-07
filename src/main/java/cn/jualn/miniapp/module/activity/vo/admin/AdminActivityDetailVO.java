package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminActivityDetailVO {
    private com.fasterxml.jackson.databind.JsonNode formSchema;
    private Integer registrationLimit;
    private java.time.LocalDateTime cancelledAt;
    private String cancelReason;

    private Integer publishStatus;
    private String activityPhase;
    private String registrationStatus;

    private Integer startPrecision;
    private Integer endPrecision;
    private String timeDescription;
    private Integer registrationStartPrecision;
    private Integer registrationEndPrecision;
    private java.time.LocalDateTime registrationStart;
    private java.time.LocalDateTime registrationEnd;

    private String summary;
    private String audienceSummary;
    private Integer registrationMode;
    private Integer participantMode;
    private Integer capacityUnit;
    private Integer officialCapacity;
    private Long coverAttachmentId;
    private java.util.List<cn.jualn.miniapp.module.activity.vo.EventSectionVO> sections;
    private java.util.List<cn.jualn.miniapp.module.activity.vo.EventActionVO> actions;


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
    private String nodeType;
    private String location;
    private Integer startPrecision;
    private Integer endPrecision;
    private String timeDescription;
    private Long id;

        private String label;
        private String description;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer sortOrder;
    }
}
