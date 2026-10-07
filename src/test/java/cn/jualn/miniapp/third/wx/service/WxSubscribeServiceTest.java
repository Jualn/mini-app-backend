package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.module.wx.service.WxBindService;
import cn.jualn.miniapp.module.wx.service.WxSubscribeService;
import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WxSubscribeServiceTest {

    @Mock
    private WxBindService wxBindService;

    private WxSubscribeService wxSubscribeService;

    @BeforeEach
    void setUp() {
        wxSubscribeService = new WxSubscribeService(wxBindService);
    }

    @Test
    void onSubscribe_shouldUseSceneWhenQrSceneEventKey() {
        WxBaseMessage event = WxBaseMessage.builder()
                .fromUserName("mp_openid_1")
                .eventKey("qrscene_bind_10_a1b2c3d4")
                .build();

        wxSubscribeService.onSubscribe(event);

        verify(wxBindService).handleScanBind("bind_10_a1b2c3d4", "mp_openid_1", "subscribe");
        verify(wxBindService, never()).handleDirectSubscribe("mp_openid_1");
    }

    @Test
    void onSubscribe_shouldHandleDirectSubscribeWhenNoEventKey() {
        WxBaseMessage event = WxBaseMessage.builder()
                .fromUserName("mp_openid_2")
                .eventKey(null)
                .build();

        wxSubscribeService.onSubscribe(event);

        verify(wxBindService).handleDirectSubscribe("mp_openid_2");
    }

    @Test
    void onScan_shouldUseOriginalScene() {
        WxBaseMessage event = WxBaseMessage.builder()
                .fromUserName("mp_openid_3")
                .eventKey("bind_20_xx00yy11")
                .build();

        wxSubscribeService.onScan(event);

        verify(wxBindService).handleScanBind("bind_20_xx00yy11", "mp_openid_3", "scan");
    }
}

