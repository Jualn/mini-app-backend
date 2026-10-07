package cn.jualn.miniapp.module.wx.handler;

import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;
import cn.jualn.miniapp.module.wx.service.WxSubscribeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 已关注用户扫码事件处理器。
 */
@Slf4j
@Component("mp:event:scan")
@RequiredArgsConstructor
public class ScanHandler implements WxEventHandler {

    private final WxSubscribeService wxSubscribeService;

    /**
     * 处理已关注用户扫码事件。
     *
     * @param event 微信事件消息
     */
    @Override
    public void handle(WxBaseMessage event) {
        log.debug("处理微信 scan 事件");
        wxSubscribeService.onScan(event);
    }
}

