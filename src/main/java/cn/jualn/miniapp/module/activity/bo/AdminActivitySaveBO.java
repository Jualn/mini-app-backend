package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminActivitySaveBO {

    private Long id;
    private Long operatorId;
    private String title;
    private String content;
    private String location;
    private ActivityCategory category;
    private String organizer;
    private Integer audienceScope;
    private String contactName;
    private String contactPhone;
    private String joinMethod;
    private String qrcodeUrl;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private Integer maxParticipants;
    private List<TimelineItemBO> timelineItems;
    private List<AttachmentItemBO> attachmentItems;
}
