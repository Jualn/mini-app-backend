package cn.jualn.miniapp.module.notify.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("notification_delivery")
public class NotificationDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long notificationId;
    private String channel;
    private String status;
    private Integer attemptCount;
    private String providerMessageId;
    private String resultCategory;
    private String providerErrorCode;
    private String lastErrorMessage;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime deliveredAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
