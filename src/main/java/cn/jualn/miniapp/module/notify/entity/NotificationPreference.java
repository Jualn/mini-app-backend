package cn.jualn.miniapp.module.notify.entity;

import lombok.Data;

@Data
public class NotificationPreference {
    private Long userId;
    private String category;
    private String channel;
    private Boolean enabled;
    private String source;
}
