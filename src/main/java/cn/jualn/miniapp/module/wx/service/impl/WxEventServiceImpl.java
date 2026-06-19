package cn.jualn.miniapp.module.wx.service.impl;

import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;
import cn.jualn.miniapp.module.wx.handler.WxEventHandler;
import cn.jualn.miniapp.module.wx.service.WxEventService;
import cn.jualn.miniapp.third.wx.config.WxAccountType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 微信服务号事件分发服务。
 * 将解密后的 XML 解析为事件对象，并根据消息类型路由到对应处理器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxEventServiceImpl implements WxEventService {

    private final XmlMapper xmlMapper;
    private final Map<String, WxEventHandler> handlers;

    /**
     * 处理微信回调 XML。
     *
     * @param xmlBody 微信回调解密后的 XML 文本
     * @return 固定返回 {@code success}，用于告知微信已接收
     */
    @Override
    public String handle(WxAccountType accountType, String xmlBody) {
        WxBaseMessage message;
        try {
            message = xmlMapper.readValue(xmlBody, WxBaseMessage.class);
            message.setRawXml(xmlBody);
        } catch (Exception e) {
            log.error("微信事件解析失败", e);
            return "success";
        }

        log.info("接收微信事件：accountType={}, msgType={}, message={}, fromUserName={}, eventKey={}",
                accountType, message.getMsgType(), message.getEvent(), message.getFromUserName(), message.getEventKey());

        String handlerKey = resolveKey(message);
        if (!StringUtils.hasText(handlerKey)) {
            log.warn("未识别的微信事件，accountType={}, msgType={}, message={}",
                    accountType, message.getMsgType(), message.getEvent());
            return "success";
        }

        WxEventHandler handler = handlers.get(handlerKey);
        if (handler == null) {
            log.warn("未找到微信事件处理器，handlerKey={}，直接跳过", handlerKey);
            return "success";
        }

        try {
            handler.handle(message);
            return "success";
        } catch (Exception e) {
            log.error("微信事件处理失败，handlerKey={}, fromUserName={}",
                    handlerKey, message.getFromUserName(), e);
        }

        return "success";
    }

    /**
     * 解析处理器键。
     *
     * @param message 微信事件消息
     * @return 处理器键（如 event:subscribe、msg:text），无法识别时返回 null
     */
    private String resolveKey(WxBaseMessage message) {
        if (message == null || !StringUtils.hasText(message.getMsgType())) {
            return null;
        }

        if ("event".equalsIgnoreCase(message.getMsgType())) {
            if (!StringUtils.hasText(message.getEvent())) {
                return null;
            }
            return "event:" + message.getEvent().toLowerCase(Locale.ROOT);
        }

        return "msg:" + message.getMsgType().toLowerCase(Locale.ROOT);
    }
}
