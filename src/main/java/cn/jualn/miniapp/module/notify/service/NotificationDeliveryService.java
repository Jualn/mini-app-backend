package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.entity.Notification;

import java.util.Map;
import java.util.Set;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;

public interface NotificationDeliveryService {
    /** Plans configured external channels in the notification transaction; null allows both WeChat channels. */
    void planWeChatDelivery(Notification notification, NotifyType type, Set<NotificationChannel> allowedChannels);

    /** Plans the currently available canonical channels in the notification transaction. */
    boolean planCanonicalDeliveries(Notification notification, NotifyType type,
                                    Set<NotificationChannel> enabledChannels);

    /** Executes one persisted delivery intent. Remote I/O is outside a database transaction. */
    void execute(long deliveryId);

    void execute(long deliveryId, cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext context);

    Map<String, Object> readFrozenContent(Notification notification);
}
