package cn.jualn.miniapp.module.exam.vo.admin;
import cn.jualn.miniapp.module.exam.vo.EventSectionVO;
import cn.jualn.miniapp.module.exam.vo.EventActionVO;




import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试详情响应对象。
 */
@Data
public class AdminPublicEventDetailVO {
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

    private String activityPhase;
    private String registrationStatus;

    private Integer publishStatus;

    private Integer startPrecision;
    private Integer endPrecision;
    private String timeDescription;
    private Integer registrationStartPrecision;
    private Integer registrationEndPrecision;
    private java.time.LocalDateTime startTime;
    private java.time.LocalDateTime endTime;

    private String summary;
    private Integer eventType;
    private String editionLabel;

    private java.util.List<EventSectionVO> sections;
    private java.util.List<EventActionVO> actions;


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


    private List<Attachment> attachmentItems;
    /** 活动时间线节点列表 */
    private List<Timeline> timelineItems;

    private LocalDateTime updatedAt;

    @Data public static class Attachment {
        private Long id;
        private cn.jualn.miniapp.common.enums.MediaType type;
        private String objectKey;
        private String url;
        private String originalName;
        private Integer sortOrder;
    }
    @Data public static class Timeline {
        private Long id;
        private String label;
        private String description;
        private String nodeType;
        private String location;
        private String timeDescription;
        private Integer startPrecision;
        private Integer endPrecision;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer sortOrder;
    }
}

