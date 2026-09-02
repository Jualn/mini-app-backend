package cn.jualn.miniapp.module.notify.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminNotificationPlanItemVO {

    private String id;
    private Integer sourceType;
    private String sourceId;
    private Integer notifyType;
    private String title;
    private String content;
    private Integer scope;
    private String scene;
    private LocalDateTime sendAt;
    private Integer status;
    private String createdBy;
    private LocalDateTime createdAt;
}
