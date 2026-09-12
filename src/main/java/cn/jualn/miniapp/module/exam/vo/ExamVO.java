package cn.jualn.miniapp.module.exam.vo;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试列表响应对象。
 */
@Data
public class ExamVO {
    private Integer publishStatus;
    private String activityPhase;
    private String registrationStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer startPrecision;
    private Integer endPrecision;
    private Integer registrationStartPrecision;
    private Integer registrationEndPrecision;
    private String timeDescription;
    private String organizer;
    private String location;
    private Integer audienceScope;
    private String audienceSummary;
    private String contactName;
    private String contactPhone;
    private Integer registrationMode;
    private Integer participantMode;
    private Integer capacity;
    private Integer capacityUnit;
    private Long coverAttachmentId;
    private java.time.LocalDateTime cancelledAt;
    private String cancelReason;

    private String summary;
    private Integer eventType;
    private String editionLabel;


    private Long id;
    private String title;
    private Integer category;
    private String content;
    private LocalDateTime registrationStart;
    private LocalDateTime registrationEnd;
    private LocalDate examDate;
    private LocalDate examDateEnd;
    private String officialUrl;
    private Integer commentCount;
    private Integer likeCount;
    private Integer viewCount;
    private LocalDateTime publishedAt;

    private UserSimpleBO author;
    private List<MediaAttachmentBO> attachmentItems;
    /** 活动时间线节点列表 */
    private List<TimelineItemDTO> timelineItems;
}


