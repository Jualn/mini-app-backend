package cn.jualn.miniapp.module.notify.vo;

import java.util.List;

public record NotificationChannelCapabilitiesVO(String evaluatedAt, List<Item> items) {
    public record Item(String category, String notificationType, String channel, boolean available,
                       String permission, List<String> unavailableReasons) {}
}
