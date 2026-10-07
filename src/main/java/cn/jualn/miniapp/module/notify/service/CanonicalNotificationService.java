package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.dto.request.CanonicalNotificationQuery;
import cn.jualn.miniapp.module.notify.dto.request.UpdateNotificationPreferencesRequest;
import cn.jualn.miniapp.module.notify.vo.CanonicalNotificationPageVO;
import cn.jualn.miniapp.module.notify.vo.NotificationChannelCapabilitiesVO;
import cn.jualn.miniapp.module.notify.vo.NotificationPreferencesVO;

public interface CanonicalNotificationService {
    NotificationPreferencesVO getPreferences();
    NotificationPreferencesVO updatePreferences(UpdateNotificationPreferencesRequest request);
    NotificationChannelCapabilitiesVO getCapabilities();
    CanonicalNotificationPageVO list(CanonicalNotificationQuery query);
    long unreadCount();
    void markRead(String notificationId);
    void markAllRead();
}
