package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.enums.NotifyType;
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
    @Mock SettingService settingService;
    @Mock NotificationPreferenceMapper preferenceMapper;

    private NotificationDeliveryServiceImpl service() {
        return new NotificationDeliveryServiceImpl(deliveryMapper, notificationMapper,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotificationInboxService.class), jobService,
                new ObjectMapper(), sendService, settingService, preferenceMapper, null);
    }

    @Test
    void planDoesNotCreateWechatIntentWithoutConfiguredTemplate() {
        service().planWeChatDelivery(Notification.builder().id(7L).build(), NotifyType.ACTIVITY_REMIND);
        verify(deliveryMapper, never()).insert(any(NotificationDelivery.class));
        verify(jobService, never()).create(any());
    }

    @Test
    void recoveredProcessingDeliveryBecomesUnknownWithoutSecondProviderCall() {
        when(deliveryMapper.selectDelivery(41L)).thenReturn(NotificationDelivery.builder()
                .id(41L).status("PROCESSING").build());

        service().execute(41L);

        verify(deliveryMapper).markTerminal(eq(41L), eq("UNKNOWN"), eq("remote"), eq(null), any());
        verify(sendService, never()).send(anyLong(), any(), any(), any());
    }

    @Test
    void disabledChannelIsSkippedWithoutProviderAttempt() {
        NotificationDelivery delivery = NotificationDelivery.builder()
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
        verify(sendService, never()).send(anyLong(), any(), any(), any());
    }

    @Test
    void canonicalChannelRechecksCanonicalPreferenceInsteadOfLegacySetting() {
        NotificationDelivery delivery = NotificationDelivery.builder()
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
        verify(sendService, never()).send(anyLong(), any(), any(), any());
    }

    @Test
    void existingDeliveryIsSkippedWhenTemplateIsUnavailable() {
        NotificationDelivery delivery = NotificationDelivery.builder()
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
        verify(sendService, never()).send(anyLong(), any(), any(), any());
    }

    @Test
    void successfulProviderCallCountsDeliveryAttemptAndPersistsMessageId() {
        NotificationDelivery delivery = NotificationDelivery.builder()
                .id(41L).notificationId(7L).status("PENDING").build();
        Notification notification = Notification.builder().id(7L).userId(3L)
                .type(NotifyType.ACTIVITY_REMIND.getCode()).title("t").content("c")
                .contentSchemaVersion(1).contentPayload("{\"subjectTitle\":\"t\"}").build();
        when(deliveryMapper.selectDelivery(41L)).thenReturn(delivery);
        when(deliveryMapper.startProviderAttempt(eq(41L), any())).thenReturn(1);
        when(notificationMapper.selectById(7L)).thenReturn(notification);
        when(settingService.getSettingByUserId(3L)).thenReturn(UserSettingBO.builder()
                .notifyActivityRemind(true).build());
        when(sendService.isNotificationDeliveryAvailable(any())).thenReturn(true);
        MpSubscribeMessageResponse response = new MpSubscribeMessageResponse();
        response.setErrcode(0);
        response.setMsgId(99L);
        when(sendService.send(eq(3L), any(), eq(Map.of("subjectTitle", "t", "notificationId", "7")), any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(3).run();
                    return response;
                });

        service().execute(41L);

        verify(deliveryMapper).markDelivered(eq(41L), eq("99"), any());
    }

    @Test
    void responseLossBecomesUnknownAndDoesNotThrowForOrdinaryRetry() {
        arrangePendingEnabledDelivery();
        when(sendService.send(eq(3L), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            throw new WebClientException("timeout") { };
        });

        service().execute(41L);

        verify(deliveryMapper).markTerminal(eq(41L), eq("UNKNOWN"), eq("remote"), eq(null), any());
    }

    @Test
    void authoritativeSystemBusyReturnsDeliveryToPendingAndRetriesJob() {
        arrangePendingEnabledDelivery();
        when(sendService.send(eq(3L), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", "-1", "system busy");
        });

        assertThrows(RetryableRemoteException.class, () -> service().execute(41L));

        verify(deliveryMapper).markRetryable(eq(41L), eq("remote"), eq("-1"), any());
    }

    @Test
    void authoritativePermanentProviderFailureIsTerminal() {
        arrangePendingEnabledDelivery();
        when(sendService.send(eq(3L), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", "40003", "invalid openid");
        });

        service().execute(41L);

        verify(deliveryMapper).markTerminal(eq(41L), eq("FAILED"), eq("remote"), eq("40003"), any());
    }

    private void arrangePendingEnabledDelivery() {
        when(deliveryMapper.selectDelivery(41L)).thenReturn(NotificationDelivery.builder()
                .id(41L).notificationId(7L).status("PENDING").build());
        when(deliveryMapper.startProviderAttempt(eq(41L), any())).thenReturn(1);
        when(notificationMapper.selectById(7L)).thenReturn(Notification.builder().id(7L).userId(3L)
                .type(NotifyType.ACTIVITY_REMIND.getCode()).title("t").content("c").build());
        when(settingService.getSettingByUserId(3L)).thenReturn(UserSettingBO.builder()
                .notifyActivityRemind(true).build());
        when(sendService.isNotificationDeliveryAvailable(any())).thenReturn(true);
    }

    @Test
    void configuredLegacyTemplateCreatesDeliveryAndJob() {
        when(sendService.isNotificationDeliveryAvailable(any())).thenReturn(true);
        doAnswer(invocation -> {
            invocation.<NotificationDelivery>getArgument(0).setId(41L);
            return 1;
        }).when(deliveryMapper).insert(any(NotificationDelivery.class));

        service().planWeChatDelivery(Notification.builder().id(7L).build(), NotifyType.ACTIVITY_REMIND);

        verify(deliveryMapper).insert(any(NotificationDelivery.class));
        verify(jobService).create(any(JobDefinition.class));
    }

    @Test
    void newlyDefinedNotificationTypesAreNotEnabledByLegacyTemplate() {
        service().planWeChatDelivery(Notification.builder().id(7L).build(), NotifyType.ACTIVITY_START_REMINDER);
        verify(deliveryMapper, never()).insert(any(NotificationDelivery.class));
        verify(jobService, never()).create(any());
    }

    @Test
    void canonicalActivityStartPlansInAppAndOfficialAccountIndependently() {
        when(sendService.isNotificationDeliveryAvailable(any())).thenReturn(true);
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
        when(sendService.send(eq(3L), any(), any(), any())).thenReturn(new MpSubscribeMessageResponse());
        service().execute(41L);
        verify(deliveryMapper).markTerminal(eq(41L), eq("UNKNOWN"), eq("remote"), eq(null), any());
        verify(deliveryMapper, never()).markDelivered(anyLong(), any(), any());
    }

    @Test
    void localFailureAfterProviderSuccessNeverSchedulesAnotherRemoteAttempt() {
        arrangePendingEnabledDelivery();
        MpSubscribeMessageResponse response = new MpSubscribeMessageResponse();
        response.setErrcode(0);
        when(sendService.send(eq(3L), any(), any(), any())).thenReturn(response);
        when(deliveryMapper.markDelivered(eq(41L), any(), any())).thenThrow(new IllegalStateException("db write failed"));
        service().execute(41L);
        verify(deliveryMapper).markTerminal(eq(41L), eq("UNKNOWN"), eq("internal"), eq(null), any());
        verify(deliveryMapper, never()).markRetryable(anyLong(), any(), any(), any());
    }
}
