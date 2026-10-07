package cn.jualn.miniapp.module.activity.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("activity_registration")
public class ActivityRegistration {
    private Long contractVersion;
    private String formVersion;
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long activityId;
    private Long userId;
    private String formData;
    private Integer status;
    private LocalDateTime submittedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime invalidatedAt;
    private Long invalidatedBy;
    private String invalidReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
