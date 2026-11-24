package cn.jualn.miniapp.module.notify.bo;

import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import java.util.List;

public record NotificationPreferencesBO(String defaultVersion, List<Item> items) {
    public record Item(String category, String channel, boolean enabled, String source) { }
    /** Null enabled resets the override; omitted HTTP values are rejected before conversion. */
    public record Change(NotificationCategory category, NotificationChannel channel, Boolean enabled) { }
    public record Patch(List<Change> changes) { }
}
