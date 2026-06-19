package cn.jualn.miniapp.module.activity.vo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动详情响应对象。
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Data
@Builder
public class ActivityDetailVO {

    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String location;
    // 0-DRAFT 2-SIGNUP 3-ONGOING 4-ENDED 5-CANCELED
    private ActivityStatus status;
    private ActivityCategory category;
    private String organizer;
    private Integer audienceScope;
    private String contactInfo;
    private String joinMethod;
    private String qrcodeUrl;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private Integer maxParticipants;
    private Integer commentCount;
    private Integer likeCount;
    private Integer viewCount;
    private LocalDateTime publishedAt;

    // 关联其他业务相关内容
    private UserSimpleBO author;
    private List<MediaAttachmentBO> attachmentItems;
    private List<TimelineItemDTO> timelineItems;

    // 当前用户相关状态
    private Boolean liked;
    private Boolean enrolled;
}
