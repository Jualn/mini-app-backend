package cn.jualn.miniapp.module.notify.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

public record NotificationSummaryVO(int unreadCount, String headCursor, int newCount, Preview latestNewNotification) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Preview(String id, String type, String title, String body,
                          NotificationItemVO.Target target, String createdAt) { }
}
