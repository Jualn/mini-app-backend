package cn.jualn.miniapp.module.notify.bo;

import java.time.OffsetDateTime;
import java.util.List;

public record NotificationCapabilitiesBO(OffsetDateTime evaluatedAt, List<Item> items) {
    public record Item(String category, String notificationType, String channel, boolean available,
                       String permission, List<String> unavailableReasons) { }
}
