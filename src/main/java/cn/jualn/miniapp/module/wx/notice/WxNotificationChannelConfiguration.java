package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.module.notify.channel.NotificationChannelAdapter;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class WxNotificationChannelConfiguration {
    @Bean NotificationChannelAdapter officialAccountNotificationChannel(WxMpNoticeSendService sender,ObjectMapper json) {
        return new WxNotificationChannelAdapter(WxMpNoticeSendService.CHANNEL,sender,sender::isDeliveryAvailable,json);
    }
    @Bean NotificationChannelAdapter miniProgramNotificationChannel(WxMiniNoticeSendService sender,ObjectMapper json) {
        return new WxNotificationChannelAdapter(WxMiniNoticeSendService.CHANNEL,sender,sender::isTemplateConfigured,json);
    }
}
