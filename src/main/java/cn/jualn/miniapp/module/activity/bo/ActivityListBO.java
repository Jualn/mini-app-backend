package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityListBO {
    private Integer startPrecision;
    private Integer endPrecision;
    private String timeDescription;
    private Integer registrationStartPrecision;
    private Integer registrationEndPrecision;
    private java.time.LocalDateTime registrationStart;
    private java.time.LocalDateTime registrationEnd;
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
    private LocalDateTime startTime;
    private LocalDateTime endTime;


    private Long id;
    private String title;
    private String location;
    private ActivityCategory category;
    private String organizer;
    private Integer maxParticipants;
    private Integer audienceScope;
    private LocalDateTime enrollDeadline;
    private ActivityStatus status;
    private LocalDateTime publishedAt;
}
