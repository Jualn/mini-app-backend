package cn.jualn.miniapp.module.search.vo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动列表项响应对象。
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Data
@Builder
public class SearchActivityVO {
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
    private ActivityStatus status;
    private ActivityCategory category;
    private String organizer;
    private Integer maxParticipants;
    private Integer audienceScope;
    private LocalDateTime enrollDeadline;
    private LocalDateTime publishedAt;
}
