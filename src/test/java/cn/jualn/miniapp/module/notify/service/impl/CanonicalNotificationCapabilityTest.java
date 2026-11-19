package cn.jualn.miniapp.module.notify.service.impl;
import cn.jualn.miniapp.module.notify.service.inbox.PersonalNotificationInbox;
import cn.jualn.miniapp.module.notify.service.preference.NotificationPreferences;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCapabilityQueries;
import cn.jualn.miniapp.module.notify.converter.NotificationPersonalConverter;
import cn.jualn.miniapp.module.notify.bo.NotificationInboxBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPreferencesBO;


import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.service.preference.NotificationPreferenceBridgeService;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanonicalNotificationCapabilityTest {

    private final UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);
    private final WxMpNoticeSendService sendService = mock(WxMpNoticeSendService.class);
    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final PersonalNotificationInbox inbox = new PersonalNotificationInbox(notificationMapper,mock(RedisService.class));
    private final NotificationCapabilityQueries service = new NotificationCapabilityQueries(
                new cn.jualn.miniapp.module.user.service.UserNotificationIdentityService(userProfileMapper),cn.jualn.miniapp.module.notify.TestNotificationChannels.create(
                sendService,
                mock(cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService.class),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test void returnsAll36UniqueCombinationsAndNewTypeHasOnlyInAppCapability() {
        UserContext.setUserId(7L);
        var items = service.getCapabilities().items();
        org.junit.jupiter.api.Assertions.assertEquals(36,items.size());
        org.junit.jupiter.api.Assertions.assertEquals(36,items.stream().map(i -> i.notificationType()+":"+i.channel()).distinct().count());
        org.junit.jupiter.api.Assertions.assertEquals(12,items.stream().map(i -> i.notificationType()).distinct().count());
        var start = items.stream().filter(i -> "ACTIVITY_REGISTRATION_START_REMINDER".equals(i.notificationType())).toList();
        org.junit.jupiter.api.Assertions.assertEquals(3,start.size());
        assertTrue(start.stream().allMatch(i -> "ACTIVITY".equals(i.category())));
        assertTrue(start.stream().allMatch(i -> i.available() == "IN_APP".equals(i.channel())));
    }
    @Test void learningTaskLegacyProjectionUsesCanonicalTargetWithoutInventingPreferenceCategory() {
        UserContext.setUserId(7L);
        var notification=cn.jualn.miniapp.module.notify.entity.Notification.builder().id(123L).userId(7L).type(24)
                .targetId(401L).title("学习任务").content("说明").createdAt(java.time.LocalDateTime.of(2026,10,17,10,0)).build();
        when(notificationMapper.selectCanonicalInbox(7L,null,null,null,null,21)).thenReturn(java.util.List.of(notification));
        var item=new NotificationPersonalConverter().page(inbox.list(new NotificationInboxBO.Query(null,20,null,null))).items().get(0);
        org.junit.jupiter.api.Assertions.assertEquals("LEARNING_TASK_ASSIGNED",item.type());
        org.junit.jupiter.api.Assertions.assertEquals("LEARNING_TASK",item.target().type());
        org.junit.jupiter.api.Assertions.assertEquals("401",item.target().resourceId());
        org.junit.jupiter.api.Assertions.assertNull(item.category());
    }

    @Test
    void unconfiguredChannelsDoNotMisreportPermissionAsThePrimaryBlocker() {
        UserContext.setUserId(7L);
        when(userProfileMapper.selectMiniOpenid(7L)).thenReturn("mini-openid");
        when(userProfileMapper.selectMpOpenIdById(7L)).thenReturn("official-openid");
        when(sendService.isDeliveryAvailable(NotifyType.ACTIVITY_START_REMINDER)).thenReturn(false);

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
        when(sendService.isDeliveryAvailable(NotifyType.ACTIVITY_START_REMINDER)).thenReturn(true);

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
