package cn.jualn.miniapp.module.wx.handler;

import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;
import cn.jualn.miniapp.third.wx.service.WxSubscribeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 微信 subscribe 事件处理器。
 */
@Slf4j
@Component("event:subscribe")
@RequiredArgsConstructor
public class SubscribeHandler implements WxEventHandler {

    private final WxSubscribeService wxSubscribeService;


    /**
     * 处理用户关注事件。
     *
     * @param event 微信事件消息
     */
    @Override
    public void handle(WxBaseMessage event) {
        log.info("处理微信 subscribe 事件，fromUserName={}, eventKey={}", event.getFromUserName(), event.getEventKey());
//        wxSubscribeService.onSubscribe(event);
    }
}
