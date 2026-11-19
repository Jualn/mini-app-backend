package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.ActivityNotificationSource;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.reminder.ReminderSubjectStatus;
import cn.jualn.miniapp.module.notify.service.impl.NotifyServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReminderFanOutBoundaryTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void notificationOwnerDeduplicatesHistoricalStartBeforeBusinessSource(boolean historical) {
        var notifications = mock(NotificationMapper.class);
        var plans = mock(NotifyPlanMapper.class);
        var enrollment = mock(ActivityEnrollmentService.class);
        var status = mock(ReminderSubjectStatus.class);
        var acceptance = mock(NotificationAcceptance.class);
        var tx = mock(TransactionTemplate.class);
        var anchor = LocalDateTime.of(2026, 10, 1, 15, 0);
        var plan = NotifyPlan.builder().id(20L).status(3).sourceType(1).sourceId(8L)
                .recipientUpperUserId(7L).recipientScope("SUBSCRIBERS").scope(0)
                .notifyType(NotifyType.ACTIVITY_START_REMINDER.getCode()).subjectStartsAt(anchor)
                .title("frozen title").content("frozen content").build();
        when(plans.selectById(20L)).thenReturn(plan);
        when(status.sourceType()).thenReturn(1);
        when(status.isCurrentAndEligible(any(),any())).thenReturn(true);
        when(status.lockCurrentAndEligible(any(),any())).thenReturn(true);
        when(enrollment.listEnrolledUserIds(8L,0L,100)).thenReturn(List.of(7L));
        when(notifications.selectHistoricalStartOccurrence(7L,TargetType.ACTIVITY.getCode(),8L,
                "2026-10-01 15:00:00")).thenReturn(historical ? 99L : null);
        when(tx.execute(any())).thenAnswer(call -> {
            TransactionCallback<?> work = call.getArgument(0);
            return work.doInTransaction(new SimpleTransactionStatus());
        });
        doAnswer(call -> {
            List<cn.jualn.miniapp.module.notify.payload.NotifyPayload> batch = call.getArgument(0);
            assertEquals(historical ? 0 : 1,batch.size());
            if (!historical) {
                assertEquals(anchor,batch.get(0).getBusinessTime());
                assertEquals("frozen title",batch.get(0).getTitle());
                assertEquals(ReminderOccurrence.sourceKey(plan,7),batch.get(0).getSourceKey());
            }
            return null;
        }).when(acceptance).acceptBatch(anyList());
        var service = new NotifyServiceImpl(notifications,new SimpleMeterRegistry(),plans,null,
                List.of(new ActivityNotificationSource(enrollment)),null,null,null,tx,acceptance,
                List.of(status),null);
        service.broadcastPlanFanOut(20L);
        verify(acceptance).acceptBatch(anyList());
        verify(notifications).selectHistoricalStartOccurrence(7L,TargetType.ACTIVITY.getCode(),8L,
                "2026-10-01 15:00:00");
    }
}
