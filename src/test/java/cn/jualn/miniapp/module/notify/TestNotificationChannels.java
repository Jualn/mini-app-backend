package cn.jualn.miniapp.module.notify;

import cn.jualn.miniapp.module.notify.channel.NotificationChannels;
import cn.jualn.miniapp.module.wx.notice.WxNotificationChannelAdapter;
import cn.jualn.miniapp.module.wx.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Uses the real channel boundary around existing provider doubles in mechanism fixtures. */
public final class TestNotificationChannels {
    private TestNotificationChannels() {}
    public static NotificationChannels create(WxMpNoticeSendService official,WxMiniNoticeSendService mini,ObjectMapper json) {
        return new NotificationChannels(List.of(
                new WxNotificationChannelAdapter(WxMpNoticeSendService.CHANNEL,official,official::isDeliveryAvailable,json),
                new WxNotificationChannelAdapter(WxMiniNoticeSendService.CHANNEL,mini,mini::isTemplateConfigured,json)));
    }
}
