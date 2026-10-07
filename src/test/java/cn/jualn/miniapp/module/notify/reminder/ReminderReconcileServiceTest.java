package cn.jualn.miniapp.module.notify.reminder;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderReconcileServiceTest {
    @Mock NotifyPlanMapper planMapper;
    @Mock JobService jobService;

    @Test
    void sameGenerationAndScheduleIsKept() {
        LocalDateTime sendAt = LocalDateTime.now().plusHours(1);
        NotifyPlan current = NotifyPlan.builder().id(1L).timelineId(8L).ruleKey("start")
                .generation(3L).sendAt(sendAt).status(0).recipientScope("SUBSCRIBERS")
                .subjectStartsAt(sendAt.plusHours(1)).subjectLocation("体育馆")
                .notifyType(NotifyType.ACTIVITY_REMIND.getCode()).build();
        when(planMapper.selectActiveBySource(1, 7L)).thenReturn(List.of(current));

        service().reconcile(1, 7L, 3L, List.of(spec(sendAt)), LocalDateTime.now());

        verify(planMapper, never()).cancelPlan(any());
        verify(planMapper, never()).insert(any(NotifyPlan.class));
    }

    @Test
    void scheduleChangeCancelsOldAndCreatesNewPlanAndJob() {
        LocalDateTime oldTime = LocalDateTime.now().plusHours(1);
        LocalDateTime newTime = oldTime.plusHours(1);
        NotifyPlan current = NotifyPlan.builder().id(1L).timelineId(8L).ruleKey("start")
                .generation(3L).sendAt(oldTime).status(0).build();
        when(planMapper.selectActiveBySource(1, 7L)).thenReturn(List.of(current));
        when(planMapper.cancelPlan(1L)).thenReturn(1);
        when(planMapper.insert(any(NotifyPlan.class))).thenAnswer(invocation -> {
            ((NotifyPlan) invocation.getArgument(0)).setId(2L);
            return 1;
        });

        service().reconcile(1, 7L, 3L, List.of(spec(newTime)), LocalDateTime.now());

        verify(jobService).cancelPending("notification.plan.fanout", "plan:1:fanout");
        ArgumentCaptor<JobDefinition> job = ArgumentCaptor.forClass(JobDefinition.class);
        verify(jobService).create(job.capture());
        assertEquals("plan:2:fanout", job.getValue().dedupeKey());
        assertEquals(newTime, job.getValue().nextRunAt());
    }

    @Test
    void pastExpectedScheduleIsNotCreated() {
        when(planMapper.selectActiveBySource(2, 7L)).thenReturn(List.of());

        service().reconcile(2, 7L, 3L, List.of(spec(LocalDateTime.now().minusMinutes(1))),
                LocalDateTime.now());

        verify(planMapper, never()).insert(any(NotifyPlan.class));
        verify(jobService, never()).create(any());
    }

    @Test
    void activityDeadlineAcceptsDifferenceScopeButPublicEventRejectsIt() {
        LocalDateTime sendAt = LocalDateTime.now().plusHours(2);
        when(planMapper.selectActiveBySource(1, 7L)).thenReturn(List.of());
        when(planMapper.insert(any(NotifyPlan.class))).thenAnswer(invocation -> {
            ((NotifyPlan) invocation.getArgument(0)).setId(9L);
            return 1;
        });
        ReminderSpec deadline = new ReminderSpec("deadline", 8L, sendAt,
                "SUBSCRIBERS_NOT_REGISTERED", NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER,
                "title", "content", sendAt.plusHours(24), "体育馆");

        service().reconcile(1, 7L, 3L, List.of(deadline), LocalDateTime.now());

        ArgumentCaptor<NotifyPlan> plan = ArgumentCaptor.forClass(NotifyPlan.class);
        verify(planMapper).insert(plan.capture());
        assertEquals("SUBSCRIBERS_NOT_REGISTERED", plan.getValue().getRecipientScope());
        when(planMapper.selectActiveBySource(2, 7L)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service().reconcile(2, 7L, 3L, List.of(deadline), LocalDateTime.now()));
    }

    private ReminderReconcileService service() {
        return new ReminderReconcileService(planMapper, jobService);
    }

    private ReminderSpec spec(LocalDateTime sendAt) {
        return new ReminderSpec("start", 8L, sendAt, "SUBSCRIBERS",
                NotifyType.ACTIVITY_REMIND, "title", "content", sendAt.plusHours(1), "体育馆");
    }
}
