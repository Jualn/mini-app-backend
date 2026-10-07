package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminActivityDraftVO {
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
    private String category;
    private String organizer;
    private String content;
    private String location;
    private List<String> audienceCodes;
    private String contactName;
    private String contactPhone;
    private String joinMethod;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private String maxParticipants;
    private List<TimelineItem> timeline;
    private List<Attachment> attachments;

    @Data
    @Builder
    public static class TimelineItem {
    private String nodeType;
    private String location;
    private Integer startPrecision;
    private Integer endPrecision;
    private String timeDescription;

        private String id;
        private String label;
        private String description;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }

    @Data
    @Builder
    public static class Attachment {
        private String id;
        private String type;
        private String objectKey;
        private String name;
        private String detail;
    }
}
