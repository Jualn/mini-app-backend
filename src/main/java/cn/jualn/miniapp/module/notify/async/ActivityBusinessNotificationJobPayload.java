package cn.jualn.miniapp.module.notify.async;

public record ActivityBusinessNotificationJobPayload(
        long activityId, String eventKey, String title, String content) {
}
