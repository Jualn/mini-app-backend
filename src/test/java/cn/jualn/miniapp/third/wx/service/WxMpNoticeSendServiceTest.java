package cn.jualn.miniapp.third.wx.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
class WxMpNoticeSendServiceTest {
    @Test void firstReleaseServiceAccountCannotBeEnabledByLegacyTemplateConfiguration(){
        var client=mock(cn.jualn.miniapp.third.wx.client.WxClient.class);
        var users=mock(cn.jualn.miniapp.module.user.service.UserWechatIdentityService.class);
        var registry=new cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateRegistry(new cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateProperties(),null);
        var service=new cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService(client,mock(cn.jualn.miniapp.third.wx.config.WxProperties.class),users,registry,new cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateRenderer());
        assertFalse(service.isChannelEnabled());assertTrue(registry.listSubscribeVisibleTemplates().isEmpty());
        assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,()->service.send(2L,cn.jualn.miniapp.module.wx.notice.WxMpNoticeType.REPLY,java.util.Map.of("title","fixture")));
        verifyNoInteractions(client,users);
    }
}
