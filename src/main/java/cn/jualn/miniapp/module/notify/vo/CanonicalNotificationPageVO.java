package cn.jualn.miniapp.module.notify.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalNotificationPageVO(List<Item> items, String nextCursor) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String notificationId, String type, String category, String title, String content,
                       Target target, boolean isRead, String createdAt) {}

    public record Target(String type, String resourceId) {}
}
