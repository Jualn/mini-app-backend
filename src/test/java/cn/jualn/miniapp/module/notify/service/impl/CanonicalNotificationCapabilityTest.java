package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.service.NotificationPreferenceBridgeService;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import cn.jualn.miniapp.third.wx.notice.WxMpNoticeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanonicalNotificationCapabilityTest {

    private final UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);
    private final WxMpNoticeSendService sendService = mock(WxMpNoticeSendService.class);
    private final CanonicalNotificationServiceImpl service = new CanonicalNotificationServiceImpl(
            mock(NotificationPreferenceMapper.class),
            mock(NotificationMapper.class),
            mock(RedisService.class),
            mock(NotificationPreferenceBridgeService.class),
            userProfileMapper,
            sendService);

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void unconfiguredChannelsDoNotMisreportPermissionAsThePrimaryBlocker() {
        UserContext.setUserId(7L);
        when(userProfileMapper.selectMiniOpenid(7L)).thenReturn("mini-openid");
        when(userProfileMapper.selectMpOpenIdById(7L)).thenReturn("official-openid");
        when(sendService.isNotificationDeliveryAvailable(WxMpNoticeType.ACTIVITY_START)).thenReturn(false);

        var capabilities = service.getCapabilities();

        assertTrue(capabilities.items().stream()
                .filter(item -> !"IN_APP".equals(item.channel()))
                .allMatch(item -> item.unavailableReasons().equals(java.util.List.of("NOT_CONFIGURED"))));
        assertFalse(capabilities.items().stream()
                .flatMap(item -> item.unavailableReasons().stream())
                .anyMatch("PERMISSION_UNKNOWN"::equals));
    }

    @Test
    void configuredActivityStartUsesProviderVerificationAtSend() {
        UserContext.setUserId(7L);
        when(userProfileMapper.selectMiniOpenid(7L)).thenReturn("mini-openid");
        when(userProfileMapper.selectMpOpenIdById(7L)).thenReturn("official-openid");
        when(sendService.isNotificationDeliveryAvailable(WxMpNoticeType.ACTIVITY_START)).thenReturn(true);

        var capability = service.getCapabilities().items().stream()
                .filter(item -> "ACTIVITY_START_REMINDER".equals(item.notificationType()))
                .filter(item -> "WECHAT_OFFICIAL_ACCOUNT".equals(item.channel()))
                .findFirst().orElseThrow();

        assertTrue(capability.available());
        assertTrue(capability.unavailableReasons().isEmpty());
        assertTrue("PROVIDER_VERIFIED_AT_SEND".equals(capability.permission()));
    }

    @Test
    void missingOfficialAccountIdentityIsReportedSeparately() {
        UserContext.setUserId(7L);
        when(userProfileMapper.selectMiniOpenid(7L)).thenReturn("mini-openid");
        when(userProfileMapper.selectMpOpenIdById(7L)).thenReturn(null);

        var capabilities = service.getCapabilities();

        assertTrue(capabilities.items().stream()
                .filter(item -> "WECHAT_OFFICIAL_ACCOUNT".equals(item.channel()))
                .allMatch(item -> item.unavailableReasons().contains("IDENTITY_REQUIRED")));
        assertTrue(capabilities.items().stream()
                .filter(item -> "WECHAT_MINI_PROGRAM".equals(item.channel()))
                .noneMatch(item -> item.unavailableReasons().contains("IDENTITY_REQUIRED")));
    }
}
