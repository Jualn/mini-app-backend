package cn.jualn.miniapp.module.wx.handler.ma;

import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;
import cn.jualn.miniapp.module.wx.dto.WxaMediaCheckMessage;
import cn.jualn.miniapp.module.wx.handler.WxEventHandler;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component("event:wxa_media_check")
public class WxaMediaCheckHandler implements WxEventHandler {

    private final XmlMapper xmlMapper;
    private final AuditService auditService;

    @Override
    public void handle(WxBaseMessage message) {
        WxaMediaCheckMessage mediaCheckMessage;
        try {
            mediaCheckMessage = xmlMapper.readValue(
                    message.getRawXml(),
                    WxaMediaCheckMessage.class
            );
        } catch (Exception e) {
            log.error("解析 wxa_media_check 回调失败，rawXml={}", message.getRawXml(), e);
            return;
        }

        auditService.handleWxMediaCallback(mediaCheckMessage);
    }
}
