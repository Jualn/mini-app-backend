package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.payload.NotifyPayload;

/** Internal durable acceptance. Call inside the business transaction when effects must commit together. */
public interface NotificationCreator {
    enum Outcome { CREATED, DUPLICATE, SKIPPED }
    record Result(Outcome outcome, Long notificationId) { }
    Result create(NotifyPayload payload);
}
