package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.module.wx.dto.WxCallbackRequest;
import cn.jualn.miniapp.module.wx.service.WxEventService;
import cn.jualn.miniapp.module.wx.service.impl.WxCallbackServiceImpl;
import cn.jualn.miniapp.third.wx.config.WxAccountType;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.crypto.WxSignatureService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxCallbackServiceImplTest {

    private final WxEventService events = mock(WxEventService.class);
    private final WxSignatureService signatures = mock(WxSignatureService.class);
    private final WxProperties properties = new WxProperties(
            new WxProperties.Mp("mp-app", "secret", "mp-token", "aes", "https://test.jualn.cn"),
            new WxProperties.Ma("ma-app", "secret", "ma-token", "aes"));
    private final WxCallbackServiceImpl service = new WxCallbackServiceImpl(properties, events, signatures);

    @Test
    void receive_rejectsInvalidSignatureWithoutDispatch() {
        WxCallbackRequest request = new WxCallbackRequest("bad", null, "1", "nonce", null, null);
        when(signatures.verify("mp-token", "bad", "1", "nonce")).thenReturn(false);

        assertEquals("FAIL", service.receive(WxAccountType.MP, request, "<xml/>"));
        verify(events, never()).handle(org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void receive_doesNotAcknowledgeHandlerFailure() {
        WxCallbackRequest request = new WxCallbackRequest("ok", null, "1", "nonce", null, null);
        when(signatures.verify("mp-token", "ok", "1", "nonce")).thenReturn(true);
        when(events.handle(WxAccountType.MP, "<xml/>")).thenThrow(new IllegalStateException("temporary"));

        assertEquals("FAIL", service.receive(WxAccountType.MP, request, "<xml/>"));
    }

    @Test
    void receive_rejectsOversizedBodyBeforeSignatureOrParsing() {
        WxCallbackRequest request = new WxCallbackRequest("ok", null, "1", "nonce", null, null);
        assertEquals("FAIL", service.receive(WxAccountType.MP, request, "x".repeat(256 * 1024 + 1)));
        verify(events, never()).handle(org.mockito.ArgumentMatchers.any(), anyString());
    }
}
