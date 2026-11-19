package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCapabilityQueries;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserNotificationIdentityService;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageRequest;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageResponse;
import cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WxNotificationChannelTest {
    private final WxClient client = mock(WxClient.class);
    private final UserWechatIdentityService users = mock(UserWechatIdentityService.class);
    private final WxProperties accounts = new WxProperties(
            new WxProperties.Mp("fixture-mp", "fixture-secret", null, null, null),
            new WxProperties.Ma("fixture-ma", "fixture-secret", null, null));

    private WxNotificationTemplateProperties miniConfiguration() {
        var p = WxNotificationTemplateTest.fixture();
        p.getOfficialAccount().setEnabled(false);
        var d = p.getDefinitions().get("fixture");
        d.setChannel("WECHAT_MINI_PROGRAM"); d.setAccountRef("ma-primary");
        d.setProduct("MINI_PROGRAM_SUBSCRIBE");
        var b = p.getBindings().get("start");
        b.setChannel(d.getChannel()); b.setAccountRef(d.getAccountRef());
        return p;
    }

    private WxMiniNoticeSendService mini(WxNotificationTemplateRegistry registry) {
        return new WxMiniNoticeSendService(client, accounts, users, registry, new WxNotificationTemplateRenderer());
    }

    @Test void miniBindingPlansFrozenDeliveryAndExistingJobWhileDisabledChannelPlansNothing() throws Exception {
        var p = miniConfiguration();
        var deliveries = mock(cn.jualn.miniapp.module.notify.mapper.NotificationDeliveryMapper.class);
        var jobs = mock(cn.jualn.miniapp.infrastructure.async.job.JobService.class);
        var json = new ObjectMapper();
        var notification = cn.jualn.miniapp.module.notify.entity.Notification.builder().id(7L).userId(3L)
                .contentSchemaVersion(1).contentPayload(json.writeValueAsString(WxNotificationTemplateTest.frozen())).build();
        doAnswer(invocation -> {
            invocation.<cn.jualn.miniapp.module.notify.entity.NotificationDelivery>getArgument(0).setId(41L);
            return 1;
        }).when(deliveries).insert(any(cn.jualn.miniapp.module.notify.entity.NotificationDelivery.class));
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            for (boolean enabled : java.util.List.of(true, false)) {
                p.getMiniProgram().setEnabled(enabled);
                var registry = WxNotificationTemplateTest.registry(p);
                var official = new WxMpNoticeSendService(client, accounts, users, registry, new WxNotificationTemplateRenderer());
                var channels = cn.jualn.miniapp.module.notify.TestNotificationChannels.create(official, mini(registry), json);
                var service = new cn.jualn.miniapp.module.notify.service.impl.NotificationDeliveryServiceImpl(
                        deliveries, mock(cn.jualn.miniapp.module.notify.mapper.NotificationMapper.class), meters,
                        mock(cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService.class), jobs, json,
                        channels, mock(cn.jualn.miniapp.module.setting.service.SettingService.class),
                        mock(cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper.class), null,
                        mock(cn.jualn.miniapp.module.notify.service.NotificationEligibility.class));
                service.planCanonicalDeliveries(notification, NotifyType.ACTIVITY_START_REMINDER,
                        java.util.Set.of(cn.jualn.miniapp.module.notify.model.NotificationChannel.WECHAT_MINI_PROGRAM));
            }
        } finally { meters.close(); }
        var capture = org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.entity.NotificationDelivery.class);
        verify(deliveries).insert(capture.capture());
        var planned = capture.getValue();
        assertEquals("WECHAT_MINI_PROGRAM", planned.getChannel());
        assertEquals("PENDING", planned.getStatus());
        var snapshot = json.readTree(planned.getTemplateSnapshot());
        assertEquals("fixture-only", snapshot.path("templateId").asText());
        assertTrue(snapshot.path("pagePath").asText().contains("notificationId=7"));
        verify(jobs).create(any());
        verifyNoInteractions(client, users);
    }

    @Test void anotherBusinessTemplateUsesTheSameAdapterWithoutNewDispatchCode() {
        var p = miniConfiguration();
        var json = new ObjectMapper();
        var definition = json.convertValue(p.getDefinitions().get("fixture"), WxNotificationTemplateProperties.Definition.class);
        definition.setTemplateId("fixture-public-event");
        p.getDefinitions().put("public-event", definition);
        var binding = json.convertValue(p.getBindings().get("start"), WxNotificationTemplateProperties.Binding.class);
        binding.setNotificationType("PUBLIC_EVENT_START_REMINDER");
        binding.setDefinitionRef("public-event"); binding.setJumpTarget("PUBLIC_EVENT_DETAIL");
        p.getBindings().put("public-event-start", binding);
        var service = mini(WxNotificationTemplateTest.registry(p));
        assertTrue(service.isDeliveryAvailable(NotifyType.PUBLIC_EVENT_START_REMINDER));
        var snapshot = service.prepareDelivery(NotifyType.PUBLIC_EVENT_START_REMINDER, WxNotificationTemplateTest.frozen());
        assertEquals("fixture-public-event", snapshot.templateId());
        assertTrue(snapshot.pagePath().contains("publicEventId=9"));
        assertFalse(service.isDeliveryAvailable(NotifyType.PUBLIC_EVENT_CANCELLED));
        verifyNoInteractions(client, users);
    }

    @Test void repositoryProfilesBindChannelDefaultsAndEnvironmentOverrides() throws Exception {
        for (String profile : java.util.List.of("local", "dev", "prod")) {
            var env = new org.springframework.mock.env.MockEnvironment();
            var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
            if (!"local".equals(profile)) loader.load("profile", new org.springframework.core.io.ClassPathResource(
                    "application-" + profile + ".yaml")).forEach(s -> env.getPropertySources().addLast(s));
            loader.load("base", new org.springframework.core.io.ClassPathResource("application.yaml"))
                    .forEach(s -> env.getPropertySources().addLast(s));
            var binder = org.springframework.boot.context.properties.bind.Binder.get(env);
            var type = org.springframework.boot.context.properties.bind.Bindable.of(WxNotificationTemplateProperties.class);
            var defaults = binder.bind("wx.notification", type).get();
            assertEquals("prod".equals(profile), defaults.getMiniProgram().isEnabled());
            assertFalse(defaults.getOfficialAccount().isEnabled());
            env.setProperty("WX_NOTIFICATION_MINI_PROGRAM_ENABLED", "false");
            env.setProperty("WX_NOTIFICATION_OFFICIAL_ACCOUNT_ENABLED", "true");
            var overridden = org.springframework.boot.context.properties.bind.Binder.get(env).bind("wx.notification", type).get();
            assertFalse(overridden.getMiniProgram().isEnabled());
            assertTrue(overridden.getOfficialAccount().isEnabled());
        }
    }

    @Test void disablingChannelRejectsPreparedSnapshotBeforeIdentityOrProviderAccess() {
        var p = miniConfiguration();
        var running = mini(WxNotificationTemplateTest.registry(p));
        var snapshot = running.prepareDelivery(NotifyType.ACTIVITY_START_REMINDER, WxNotificationTemplateTest.frozen());
        p.getMiniProgram().setEnabled(false);
        var disabled = mini(WxNotificationTemplateTest.registry(p));
        assertFalse(disabled.isDeliveryAvailable(NotifyType.ACTIVITY_START_REMINDER));
        assertFalse(disabled.isFrozenDeliveryAvailable(snapshot));
        assertThrows(BusinessException.class, () -> disabled.sendFrozen(3L, snapshot, "7", () -> fail("must not attempt")));
        assertTrue(running.isDeliveryAvailable(NotifyType.ACTIVITY_START_REMINDER)); // Restart-bound configuration.
        verifyNoInteractions(client, users);
    }

    @Test void miniUsesOwnIdentityAndPreservesProviderRejectionAndUnknownOutcome() {
        var service = mini(WxNotificationTemplateTest.registry(miniConfiguration()));
        var snapshot = service.prepareDelivery(NotifyType.ACTIVITY_START_REMINDER, WxNotificationTemplateTest.frozen());
        when(users.getMiniOpenid(3L)).thenReturn("fixture-user");
        var denied = new MiniSubscribeMessageResponse(); denied.setErrcode(43101);
        when(client.sendMiniSubscribeMessage(any())).thenReturn(denied);
        Runnable before = mock(Runnable.class);
        var result = service.deliver(3L, snapshot, "7", before);
        assertEquals(WxNoticeSendResult.Outcome.REJECTED_PERMANENT, result.outcome());
        assertEquals("43101", result.providerCode());
        var request = org.mockito.ArgumentCaptor.forClass(MiniSubscribeMessageRequest.class);
        verify(client).sendMiniSubscribeMessage(request.capture());
        assertEquals("fixture-user", request.getValue().toUser());
        assertEquals("fixture-only", request.getValue().templateId());
        assertEquals("subpkg_activity/pages/detail/detail?activityId=9&notificationId=7", request.getValue().page());
        verify(before).run();
        when(client.sendMiniSubscribeMessage(any())).thenThrow(
                new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", "unknown"));
        assertEquals(WxNoticeSendResult.Outcome.UNKNOWN, service.deliver(3L, snapshot, "7", () -> {}).outcome());
        verify(client, times(2)).sendMiniSubscribeMessage(any());
        verify(users, never()).getOfficialAccountOpenid(any());
        verify(client, never()).sendMpSubscribeMessage(any());
    }

    @Test void missingMiniIdentityCannotReachProvider() {
        var service = mini(WxNotificationTemplateTest.registry(miniConfiguration()));
        var snapshot = service.prepareDelivery(NotifyType.ACTIVITY_START_REMINDER, WxNotificationTemplateTest.frozen());
        assertThrows(BusinessException.class, () -> service.sendFrozen(3L, snapshot, "7", () -> fail("must not attempt")));
        verifyNoInteractions(client);
    }

    @Test void officialSwitchBlocksLegacyDirectSendAndSubscriptionEntry() {
        var legacy = new WxMpNoticeTemplateProperties();
        var t = new WxMpNoticeTemplateProperties.Template();
        t.setType("reply"); t.setEnabled(true); t.setTemplateId("fixture-reply");
        var field = new WxMpNoticeTemplateProperties.Field(); field.setSource("title");
        t.setFields(java.util.Map.of("thing1", field));
        legacy.setNoticeTemplates(java.util.Map.of("reply", t));
        var p = new WxNotificationTemplateProperties();
        var registry = new WxNotificationTemplateRegistry(legacy, p);
        var official = new WxMpNoticeSendService(client, accounts, users, registry, new WxNotificationTemplateRenderer());
        assertTrue(registry.listSubscribeVisibleTemplates().isEmpty());
        assertFalse(official.isTemplateAvailable(WxMpNoticeType.REPLY));
        assertThrows(BusinessException.class, () -> official.send(3L, WxMpNoticeType.REPLY, java.util.Map.of("title", "fixture")));
        verifyNoInteractions(client, users);
    }

    @Test void configuredMiniReportsProviderDecisionAndIndependentDisabledOfficialChannel() {
        var registry = WxNotificationTemplateTest.registry(miniConfiguration());
        var official = new WxMpNoticeSendService(client, accounts, users, registry, new WxNotificationTemplateRenderer());
        var channels = cn.jualn.miniapp.module.notify.TestNotificationChannels.create(official, mini(registry), new ObjectMapper());
        var profiles = mock(UserProfileMapper.class);
        when(profiles.selectMiniOpenid(3L)).thenReturn("fixture-user");
        when(profiles.selectMpOpenIdById(3L)).thenReturn("fixture-mp-user");
        UserContext.setUserId(3L);
        try {
            var items = new NotificationCapabilityQueries(new UserNotificationIdentityService(profiles), channels).getCapabilities().items();
            var start = items.stream().filter(i -> "ACTIVITY_START_REMINDER".equals(i.notificationType())
                    && "WECHAT_MINI_PROGRAM".equals(i.channel())).findFirst().orElseThrow();
            assertTrue(start.available());
            assertEquals("PROVIDER_VERIFIED_AT_SEND", start.permission());
            assertTrue(start.unavailableReasons().isEmpty());
            assertTrue(items.stream().filter(i -> "WECHAT_OFFICIAL_ACCOUNT".equals(i.channel()))
                    .allMatch(i -> !i.available() && i.unavailableReasons().contains("CHANNEL_DISABLED")));
            assertTrue(items.stream().filter(i -> "IN_APP".equals(i.channel())).allMatch(i -> i.available()));
        } finally { UserContext.clear(); }
    }
}
