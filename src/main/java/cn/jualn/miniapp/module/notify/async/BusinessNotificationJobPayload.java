package cn.jualn.miniapp.module.notify.async;

public record BusinessNotificationJobPayload(
        Integer targetType,
        Long targetId,
        String eventIdentity,
        Integer notificationType,
        String recipientScope,
        String title,
        String content,
        cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot snapshot,
        java.time.LocalDateTime occurredAt) {
    public BusinessNotificationJobPayload(Integer targetType, Long targetId, String eventIdentity, Integer notificationType,
            String recipientScope, String title, String content, cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot snapshot) {
        this(targetType,targetId,eventIdentity,notificationType,recipientScope,title,content,snapshot,null);
    }
    public BusinessNotificationJobPayload(Integer targetType, Long targetId, String eventIdentity,
            Integer notificationType, String recipientScope, String title, String content) {
        this(targetType, targetId, eventIdentity, notificationType, recipientScope, title, content, null);
    }
}
