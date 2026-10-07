package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
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
 * 活动详情内部对象。
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityDetailBO {
    private TimelineItemDTO cardTimeline;
    private Boolean liked;
    private Boolean enrolled;
    private Integer lifecycleStatus;
    private String contactsJson;
    private String audienceDepartmentIds;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventContactBO> contacts;
    private ActivityFormBO registrationForm;
    private String participationState;
    private Long submittedCount;
    private java.time.LocalDateTime evaluatedAt;
    private String activityPhase;
    private String registrationStatus;

    private Integer publishStatus;

    private String summary;
    private String audienceSummary;
    private Integer registrationMode;
    private Integer participantMode;
    private Integer capacityUnit;
    private Integer capacity;
    private Long coverAttachmentId;

    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO> sections;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventActionBO> actions;


    private Long id;
    private Long userId;
    private String title;
    private String location;
    private ActivityCategory category;
    private String organizer;
    private Integer audienceScope;
    private Integer commentCount;
    private Integer likeCount;
    private Integer viewCount;
    private LocalDateTime publishedAt;

    // 其他业务相关内容
    private UserSimpleBO author;
    private List<MediaAttachmentBO> attachmentItems;
    private List<TimelineItemDTO> timelineItems;
}
