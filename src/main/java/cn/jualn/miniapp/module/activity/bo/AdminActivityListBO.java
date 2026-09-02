package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminActivityListBO {

    private Long id;
    private String title;
    private String summary;
    private ActivityCategory category;
    private ActivityStatus status;
    private Integer auditStatus;
    private String organizer;
    private String location;
    private Integer audienceScope;
    private Boolean pinned;
    private Long subscriberCount;
    private Integer capacity;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private Integer viewCount;
    private LocalDateTime updatedAt;
}
