package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminActivityListVO {

    private String id;
    private String title;
    private String summary;
    private String category;
    private String status;
    private String auditStatus;
    private String organizer;
    private String location;
    private List<String> audienceCodes;
    private Boolean isPinned;
    private Long subscriberCount;
    private Integer capacity;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private Integer viewCount;
    private LocalDateTime updatedAt;
}
