package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminActivitySaveBO {
    private com.fasterxml.jackson.databind.JsonNode formSchema;
    private Integer registrationLimit;
    private Boolean clearRegistrationLimit;
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
    private Integer capacity;
    private Long coverAttachmentId;
    private String coverObjectKey;
    private Boolean clearCover;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO> sections;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventActionBO> actions;


    private Long id;
    private Long operatorId;
    private String title;
    private String content;
    private String location;
    private ActivityCategory category;
    private String organizer;
    private Integer audienceScope;
    private String contactName;
    private String contactPhone;
    private String joinMethod;
    private String qrcodeUrl;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private Integer maxParticipants;
    private List<TimelineItemBO> timelineItems;
    private List<AttachmentItemBO> attachmentItems;
}
