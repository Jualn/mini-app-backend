package cn.jualn.miniapp.module.notify.bo;

import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import java.time.LocalDateTime;
import java.util.List;

/** Compatibility inbox facts; the HTTP boundary owns aliases and string representations. */
public final class NotificationInboxBO {
    private NotificationInboxBO() { }
    public record Query(String cursor, Integer pageSize, NotificationCategory category, Boolean isRead) { }
    public record Page(List<Item> items, String nextCursor) { }
    public record Item(Long notificationId, Integer type, String category, String title, String content,
                       Target target, boolean isRead, LocalDateTime createdAt) { }
    public record Target(String type, Long resourceId) { }
}
