package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult;
import java.util.Map;

/** Application channel boundary; no provider response DTO or error-code interpretation leaks to Delivery. */
public interface WxNoticeDeliveryAdapter {
    String channel();
    boolean isDeliveryAvailable(NotifyType type);
    WxDeliveryTemplateSnapshot prepare(NotifyType type, Map<String, Object> frozen);
    boolean isFrozenAvailable(WxDeliveryTemplateSnapshot snapshot, NotifyType type);
    WxNoticeSendResult deliver(Long userId, WxDeliveryTemplateSnapshot snapshot, String notificationId, Runnable beforeProviderCall);
}
