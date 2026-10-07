package cn.jualn.miniapp.module.notify.payload;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.wx.notice.data.NoticeData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 个人通知 Payload（所有通知路径最终都用这个发）。
 * <p>
 * wxData 用 NoticeData 接口约束类型，各通知类型有对应的 record 实现：
 * COMMENT → CommentNoticeData
 * REPLY   → ReplyNoticeData
 * LIKE    → LikeNoticeData
 * ACTIVITY→ ActivityRemindNoticeData
 * EXAM    → ExamRemindNoticeData
 * AUDIT   → AuditResultNoticeData
 * SYSTEM  → BroadcastNoticeData
 */
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
    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot snapshot;
    /**
     * 微信模板变量值，按类型使用不同的 NoticeData 实现
     */
    private NoticeData wxData;
}
