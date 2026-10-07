package cn.jualn.miniapp.module.exam.bo;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试详情内部对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamDetailBO {
    private TimelineItemDTO cardTimeline;
    private String contactsJson;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventContactBO> contacts;
    private Long contractVersion;
    private Integer lifecycleStatus;
    private String sourceName;
    private String sourceUrl;
    private Boolean liked;
    private Boolean subscribed;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
    private Long coverAttachmentId;
    private java.time.LocalDateTime cancelledAt;
    private String cancelReason;

    private Integer publishStatus;

    private String summary;
    private Integer eventType;
    private String editionLabel;

    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO> sections;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventActionBO> actions;


    private Long id;
    private String title;
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
