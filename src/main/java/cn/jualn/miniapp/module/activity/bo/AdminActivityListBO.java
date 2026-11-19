package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.module.activity.enums.ActivityCategory;
import cn.jualn.miniapp.module.activity.enums.ActivityStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminActivityListBO {
    private java.time.LocalDateTime deletedAt;
    private String stageState;
    private java.time.LocalDateTime evaluatedAt;


    private Integer publishStatus;
    private Integer lifecycleStatus;
    private Long id;
    private String title;
    private String summary;
    private ActivityCategory category;
    private ActivityStatus status;
    private Integer auditStatus;
    private String organizer;
    private String location;
    private Boolean pinned;
    private Long subscriberCount;
    private Integer capacity;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private Integer viewCount;
    private LocalDateTime updatedAt;
}
