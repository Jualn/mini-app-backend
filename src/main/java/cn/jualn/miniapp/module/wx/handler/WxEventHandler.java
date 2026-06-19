package cn.jualn.miniapp.module.wx.handler;

import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;

/**
 * 微信事件处理器接口。
 */
public interface WxEventHandler {

    /**
     * 处理微信事件。
     *
     * @param event 微信事件消息
     */
    void handle(WxBaseMessage event);
}
