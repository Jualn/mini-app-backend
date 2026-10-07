package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.wx.service.WxMpOauthService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MpOauthAccessTokenResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxMpOauthServiceTest {

    private final WxClient client = mock(WxClient.class);
    private final RedisService redis = mock(RedisService.class);
    private final UserService users = mock(UserService.class);
    private final WxProperties properties = new WxProperties(
            new WxProperties.Mp("mp-app", "secret", "token", "aes", "https://test.jualn.cn"),
            new WxProperties.Ma("ma-app", "secret", "token", "aes"));
    private final WxMpOauthService service = new WxMpOauthService(client, properties, redis, users);

    @Test
    void callback_persistsResolvedIdentityBeforeBindingAndStoresReplayableResult() {
        String state = "state-1";
        String stateKey = RedisKeyConstant.wxMpOauthState(state);
        when(redis.getString(stateKey)).thenReturn("bind:42", "bind:42");
        when(redis.setIfAbsent(eq(RedisKeyConstant.wxMpOauthClaim(state)), anyString(), eq(Duration.ofSeconds(30))))
                .thenReturn(true);
        MpOauthAccessTokenResponse response = new MpOauthAccessTokenResponse();
        response.setOpenid("openid-1");
        when(client.getMpOauthAccessToken("code-1")).thenReturn(response);

        assertEquals("https://test.jualn.cn/h5/service-subscribe/index.html?mode=bind-success",
                service.handleCallback("code-1", state));

        verify(users).bindOfficialAccountIdentity(42L, "openid-1");
        verify(redis, atLeastOnce()).setRequired(eq(stateKey), anyString(), eq(Duration.ofMinutes(10)));
        verify(redis).deleteIfValueMatches(eq(RedisKeyConstant.wxMpOauthClaim(state)), anyString());
    }

    @Test
    void callback_replaysStoredRedirectWithoutReusingProviderCode() {
        String state = "state-2";
        when(redis.getString(RedisKeyConstant.wxMpOauthState(state)))
                .thenReturn("done:https://test.jualn.cn/h5/service-subscribe/index.html?mode=unbound");

        assertEquals("https://test.jualn.cn/h5/service-subscribe/index.html?mode=unbound",
                service.handleCallback("already-used-code", state));

        verify(client, never()).getMpOauthAccessToken(anyString());
        verify(redis, never()).setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(30)));
    }
}
