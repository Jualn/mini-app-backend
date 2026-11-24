package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.mapper.ActivityEnrollmentMapper;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import cn.jualn.miniapp.module.notify.reminder.ReminderExecution;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityReminderSubjectStatusTest {
    final ActivityMapper activities = mock(ActivityMapper.class);
    final ActivityEnrollmentMapper recipients = mock(ActivityEnrollmentMapper.class);
    final TimelineService timelines = mock(TimelineService.class);
    final ActivityReminderSubjectStatus status = new ActivityReminderSubjectStatus(activities, timelines, recipients);
    final LocalDateTime now = LocalDateTime.of(2026, 10, 1, 8, 0);
    final Activity activity = Activity.builder().id(7L).publishStatus(1).lifecycleStatus(0).contractVersion(99L).registrationMode(1).build();
    final TimelineFactBO node = TimelineFactBO.builder().id(9L).nodeType("ACTIVITY_START")
            .startPrecision(2).startTime(now.plusMinutes(1)).build();
    ReminderExecution intent() {
        return new ReminderExecution(7L, 2L, 9L, 8, "ACTIVITY_START_REMINDER", now.plusMinutes(1), now.minusMinutes(59));
    }
    void current() {
        when(activities.selectReminderStatus(7L)).thenReturn(activity);
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, 7L)).thenReturn(List.of(node));
    }
    @Test void validOldGenerationRecoversBeforeAnchorButExpiresAtAnchor() {
        current();
        assertTrue(status.isCurrentAndEligible(intent(), now));
        assertFalse(status.isCurrentAndEligible(intent(), now.plusMinutes(1)));
    }
    @Test void changedWithdrawnOrAmbiguousFactCannotExecuteOldIntent() {
        current(); node.setStartTime(now.plusHours(2)); assertFalse(status.isCurrentAndEligible(intent(), now));
        node.setStartTime(null); node.setStartPrecision(0); assertFalse(status.isCurrentAndEligible(intent(), now));
        node.setStartTime(now.plusMinutes(1)); node.setStartPrecision(2);
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, 7L)).thenReturn(List.of(node,node));
        assertFalse(status.isCurrentAndEligible(intent(), now));
    }
    @Test void cancelledActivityDoesNotExecuteReminderButMayNotifyCancellation() {
        current(); activity.setLifecycleStatus(2);
        assertFalse(status.isCurrentAndEligible(intent(), now));
        when(recipients.isSubmittedRegistrant(7L, 3L)).thenReturn(true);
        assertTrue(status.isDirectEventEligible(7L,3L,NotifyType.ACTIVITY_CANCELLED));
        assertTrue(status.isDirectEventEligible(7L,3L,NotifyType.ACTIVITY_TIME_CHANGED));
        activity.setLifecycleStatus(1);
        assertTrue(status.isDirectEventEligible(7L,3L,NotifyType.ACTIVITY_ENDED_EARLY));
    }
    @Test void subscriptionSwitchDoesNotSuppressIndependentRegistrantAndCancellationDoesNotUnsubscribe() {
        assertFalse(status.isRecipient(7L,3L,NotifyType.ACTIVITY_START_REMINDER,null));
        when(recipients.isSubmittedRegistrant(7L,3L)).thenReturn(true);
        assertTrue(status.isRecipient(7L,3L,NotifyType.ACTIVITY_START_REMINDER,null));
        assertFalse(status.isRecipient(7L,3L,NotifyType.ACTIVITY_REGISTRATION_START_REMINDER,null));
        when(recipients.isNotifyEnabledSubscriber(7L,3L)).thenReturn(true);
        assertTrue(status.isRecipient(7L,3L,NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER,null));
        when(recipients.isSubmittedRegistrant(7L,3L)).thenReturn(false);
        assertTrue(status.isRecipient(7L,3L,NotifyType.ACTIVITY_START_REMINDER,null));
        when(recipients.isNotifyEnabledSubscriber(7L,3L)).thenReturn(false);
        assertFalse(status.isRecipient(7L,3L,NotifyType.ACTIVITY_START_REMINDER,null));
    }
    @Test void batchLockUsesOwnerReadBeforeProducingEffects() {
        when(activities.lockReminderStatus(7L)).thenReturn(activity);
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, 7L)).thenReturn(List.of(node));
        assertTrue(status.lockCurrentAndEligible(intent(),now));
        verify(activities).lockReminderStatus(7L);
    }
}
