package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminActivityDetailBO {
    private Long contractVersion;
    private Integer lifecycleStatus;
    private String formVersion;
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
    private String audienceDepartmentIds;
    private String contactsJson;
    private Integer registrationMode;
    private Integer participantMode;
    private Integer capacityUnit;
    private Integer officialCapacity;
    private Long coverAttachmentId;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO> sections;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventActionBO> actions;


    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String location;
    private ActivityCategory category;
    private ActivityStatus status;
    private Integer auditStatus;
    private String rejectReason;
    private String organizer;
    private Integer audienceScope;
    private String contactName;
    private String contactPhone;
    private String joinMethod;
    private String qrcodeUrl;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private Integer capacity;
    private Boolean pinned;
    private Integer commentCount;
    private Integer likeCount;
    private Integer viewCount;
    private Long subscriberCount;
    private Long notifyEnabledSubscriberCount;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private UserSimpleBO author;
    private List<MediaAttachmentBO> attachments;
    private List<TimelineItemDTO> timeline;
}
