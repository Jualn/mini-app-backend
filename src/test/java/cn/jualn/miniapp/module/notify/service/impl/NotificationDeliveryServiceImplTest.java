package cn.jualn.miniapp.module.notify.service.impl;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.infrastructure.async.job.RetryableRemoteException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.entity.NotificationDelivery;
import cn.jualn.miniapp.module.notify.mapper.NotificationDeliveryMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceImplTest {
    @Mock NotificationDeliveryMapper deliveryMapper;
    @Mock NotificationMapper notificationMapper;
    @Mock JobService jobService;
    @Mock WxMpNoticeSendService sendService;
    @Mock cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService miniSendService;
    @Mock SettingService settingService;
    @Mock NotificationPreferenceMapper preferenceMapper;
    @Mock cn.jualn.miniapp.module.activity.service.ActivityDeliveryEligibility activityEligibility;

    private NotificationDeliveryServiceImpl service() {
        return new NotificationDeliveryServiceImpl(deliveryMapper,notificationMapper,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService.class),jobService,new ObjectMapper(),cn.jualn.miniapp.module.notify.TestNotificationChannels.create(sendService,miniSendService,new ObjectMapper()),settingService,preferenceMapper,null,new cn.jualn.miniapp.module.notify.service.NotificationEligibility(java.util.List.of(activityEligibility),org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper.class)));
    }

    @Test void miniResponseLossUsesSameUnknownRecoveryWithoutOfficialFallback() throws Exception {
        var snapshot = new cn.jualn.miniapp.module.wx.notice.WxDeliveryTemplateSnapshot(1,
                "WECHAT_MINI_PROGRAM", "ma-primary", "test", "fixture-ma", "MINI_PROGRAM_SUBSCRIBE",
                "mini", 1, "mini", 1, "fixture-mini-template", Map.of("thing1", "fixture"),
                "/pages/message/message?notificationId=7", null, "fixture-digest", "trial");
        when(deliveryMapper.selectDelivery(41L)).thenReturn(NotificationDelivery.builder().id(41L)
                .notificationId(7L).channel("WECHAT_MINI_PROGRAM").status("PENDING")
                .templateSnapshotVersion(1).templateSnapshot(new ObjectMapper().writeValueAsString(snapshot)).build());
        when(notificationMapper.selectById(7L)).thenReturn(Notification.builder().id(7L).userId(3L)
                .type(NotifyType.ACTIVITY_START_REMINDER.getCode()).inboxGeneration("CANONICAL").build());
        when(preferenceMapper.selectEnabled(3L, "ACTIVITY", "WECHAT_MINI_PROGRAM")).thenReturn(true);
        when(miniSendService.isFrozenAvailable(any(),any())).thenReturn(true);
        when(activityEligibility.isEligible(any(), any())).thenReturn(true);
        when(deliveryMapper.startProviderAttempt(eq(41L), any())).thenReturn(1);
        when(miniSendService.deliver(eq(3L), any(), eq("7"), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult.unknown();
        });
        service().execute(41L);
        verify(deliveryMapper).markTerminal(eq(41L), eq("UNKNOWN"), eq("remote"), eq(null), any());
        verify(deliveryMapper, never()).markRetryable(anyLong(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(sendService, settingService);
    }

    @Test
    void planDoesNotCreateWechatIntentWithoutConfiguredTemplate() {
        service().planWeChatDelivery(Notification.builder().id(7L).build(), NotifyType.ACTIVITY_REMIND, null);
        verify(deliveryMapper, never()).insert(any(NotificationDelivery.class));
        verify(jobService, never()).create(any());
    }

    @Test
    void recoveredProcessingDeliveryBecomesUnknownWithoutSecondProviderCall() {
        when(deliveryMapper.selectDelivery(41L)).thenReturn(pendingBuilder()
                .id(41L).status("PROCESSING").build());

        service().execute(41L);

        verify(deliveryMapper).markTerminal(eq(41L), eq("UNKNOWN"), eq("remote"), eq(null), any());
        verify(sendService, never()).deliver(anyLong(), any(), any(), any());
    }

    @Test
    void disabledChannelIsSkippedWithoutProviderAttempt() {
        NotificationDelivery delivery = pendingBuilder()
                .id(41L).notificationId(7L).status("PENDING").build();
        Notification notification = Notification.builder().id(7L).userId(3L)
                .type(NotifyType.ACTIVITY_REMIND.getCode()).title("t").content("c").build();
        when(deliveryMapper.selectDelivery(41L)).thenReturn(delivery);
        when(notificationMapper.selectById(7L)).thenReturn(notification);
        when(settingService.getSettingByUserId(3L)).thenReturn(UserSettingBO.builder()
                .notifyActivityRemind(false).build());

        service().execute(41L);

        verify(deliveryMapper).markTerminal(eq(41L), eq("SKIPPED"), eq("validation"),
                eq("channel_disabled"), any());
        verify(deliveryMapper, never()).recordProviderAttempt(anyLong(), any());
        verify(sendService, never()).deliver(anyLong(), any(), any(), any());
    }

    @Test
    void canonicalChannelRechecksCanonicalPreferenceInsteadOfLegacySetting() {
        NotificationDelivery delivery = pendingBuilder()
                .id(41L).notificationId(7L).status("PENDING").build();
        Notification notification = Notification.builder().id(7L).userId(3L)
                .inboxGeneration("CANONICAL").type(NotifyType.ACTIVITY_START_REMINDER.getCode())
                .title("t").content("c").build();
        when(deliveryMapper.selectDelivery(41L)).thenReturn(delivery);
        when(notificationMapper.selectById(7L)).thenReturn(notification);
        when(preferenceMapper.selectEnabled(3L, "ACTIVITY", "WECHAT_OFFICIAL_ACCOUNT"))
                .thenReturn(false);

        service().execute(41L);

        verify(deliveryMapper).markTerminal(eq(41L), eq("SKIPPED"), eq("validation"),
                eq("channel_disabled"), any());
        verify(settingService, never()).getSettingByUserId(anyLong());
        verify(sendService, never()).deliver(anyLong(), any(), any(), any());
    }

    @Test
    void existingDeliveryIsSkippedWhenTemplateIsUnavailable() {
        NotificationDelivery delivery = pendingBuilder()
                .id(41L).notificationId(7L).status("PENDING").build();
        Notification notification = Notification.builder().id(7L).userId(3L)
                .type(NotifyType.ACTIVITY_REMIND.getCode()).title("t").content("c").build();
        when(deliveryMapper.selectDelivery(41L)).thenReturn(delivery);
        when(notificationMapper.selectById(7L)).thenReturn(notification);
        when(settingService.getSettingByUserId(3L)).thenReturn(UserSettingBO.builder()
                .notifyActivityRemind(true).build());

        service().execute(41L);

        verify(deliveryMapper).markTerminal(eq(41L), eq("SKIPPED"), eq("validation"),
                eq("template_unavailable"), any());
        verify(deliveryMapper, never()).startProviderAttempt(anyLong(), any());
        verify(sendService, never()).deliver(anyLong(), any(), any(), any());
    }

    @Test
    void successfulProviderCallCountsDeliveryAttemptAndPersistsMessageId() {
        NotificationDelivery delivery = pendingBuilder()
                .id(41L).notificationId(7L).status("PENDING").build();
        Notification notification = Notification.builder().id(7L).userId(3L)
                .type(NotifyType.ACTIVITY_REMIND.getCode()).title("t").content("c")
                .contentSchemaVersion(1).contentPayload("{\"subjectTitle\":\"t\"}").build();
        when(deliveryMapper.selectDelivery(41L)).thenReturn(delivery);
        when(deliveryMapper.startProviderAttempt(eq(41L), any())).thenReturn(1);
        when(notificationMapper.selectById(7L)).thenReturn(notification);
        when(settingService.getSettingByUserId(3L)).thenReturn(UserSettingBO.builder()
                .notifyActivityRemind(true).build());
        when(sendService.isFrozenAvailable(any(),any())).thenReturn(true);
        when(activityEligibility.isEligible(any(), any())).thenReturn(true);
        MpSubscribeMessageResponse response = new MpSubscribeMessageResponse();
        response.setErrcode(0);
        response.setMsgId(99L);
        when(sendService.deliver(eq(3L), any(), eq("7"), any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(3).run();
                    return cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult.official(response);
                });

        service().execute(41L);

        verify(deliveryMapper).markDelivered(eq(41L), eq("99"), any());
    }

    @Test
    void responseLossBecomesUnknownAndDoesNotThrowForOrdinaryRetry() {
        arrangePendingEnabledDelivery();
        when(sendService.deliver(eq(3L), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult.unknown();
        });

        service().execute(41L);

        verify(deliveryMapper).markTerminal(eq(41L), eq("UNKNOWN"), eq("remote"), eq(null), any());
    }

    @Test
    void authoritativeSystemBusyReturnsDeliveryToPendingAndRetriesJob() {
        arrangePendingEnabledDelivery();
        when(sendService.deliver(eq(3L), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult.officialFailure(new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", "-1", "system busy"));
        });

        assertThrows(RetryableRemoteException.class, () -> service().execute(41L));

        verify(deliveryMapper).markRetryable(eq(41L), eq("remote"), eq("-1"), any());
    }

    @Test
    void authoritativePermanentProviderFailureIsTerminal() {
        arrangePendingEnabledDelivery();
        when(sendService.deliver(eq(3L), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult.officialFailure(new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", "40003", "invalid openid"));
        });

        service().execute(41L);

        verify(deliveryMapper).markTerminal(eq(41L), eq("FAILED"), eq("remote"), eq("40003"), any());
    }

    private void arrangePendingEnabledDelivery() {
        when(deliveryMapper.selectDelivery(41L)).thenReturn(pendingBuilder()
                .id(41L).notificationId(7L).status("PENDING").build());
        when(deliveryMapper.startProviderAttempt(eq(41L), any())).thenReturn(1);
        when(notificationMapper.selectById(7L)).thenReturn(Notification.builder().id(7L).userId(3L)
                .type(NotifyType.ACTIVITY_REMIND.getCode()).title("t").content("c").build());
        when(settingService.getSettingByUserId(3L)).thenReturn(UserSettingBO.builder()
                .notifyActivityRemind(true).build());
        when(sendService.isFrozenAvailable(any(),any())).thenReturn(true);
        when(activityEligibility.isEligible(any(), any())).thenReturn(true);
    }

    @Test void staleOldDeliverySkipsWithoutAttemptAndKeepsNotification() {
        when(deliveryMapper.selectDelivery(41L)).thenReturn(pendingBuilder().id(41L).notificationId(7L).status("PENDING").build());
        when(notificationMapper.selectById(7L)).thenReturn(Notification.builder().id(7L).userId(3L).type(4).build());
        when(settingService.getSettingByUserId(3L)).thenReturn(UserSettingBO.builder().notifyActivityRemind(true).build());
        when(sendService.isFrozenAvailable(any(),any())).thenReturn(true);
        service().execute(41L);
        verify(deliveryMapper).markTerminal(eq(41L),eq("SKIPPED"),eq("validation"),eq("business_ineligible"),any());
        verify(deliveryMapper,never()).startProviderAttempt(anyLong(),any());
        verify(sendService,never()).deliver(anyLong(),any(),any(),any());
        verify(notificationMapper,never()).deleteById(anyLong());
    }

    @Test void eligibilityLostDuringTemplatePreparationSkipsWithoutProviderAttempt() {
        when(deliveryMapper.selectDelivery(41L)).thenReturn(pendingBuilder().id(41L).notificationId(7L).status("PENDING").build());
        when(notificationMapper.selectById(7L)).thenReturn(Notification.builder().id(7L).userId(3L).type(4).build());
        when(settingService.getSettingByUserId(3L)).thenReturn(UserSettingBO.builder().notifyActivityRemind(true).build());
        when(sendService.isFrozenAvailable(any(),any())).thenReturn(true);
        when(activityEligibility.isEligible(any(),any())).thenReturn(true,false);
        when(sendService.deliver(anyLong(),any(),any(),any())).thenAnswer(i -> { i.<Runnable>getArgument(3).run(); throw new AssertionError("Provider must not be invoked"); });
        service().execute(41L);
        verify(deliveryMapper,never()).startProviderAttempt(anyLong(),any());
        verify(deliveryMapper).markTerminal(eq(41L),eq("SKIPPED"),eq("validation"),eq("business_ineligible"),any());
    }

    @Test
    void configuredLegacyTemplateCreatesDeliveryAndJob() {
        when(sendService.isDeliveryAvailable(any())).thenReturn(true);
        when(sendService.prepare(any(), any())).thenReturn(snapshot());
        doAnswer(invocation -> {
            invocation.<NotificationDelivery>getArgument(0).setId(41L);
            return 1;
        }).when(deliveryMapper).insert(any(NotificationDelivery.class));

        service().planWeChatDelivery(Notification.builder().id(7L).build(), NotifyType.ACTIVITY_REMIND, null);

        verify(deliveryMapper).insert(any(NotificationDelivery.class));
        verify(jobService).create(any(JobDefinition.class));
    }

    @Test
    void newlyDefinedNotificationTypesAreNotEnabledByLegacyTemplate() {
        service().planWeChatDelivery(Notification.builder().id(7L).build(), NotifyType.ACTIVITY_START_REMINDER, null);
        verify(deliveryMapper, never()).insert(any(NotificationDelivery.class));
        verify(jobService, never()).create(any());
    }

    @Test
    void canonicalActivityStartPlansInAppAndOfficialAccountIndependently() {
        when(sendService.isDeliveryAvailable(any())).thenReturn(true);
        when(sendService.prepare(any(), any())).thenReturn(snapshot());
        doAnswer(invocation -> {
            NotificationDelivery delivery = invocation.getArgument(0);
            if (NotificationChannel.WECHAT_OFFICIAL_ACCOUNT.name().equals(delivery.getChannel())) {
                delivery.setId(41L);
            }
            return 1;
        }).when(deliveryMapper).insert(any(NotificationDelivery.class));
        Notification notification = Notification.builder().id(7L)
                .contentSchemaVersion(1)
                .contentPayload("{\"activityId\":9,\"activityTitle\":\"志愿活动\",\"startTime\":\"2027-03-01T10:00:00\"}")
                .build();

        boolean visible = service().planCanonicalDeliveries(notification,
                NotifyType.ACTIVITY_START_REMINDER,
                java.util.Set.of(NotificationChannel.IN_APP, NotificationChannel.WECHAT_OFFICIAL_ACCOUNT));

        assertEquals(true, visible);
        ArgumentCaptor<NotificationDelivery> delivery = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper, org.mockito.Mockito.times(2)).insert(delivery.capture());
        assertEquals(java.util.Set.of("IN_APP", "WECHAT_OFFICIAL_ACCOUNT"), delivery.getAllValues().stream()
                .map(NotificationDelivery::getChannel).collect(java.util.stream.Collectors.toSet()));
        verify(jobService).create(any(JobDefinition.class));
    }

    @Test
    void emptySuccessBodyIsUnknownRatherThanDelivered() {
        arrangePendingEnabledDelivery();
        when(sendService.deliver(eq(3L), any(), any(), any())).thenAnswer(invocation -> { invocation.<Runnable>getArgument(3).run(); return cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult.unknown(); });
        service().execute(41L);
        verify(deliveryMapper).markTerminal(eq(41L), eq("UNKNOWN"), eq("remote"), eq(null), any());
        verify(deliveryMapper, never()).markDelivered(anyLong(), any(), any());
    }

    @Test
    void localFailureAfterProviderSuccessNeverSchedulesAnotherRemoteAttempt() {
        arrangePendingEnabledDelivery();
        MpSubscribeMessageResponse response = new MpSubscribeMessageResponse();
        response.setErrcode(0);
        when(sendService.deliver(eq(3L), any(), any(), any())).thenAnswer(invocation -> { invocation.<Runnable>getArgument(3).run(); return cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult.official(response); });
        when(deliveryMapper.markDelivered(eq(41L), any(), any())).thenThrow(new IllegalStateException("db write failed"));
        service().execute(41L);
        verify(deliveryMapper).markTerminal(eq(41L), eq("UNKNOWN"), eq("internal"), eq(null), any());
        verify(deliveryMapper, never()).markRetryable(anyLong(), any(), any(), any());
    }
    private static cn.jualn.miniapp.module.wx.notice.WxDeliveryTemplateSnapshot snapshot() {
        return new cn.jualn.miniapp.module.wx.notice.WxDeliveryTemplateSnapshot(1, "WECHAT_OFFICIAL_ACCOUNT", "mp-primary",
                "test", "fixture-mp", "OFFICIAL_ACCOUNT_SUBSCRIBE_BIZSEND", "legacy:activity_start", 1,
                "legacy:activity_start", 1, "fixture-template", Map.of("thing1", "frozen"),
                "/pages/message/message?notificationId=7", "fixture-ma", "fixture-digest");
    }
    private static NotificationDelivery.NotificationDeliveryBuilder pendingBuilder() {
        try { return NotificationDelivery.builder().channel("WECHAT_OFFICIAL_ACCOUNT").templateSnapshotVersion(1)
                .templateSnapshot(new ObjectMapper().writeValueAsString(snapshot())); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @Test void oldUnfrozenDeliveryTerminatesWithoutUsingCurrentTemplate() {
        when(deliveryMapper.selectDelivery(41L)).thenReturn(NotificationDelivery.builder().id(41L).status("PENDING")
                .channel("WECHAT_OFFICIAL_ACCOUNT").build());
        service().execute(41L);
        verify(deliveryMapper).markTerminal(eq(41L),eq("SKIPPED"),eq("validation"),eq("legacy_template_unfrozen"),any());
        org.mockito.Mockito.verifyNoInteractions(sendService, notificationMapper, jobService);
    }
    @Test void miniDeliveryCannotReachOfficialAccountAdapter() {
        when(deliveryMapper.selectDelivery(41L)).thenReturn(pendingBuilder().id(41L).status("PENDING")
                .channel("WECHAT_MINI_PROGRAM").build());
        service().execute(41L);
        verify(deliveryMapper).markTerminal(eq(41L),eq("FAILED"),eq("validation"),eq("invalid_template_snapshot"),any());
        org.mockito.Mockito.verifyNoInteractions(sendService, miniSendService, notificationMapper);
    }
    @Test void renderingFailureDoesNotRemoveInAppDeliveryOrCreateWechatJob() {
        when(sendService.isDeliveryAvailable(any())).thenReturn(true);
        when(sendService.prepare(any(),any())).thenThrow(new cn.jualn.miniapp.common.exception.BusinessException(
                ResultCode.WX_NOTICE_PAYLOAD_INVALID, "synthetic invalid field"));
        assertEquals(true,service().planCanonicalDeliveries(Notification.builder().id(7L).contentSchemaVersion(1)
                .contentPayload("{\"activityId\":9,\"activityTitle\":\"fixture\",\"startTime\":\"2027-03-01T10:00:00\"}").build(), NotifyType.ACTIVITY_START_REMINDER,
                java.util.Set.of(NotificationChannel.IN_APP, NotificationChannel.WECHAT_OFFICIAL_ACCOUNT)));
        ArgumentCaptor<NotificationDelivery> captured=ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper,org.mockito.Mockito.times(2)).insert(captured.capture());
        assertEquals("DELIVERED",captured.getAllValues().stream().filter(d->"IN_APP".equals(d.getChannel())).findFirst().orElseThrow().getStatus());
        assertEquals("FAILED",captured.getAllValues().stream().filter(d->"WECHAT_OFFICIAL_ACCOUNT".equals(d.getChannel())).findFirst().orElseThrow().getStatus());
        verify(jobService,never()).create(any());
    }

}
