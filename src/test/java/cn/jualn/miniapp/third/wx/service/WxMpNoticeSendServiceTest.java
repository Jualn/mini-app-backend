package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageRequest;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse;
import cn.jualn.miniapp.third.wx.notice.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WxMpNoticeSendServiceTest {
    private final WxClient client = mock(WxClient.class);
    private final UserService users = mock(UserService.class);

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
        var wx = new WxProperties(null, new WxProperties.Ma("synthetic-app", "synthetic-secret", "synthetic-token", "synthetic-aes"));
        var service = new WxMpNoticeSendService(client, wx, users, new WxMpNoticeTemplateRegistry(properties), new WxMpNoticeFieldRenderer());
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
                new WxMpNoticeTemplateRegistry(properties), new WxMpNoticeFieldRenderer());
    }
}
