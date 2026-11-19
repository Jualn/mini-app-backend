package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import java.util.List;

/** Compatibility and owned batch entry points. New business producers use NotificationCreator. */
public interface NotificationAcceptance extends NotificationCreator {
    void acceptLegacy(NotifyPayload payload);
    void acceptBatch(List<NotifyPayload> payloads);
}
