package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.activity.service.ActivityDeliveryEligibility;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.service.ActivityReminderSubjectStatus;
import cn.jualn.miniapp.module.notify.entity.*;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityDeliveryEligibilityTest {
    final ActivityReminderSubjectStatus owner=mock(ActivityReminderSubjectStatus.class);
    final NotifyPlanMapper plans=mock(NotifyPlanMapper.class);
    final NotificationEligibility eligibility=new NotificationEligibility(java.util.List.of(new ActivityDeliveryEligibility(owner)),plans);
    Notification row(String sourceKey) { return Notification.builder().id(3L).userId(7L).targetId(8L)
            .targetType(TargetType.ACTIVITY.getCode()).type(8).sourceKey(sourceKey).build(); }
    @Test void cancelledOrWrongSubjectPlanCannotSendEvenIfRecipientRemains() {
        var plan=NotifyPlan.builder().id(9L).sourceType(1).sourceId(8L).notifyType(8).status(2).build();
        when(plans.selectById(9L)).thenReturn(plan);
        assertFalse(eligibility.isEligible(row("plan:9:user:7"),Map.of()));
        plan.setStatus(1);plan.setSourceId(88L);
        assertFalse(eligibility.isEligible(row(null),Map.of("reminderPlanId",9)));
        verifyNoInteractions(owner);
    }
    @Test void completedPlanRequiresCurrentAnchorRuleAndCurrentRecipient() {
        var plan=NotifyPlan.builder().id(9L).sourceType(1).sourceId(8L).notifyType(8).status(1).build();
        when(plans.selectById(9L)).thenReturn(plan);
        when(owner.isCurrentAndEligible(any(),any())).thenReturn(true);
        assertFalse(eligibility.isEligible(row(null),Map.of("reminderPlanId",9)));
        when(owner.isRecipient(8L,7L,cn.jualn.miniapp.module.notify.enums.NotifyType.ACTIVITY_START_REMINDER,null)).thenReturn(true);
        assertTrue(eligibility.isEligible(row(null),Map.of("reminderPlanId",9)));
        when(owner.isCurrentAndEligible(any(),any())).thenReturn(false);
        assertFalse(eligibility.isEligible(row(null),Map.of("reminderPlanId",9)));
    }
    @Test void oldSnapshotUsesFrozenAnchorWithoutInventingFromCurrentActivityOrSendAt() {
        var anchor=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(2).withNano(0);
        when(owner.isStartDeliveryEligible(eq(8L),eq(7L),eq(anchor),any())).thenReturn(true);
        assertTrue(eligibility.isEligible(row("reminder:1:8:8:old:user:7"),Map.of("startTime",anchor.toString())));
        assertFalse(eligibility.isEligible(row(null),Map.of("sendAt",anchor.toString())));
        assertFalse(eligibility.isEligible(row(null),Map.of("startTime","bad time")));
    }

    @Test void missingOrMalformedPlanNeverFallsBackToLegacyAnchor() {
        var frozen = LocalDateTime.of(2026, 10, 1, 15, 0).toString();
        assertFalse(eligibility.isEligible(row(null),Map.of("reminderPlanId",9,"startTime",frozen)));
        assertFalse(eligibility.isEligible(row(null),Map.of("reminderPlanId","invalid","startTime",frozen)));
        assertFalse(eligibility.isEligible(row("plan:999999999999999999999999:user:7"),Map.of("startTime",frozen)));
        verifyNoInteractions(owner);
    }

    @Test void eachDeliveryCheckReadsCurrentPlanAndCannotReuseEarlierAcceptance() {
        var plan=NotifyPlan.builder().id(9L).sourceType(1).sourceId(8L).notifyType(8).status(1).build();
        when(plans.selectById(9L)).thenReturn(plan);
        when(owner.isCurrentAndEligible(any(),any())).thenReturn(true);
        when(owner.isRecipient(eq(8L),eq(7L),any(),isNull())).thenReturn(true);
        assertTrue(eligibility.isEligible(row(null),Map.of("reminderPlanId",9)));
        plan.setStatus(2);
        assertFalse(eligibility.isEligible(row(null),Map.of("reminderPlanId",9)));
        verify(plans,times(2)).selectById(9L);
    }

    @Test void directBusinessEventsDoNotReadReminderPlans() {
        var notification=row(null);
        notification.setType(cn.jualn.miniapp.module.notify.enums.NotifyType.ACTIVITY_CANCELLED.getCode());
        when(owner.isDirectEventEligible(eq(8L),eq(7L),any())).thenReturn(true);
        assertTrue(eligibility.isEligible(notification,Map.of("reminderPlanId",9)));
        verifyNoInteractions(plans);
    }
}
