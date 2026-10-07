package cn.jualn.miniapp.module.notify.vo;

import java.util.List;

public record NotificationPreferencesVO(String defaultVersion, List<Item> items) {
    public record Item(String category, String channel, boolean enabled, String source) {}
}
