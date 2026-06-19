package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.MpQrCodeCreateResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WxBindServiceTest {

    @Mock
    private WxClient wxClient;

    @Mock
    private RedisService redisService;

    @Mock
    private UserProfileMapper userProfileMapper;

    @Test
    void createBindQr_shouldCacheSceneAndBuildResponse() {
        WxBindService wxBindService = new WxBindService(wxClient, redisService, userProfileMapper);
        MpQrCodeCreateResponse qrResponse = new MpQrCodeCreateResponse();
        qrResponse.setTicket("ticket-123");
        when(wxClient.createQrSceneTicket(anyString(), eq(600))).thenReturn(qrResponse);

        LocalDateTime before = LocalDateTime.now();
        WxBindService.BindQrInfo qrInfo = wxBindService.createBindQr(42L);
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisService).set(keyCaptor.capture(), valueCaptor.capture(), durationCaptor.capture());
        verify(wxClient).createQrSceneTicket(eq(qrInfo.getScene()), eq(600));

        assertTrue(qrInfo.getScene().matches("bind_42_[0-9a-f]{8}"));
        assertEquals("ticket-123", qrInfo.getTicket());
        assertTrue(qrInfo.getQrUrl().contains("ticket-123"));
        assertEquals(RedisKeyConstant.wxBindScene(qrInfo.getScene()), keyCaptor.getValue());
        assertEquals("42", valueCaptor.getValue());
        assertEquals(Duration.ofSeconds(600), durationCaptor.getValue());
        assertTrue(qrInfo.getExpireAt().isAfter(before.plusSeconds(590)));
        assertTrue(qrInfo.getExpireAt().isBefore(after.plusSeconds(610)));
    }

    @Test
    void handleScanBind_shouldUpdateMpOpenidWhenSceneAndUserExist() {
        WxBindService wxBindService = new WxBindService(wxClient, redisService, userProfileMapper);
        String scene = "bind_42_a1b2c3d4";
        when(redisService.getString(RedisKeyConstant.wxBindScene(scene))).thenReturn("42");
        UserProfile userProfile = UserProfile.builder()
                .id(42L)
                .mpOpenid(null)
                .build();
        when(userProfileMapper.selectById(42L)).thenReturn(userProfile);
        when(userProfileMapper.updateById(any(UserProfile.class))).thenReturn(1);

        wxBindService.handleScanBind(scene, "mp_openid_1", "scan");

        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileMapper).updateById(profileCaptor.capture());
        assertEquals("mp_openid_1", profileCaptor.getValue().getMpOpenid());
    }

    @Test
    void handleScanBind_shouldSkipWhenSceneMissing() {
        WxBindService wxBindService = new WxBindService(wxClient, redisService, userProfileMapper);
        when(redisService.getString(anyString())).thenReturn(null);

        wxBindService.handleScanBind("bind_42_missing", "mp_openid_1", "scan");

        verifyNoInteractions(userProfileMapper);
        verify(redisService).getString(RedisKeyConstant.wxBindScene("bind_42_missing"));
        verify(userProfileMapper, never()).selectById(anyLong());
    }
}
