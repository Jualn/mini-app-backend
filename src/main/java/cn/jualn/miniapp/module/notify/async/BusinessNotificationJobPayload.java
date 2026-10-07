package cn.jualn.miniapp.module.notify.async;

public record BusinessNotificationJobPayload(
        Integer targetType,
        Long targetId,
        String eventIdentity,
        Integer notificationType,
        String recipientScope,
        String title,
        String content,
        cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot snapshot) {
    public BusinessNotificationJobPayload(Integer targetType, Long targetId, String eventIdentity,
            Integer notificationType, String recipientScope, String title, String content) {
        this(targetType, targetId, eventIdentity, notificationType, recipientScope, title, content, null);
    }
}
