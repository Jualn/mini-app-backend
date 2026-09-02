package cn.jualn.miniapp.module.notify.bo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminNotificationPlanItemBO {

    private Long id;
    private Integer sourceType;
    private Long sourceId;
    private Integer notifyType;
    private String title;
    private String content;
    private Integer scope;
    private String scene;
    private LocalDateTime sendAt;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createdAt;
}
