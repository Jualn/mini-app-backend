package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
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

    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String location;
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

    // 其他业务相关内容
    private UserSimpleBO author;
    private List<MediaAttachmentBO> attachmentItems;
    private List<TimelineItemDTO> timelineItems;
}
