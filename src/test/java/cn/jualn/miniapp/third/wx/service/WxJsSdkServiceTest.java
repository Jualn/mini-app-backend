package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.wx.service.WxJsSdkService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxJsSdkServiceTest {

    private final WxClient client = mock(WxClient.class);
    private final WxProperties properties = new WxProperties(
            new WxProperties.Mp("mp-app", "secret", "token", "aes", "https://test.jualn.cn"),
            new WxProperties.Ma("ma-app", "secret", "token", "aes"));
    private final WxJsSdkService service = new WxJsSdkService(client, properties);

    @Test
    void createConfig_acceptsOnlyConfiguredHttpsOrigin() {
        when(client.getMpJsApiTicket(false)).thenReturn("ticket");

        assertEquals("mp-app", service.createConfig("https://test.jualn.cn/h5/page?a=1").getAppId());
        verify(client).getMpJsApiTicket(false);
    }

    @Test
    void createConfig_rejectsForeignOriginAndFragmentBeforeFetchingTicket() {
        assertThrows(BusinessException.class,
                () -> service.createConfig("https://evil.example/h5/page"));
        assertThrows(BusinessException.class,
                () -> service.createConfig("https://test.jualn.cn/h5/page#fragment"));
        verify(client, never()).getMpJsApiTicket(false);
    }
}
