package cn.jualn.miniapp.module.notify.payload;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.notify.payload.data.NoticeData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Channel-neutral personal notification command with Notify-owned content facts. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyPayload {

    /**
     * 接收者
     */
    private Long receiverId;
    /**
     * 触发者，null=系统
     */
    private Long senderId;
    /**
     * notification.type
     */
    private NotifyType type;
    /**
     * 站内通知标题
     */
    private String title;
    /**
     * 站内通知内容
     */
    private String content;
    /**
     * 跳转目标类型
     */
    private TargetType targetType;
    /**
     * 跳转目标ID
     */
    private Long targetId;
    /** 稳定业务来源；需要幂等的通知必须提供。 */
    private String sourceKey;
    /** Internal execution evidence, excluded from all public notification projections. */
    private Long reminderPlanId;
    /** Exact business anchor frozen by the reminder producer, never reconstructed by a renderer. */
    private java.time.LocalDateTime businessTime;
    private String businessLocation;
    private java.time.LocalDateTime registrationDeadlineTime;
    private java.time.LocalDateTime occurredAt;
    /** True only after the mentor answer publication guard has accepted the answer. */
    private boolean mentorAnswer;
    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot snapshot;
    /**
     * 通知内容事实；持久 JSON 沿用 wxData 字段名，以读取既有载荷
     */
    @com.fasterxml.jackson.annotation.JsonProperty("wxData")
    private NoticeData contentData;
    /** Internal binding evidence, never exposed as template fields or a public response. */
    private String recipientPersonId;
    private String recipientBindingId;
    /** Optional upper bound only; never grants a channel or overrides user preferences/capability. */
    private java.util.Set<cn.jualn.miniapp.module.notify.model.NotificationChannel> allowedChannels;
}
