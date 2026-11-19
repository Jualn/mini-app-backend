package cn.jualn.miniapp.module.notify.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** Minimal replay barrier; never contains message content or raw source identifiers. */
@Data
public class NotificationSourceReceipt {
    private String sourceDigest;
    private String identityDigest;
    private String contentDigest;
    private Long notificationId;
    private LocalDateTime originalCreatedAt;
    private LocalDateTime disposedAt;
}
