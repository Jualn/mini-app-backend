package cn.jualn.miniapp.module.activity.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminActivityListRow {

    private Integer publishStatus;
    private Long id;
    private String title;
    private String summary;
    private Integer category;
    private Integer status;
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
