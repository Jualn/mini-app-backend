package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import java.util.List;

/** Owned producer and batch entry points. New business producers use NotificationCreator. */
public interface NotificationAcceptance extends NotificationCreator {
    void accept(NotifyPayload payload);
    void acceptBatch(List<NotifyPayload> payloads);
}
