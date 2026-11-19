package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageRequest;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse;
import cn.jualn.miniapp.module.wx.notice.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WxMpNoticeSendServiceTest {
    private final WxClient client = mock(WxClient.class);
    private final UserWechatIdentityService users = mock(UserWechatIdentityService.class);

    @Test void notificationDeliveryRequiresRecoverableEntryAndPreservesOriginatingId() {
        when(users.getOfficialAccountOpenid(7L)).thenReturn("synthetic-openid");
        var response = new MpSubscribeMessageResponse(); response.setErrcode(0);
        when(client.sendMpSubscribeMessage(any())).thenReturn(response);
        var properties = new WxMpNoticeTemplateProperties();
        var template = new WxMpNoticeTemplateProperties.Template();
        template.setType("reply"); template.setEnabled(true); template.setTemplateId("synthetic-template");
        template.setPagePath("pages/community/detail?id={targetId}&notificationId=wrong");
        var field = new WxMpNoticeTemplateProperties.Field(); field.setSource("title"); field.setRequired(true);
        template.setFields(Map.of("thing1", field)); properties.setNoticeTemplates(Map.of("reply", template));
        var wx = new WxProperties(new WxProperties.Mp("synthetic-mp", "synthetic-secret", null, null, null), new WxProperties.Ma("synthetic-app", "synthetic-secret", "synthetic-token", "synthetic-aes"));
        var service = new WxMpNoticeSendService(client, wx, users, enabledRegistry(properties), new WxNotificationTemplateRenderer());
        assertTrue(service.isNotificationDeliveryAvailable(WxMpNoticeType.REPLY));
        assertFalse(service(true).isNotificationDeliveryAvailable(WxMpNoticeType.REPLY));
        service.send(7L, WxMpNoticeType.REPLY, Map.of("title", "t", "targetId", "42", "notificationId", "7"));
        var request = ArgumentCaptor.forClass(MpSubscribeMessageRequest.class);
        verify(client).sendMpSubscribeMessage(request.capture());
        assertEquals("pages/community/detail?id=42&notificationId=7", request.getValue().getMiniprogram().getPagePath());
        assertThrows(BusinessException.class, () -> service(true).send(7L, WxMpNoticeType.REPLY, Map.of("title", "t", "notificationId", "7")));
    }

    @Test
    void configuredLegacyMessageReachesProviderWithoutInventedLocalPermission() {
        when(users.getOfficialAccountOpenid(7L)).thenReturn("synthetic-openid");
        var response = new MpSubscribeMessageResponse();
        response.setErrcode(0);
        var prepared = new AtomicBoolean();
        when(client.sendMpSubscribeMessage(any())).thenAnswer(invocation -> {
            assertTrue(prepared.get());
            return response;
        });
        var service = service(true);
        assertTrue(service.isTemplateAvailable(WxMpNoticeType.REPLY));
        assertSame(response, service.send(7L, WxMpNoticeType.REPLY, Map.of("title", "内容"), () -> prepared.set(true)));
        var request = ArgumentCaptor.forClass(MpSubscribeMessageRequest.class);
        verify(client).sendMpSubscribeMessage(request.capture());
        assertEquals("synthetic-openid", request.getValue().getToUser());
        assertNull(request.getValue().getMiniprogram());
        assertEquals("内容", request.getValue().getData().get("thing1").getValue());
    }

    @Test
    void missingIdentityAndDisabledTemplateNeverCallProvider() {
        assertThrows(BusinessException.class, () -> service(true).send(7L, WxMpNoticeType.REPLY, Map.of("title", "内容")));
        assertThrows(BusinessException.class, () -> service(false).send(7L, WxMpNoticeType.REPLY, Map.of("title", "内容")));
        verifyNoInteractions(client);
    }

    @Test
    void unknownResponseAndExplicitRejectionCannotBecomeSuccess() {
        when(users.getOfficialAccountOpenid(7L)).thenReturn("synthetic-openid");
        var service = service(true);
        when(client.sendMpSubscribeMessage(any())).thenReturn(new MpSubscribeMessageResponse());
        var unknown = assertThrows(ExternalServiceException.class,
                () -> service.send(7L, WxMpNoticeType.REPLY, Map.of("title", "内容")));
        assertNull(unknown.getProviderCode());
        var rejected = new MpSubscribeMessageResponse();
        rejected.setErrcode(40003);
        when(client.sendMpSubscribeMessage(any())).thenReturn(rejected);
        var failure = assertThrows(ExternalServiceException.class,
                () -> service.send(7L, WxMpNoticeType.REPLY, Map.of("title", "内容")));
        assertEquals("40003", failure.getProviderCode());
    }

    private WxMpNoticeSendService service(boolean enabled) {
        var properties = new WxMpNoticeTemplateProperties();
        var template = new WxMpNoticeTemplateProperties.Template();
        template.setType("reply");
        template.setEnabled(enabled);
        template.setTemplateId("synthetic-template");
        var field = new WxMpNoticeTemplateProperties.Field();
        field.setSource("title");
        field.setRequired(true);
        template.setFields(Map.of("thing1", field));
        properties.setNoticeTemplates(Map.of("reply", template));
        return new WxMpNoticeSendService(client, mock(WxProperties.class), users,
                enabledRegistry(properties), new WxNotificationTemplateRenderer());
    }
    @Test void frozenDeliveryRetainsTemplateAndContentAcrossConfigVersionChange() {
        var old=freezeService("old-id",true,"fixture-mp");
        var snapshot=old.prepareDelivery("ACTIVITY_START_REMINDER",WxMpNoticeType.ACTIVITY_START,
                Map.of("notificationId","7","title","frozen-title"));
        verifyNoInteractions(users,client);
        var changed=freezeService("new-id",true,"fixture-mp");
        assertTrue(changed.isFrozenDeliveryAvailable(snapshot));
        when(users.getOfficialAccountOpenid(7L)).thenReturn("fixture-user");
        var response=new MpSubscribeMessageResponse();response.setErrcode(0);when(client.sendMpSubscribeMessage(any())).thenReturn(response);
        changed.sendFrozen(7L,snapshot,"7",()->{});
        var capture=ArgumentCaptor.forClass(MpSubscribeMessageRequest.class);verify(client).sendMpSubscribeMessage(capture.capture());
        assertEquals("old-id",capture.getValue().getTemplateId());assertEquals("frozen-title",capture.getValue().getData().get("thing1").getValue());
        assertFalse(freezeService("new-id",false,"fixture-mp").isFrozenDeliveryAvailable(snapshot));
        assertFalse(freezeService("new-id",true,"different-account").isFrozenDeliveryAvailable(snapshot));
        assertThrows(BusinessException.class,()->changed.sendFrozen(7L,snapshot,"8",()->{}));
    }
    private WxMpNoticeSendService freezeService(String templateId,boolean enabled,String appId) {
        var p=new WxMpNoticeTemplateProperties();var t=new WxMpNoticeTemplateProperties.Template();
        t.setType("activity_start");t.setEnabled(enabled);t.setTemplateId(templateId);t.setVersion("new-id".equals(templateId)?2:1);t.setPagePath("/pages/message/message");
        var field=new WxMpNoticeTemplateProperties.Field();field.setSource("title");field.setRequired(true);t.setFields(Map.of("thing1",field));
        p.setNoticeTemplates(Map.of("activity-start",t));
        var wx=new WxProperties(new WxProperties.Mp(appId,"fixture-secret",null,null,null),new WxProperties.Ma("fixture-ma","fixture-secret",null,null));
        return new WxMpNoticeSendService(client,wx,users,enabledRegistry(p),new WxNotificationTemplateRenderer());
    }

    private WxNotificationTemplateRegistry enabledRegistry(WxMpNoticeTemplateProperties source) {
        var channels = new cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateProperties();
        channels.getOfficialAccount().setEnabled(true);
        return new WxNotificationTemplateRegistry(source, channels);
    }

}
