package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动创建业务对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityCreateBO {

    private String title;
    private String content;
    private String location;
    private Integer category;
    private String organizer;
    private Integer audienceScope;
    private String contactInfo;
    private String joinMethod;
    private String qrcodeUrl;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private Integer maxParticipants;

    private List<TimelineItemBO> timelineItems;
    private List<AttachmentItemBO> attachmentItems;
}