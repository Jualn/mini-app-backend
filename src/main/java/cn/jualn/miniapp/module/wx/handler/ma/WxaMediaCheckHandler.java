package cn.jualn.miniapp.module.wx.handler.ma;

import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;
import cn.jualn.miniapp.module.wx.dto.WxaMediaCheckMessage;
import cn.jualn.miniapp.module.wx.handler.WxEventHandler;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@RequiredArgsConstructor
@Component("ma:event:wxa_media_check")
public class WxaMediaCheckHandler implements WxEventHandler {

    private final AuditService auditService;
    private final WxProperties wxProperties;

    @Override
    public void handle(WxBaseMessage message) {
        if (!(message instanceof WxaMediaCheckMessage mediaCheckMessage)) {
            throw new IllegalArgumentException("wxa_media_check 回调类型不匹配");
        }
        if (!StringUtils.hasText(mediaCheckMessage.getAppId())
                || !mediaCheckMessage.getAppId().equals(wxProperties.getMa().getAppId())) {
            throw new IllegalArgumentException("wxa_media_check 回调账号不匹配");
        }
        auditService.handleWxMediaCallback(mediaCheckMessage);
    }
}
