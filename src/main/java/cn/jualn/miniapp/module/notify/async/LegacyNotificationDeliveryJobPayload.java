package cn.jualn.miniapp.module.notify.async;

import cn.jualn.miniapp.third.wx.notice.WxMpNoticeType;

import java.util.Map;

/** Compatibility reader for v1 jobs created before notification_delivery existed. */
public record LegacyNotificationDeliveryJobPayload(long notificationId, long receiverId,
                                                    WxMpNoticeType noticeType, Map<String, Object> data) {
}
