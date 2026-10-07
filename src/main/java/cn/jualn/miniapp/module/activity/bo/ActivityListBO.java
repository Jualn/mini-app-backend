package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityListBO {
    private cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO cardTimeline;
    private Integer lifecycleStatus;
    private String audienceDepartmentIds;
    private String participationState;
    private Long submittedCount;
    private java.time.LocalDateTime evaluatedAt;
    private Integer publishStatus;
    private String activityPhase;
    private String registrationStatus;

    private String summary;
    private String audienceSummary;
    private Integer registrationMode;
    private Integer participantMode;
    private Integer capacity;
    private Integer capacityUnit;
    private Long coverAttachmentId;
    private MediaAttachmentBO coverAttachment;
    private Long id;
    private String title;
    private String location;
    private ActivityCategory category;
    private String organizer;
    private Integer audienceScope;
    private LocalDateTime publishedAt;
}
