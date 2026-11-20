package cn.jualn.miniapp.module.notify.service.impl;
import cn.jualn.miniapp.module.notify.channel.NotificationChannelAdapter;
import cn.jualn.miniapp.module.notify.channel.NotificationChannels;
import cn.jualn.miniapp.module.notify.channel.NotificationSendResult;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.entity.NotificationDelivery;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.mapper.NotificationDeliveryMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.service.NotificationEligibility;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
class NotificationDeliveryServiceImplTest {
    final NotificationDeliveryMapper deliveries=mock(NotificationDeliveryMapper.class);
    final NotificationMapper notifications=mock(NotificationMapper.class);
    final NotificationPreferenceMapper preferences=mock(NotificationPreferenceMapper.class);
    final NotificationChannelAdapter adapter=mock(NotificationChannelAdapter.class);
    final JobService jobs=mock(JobService.class);
    final ObjectMapper json=new ObjectMapper();
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final NotificationEligibility eligibility=mock(NotificationEligibility.class);
    NotificationDeliveryServiceImpl service;
    @BeforeEach void setup()throws Exception{
        when(adapter.channel()).thenReturn("WECHAT_MINI_PROGRAM");when(adapter.isEnabled()).thenReturn(true);
        when(adapter.isFrozenAvailable(any(),any())).thenReturn(true);
        when(preferences.selectEnabled(2L,"COMMENT","WECHAT_MINI_PROGRAM")).thenReturn(true);
        when(eligibility.isEligible(any(),any())).thenReturn(true);
        when(notifications.selectById(7L)).thenReturn(Notification.builder().id(7L).userId(2L).type(NotifyType.POST_COMMENTED.getCode()).contentSchemaVersion(1).contentPayload("{}").build());
        when(deliveries.selectDelivery(41L)).thenReturn(NotificationDelivery.builder().id(41L).notificationId(7L).channel("WECHAT_MINI_PROGRAM").status("PENDING").templateSnapshotVersion(1).templateSnapshot("{\"schemaVersion\":1,\"channel\":\"WECHAT_MINI_PROGRAM\"}").build());
        when(deliveries.startProviderAttempt(eq(41L),any())).thenReturn(1);
        service=new NotificationDeliveryServiceImpl(deliveries,notifications,meters,mock(NotificationInboxService.class),jobs,json,new NotificationChannels(List.of(adapter)),preferences,null,eligibility);
    }
    @AfterEach void stop(){meters.close();}
    void result(NotificationSendResult.Outcome outcome){when(adapter.deliver(eq(2L),any(),eq("7"),any())).thenAnswer(call->{call.<Runnable>getArgument(3).run();return new NotificationSendResult(outcome,outcome==NotificationSendResult.Outcome.REJECTED_PERMANENT?"43101":null,null);});}
    @Test void acceptedIsPersistedWithoutMarkingRead(){result(NotificationSendResult.Outcome.ACCEPTED);service.execute(41L);verify(deliveries).markDelivered(eq(41L),isNull(),any());verify(notifications,never()).markCanonicalRead(anyLong(),anyLong());}
    @Test void unknownStopsRetryAndDoesNotBecomePending(){result(NotificationSendResult.Outcome.UNKNOWN);service.execute(41L);verify(deliveries).markTerminal(eq(41L),eq("UNKNOWN"),eq("remote"),isNull(),any());verify(deliveries,never()).markRetryable(anyLong(),any(),any(),any());}
    @Test void definiteProviderRefusalIsTerminal(){result(NotificationSendResult.Outcome.REJECTED_PERMANENT);service.execute(41L);verify(deliveries).markTerminal(eq(41L),eq("FAILED"),eq("remote"),eq("43101"),any());}
    @Test void incompletePreviousAttemptBecomesUnknownWithoutResending(){when(deliveries.selectDelivery(41L)).thenReturn(NotificationDelivery.builder().id(41L).status("PROCESSING").build());service.execute(41L);verify(deliveries).markTerminal(eq(41L),eq("UNKNOWN"),any(),any(),any());verify(adapter,never()).deliver(any(),any(),any(),any());}
    @Test void currentPersonalChoiceIsCheckedAgain(){when(preferences.selectEnabled(2L,"COMMENT","WECHAT_MINI_PROGRAM")).thenReturn(false);service.execute(41L);verify(deliveries).markTerminal(eq(41L),eq("SKIPPED"),eq("validation"),eq("channel_disabled"),any());verify(deliveries,never()).startProviderAttempt(anyLong(),any());}
    @Test void businessEligibilityLostImmediatelyBeforeClaimDoesNotSend(){when(eligibility.isEligible(any(),any())).thenReturn(true,false);result(NotificationSendResult.Outcome.ACCEPTED);service.execute(41L);verify(deliveries).markTerminal(eq(41L),eq("SKIPPED"),eq("validation"),eq("business_ineligible"),any());verify(deliveries,never()).startProviderAttempt(anyLong(),any());}
    @Test void unavailableTemplateDoesNotCreateAnIntentOrJob(){service.planCanonicalDeliveries(Notification.builder().id(7L).type(19).build(),NotifyType.POST_COMMENTED,Set.of(NotificationChannel.WECHAT_MINI_PROGRAM));verify(deliveries,never()).insert(any(NotificationDelivery.class));verifyNoInteractions(jobs);}
    @Test void disabledProductSkipsBeforeReadingPayload(){when(adapter.isEnabled()).thenReturn(false);service.execute(41L);verify(deliveries).markTerminal(eq(41L),eq("SKIPPED"),eq("validation"),eq("channel_unavailable"),any());verify(notifications,never()).selectById(any());}
}
