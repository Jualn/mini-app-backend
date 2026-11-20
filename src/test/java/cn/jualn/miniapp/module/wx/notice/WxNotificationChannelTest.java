package cn.jualn.miniapp.module.wx.notice;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
class WxNotificationChannelTest {
    @Test void noUserIdentityMeansNoProviderAttempt(){
        var users=mock(cn.jualn.miniapp.module.user.service.UserWechatIdentityService.class);var client=mock(cn.jualn.miniapp.third.wx.client.WxClient.class);
        var accounts=new cn.jualn.miniapp.third.wx.config.WxProperties(null,new cn.jualn.miniapp.third.wx.config.WxProperties.Ma("fixture-ma","fixture-secret",null,null));
        var service=new cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService(client,accounts,users,WxNotificationTemplateTest.registry(WxNotificationTemplateTest.fixture()),new WxNotificationTemplateRenderer());
        var snapshot=service.prepareDelivery(cn.jualn.miniapp.module.notify.enums.NotifyType.ACTIVITY_START_REMINDER,WxNotificationTemplateTest.frozen());
        assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,()->service.sendFrozen(2L,snapshot,"7",()->fail("must not attempt")));verifyNoInteractions(client);
    }
}
