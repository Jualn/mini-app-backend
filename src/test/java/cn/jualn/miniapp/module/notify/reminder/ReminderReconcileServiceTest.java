package cn.jualn.miniapp.module.notify.reminder;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
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

    @Test void overdueOldScopeIsReplacedWithCurrentRecipientsWithinTheAnchorWindow() {
        var now=LocalDateTime.of(2030,1,1,9,0);
        var sendAt=now.minusMinutes(20);
        var old=NotifyPlan.builder().id(1L).sourceType(1).sourceId(7L).timelineId(8L).ruleKey("ACTIVITY_START_REMINDER")
                .notifyType(8).recipientScope("SUBSCRIBERS").sendAt(sendAt).subjectStartsAt(sendAt.plusHours(1)).status(0).build();
        var spec=new ReminderSpec("ACTIVITY_START_REMINDER",9L,sendAt,"SUBSCRIBERS_OR_REGISTERED_USERS",
                NotifyType.ACTIVITY_START_REMINDER,"new title","content",sendAt.plusHours(1),"new room");
        when(planMapper.selectActiveBySource(1,7L)).thenReturn(List.of(old));
        when(planMapper.cancelPlan(1L)).thenReturn(1);
        when(planMapper.insert(any(NotifyPlan.class))).thenAnswer(i->{((NotifyPlan)i.getArgument(0)).setId(2L);return 1;});
        service().reconcile(1,7L,99L,List.of(spec),now);
        verify(planMapper).cancelPlan(1L);
        var plan=ArgumentCaptor.forClass(NotifyPlan.class);verify(planMapper).insert(plan.capture());
        assertEquals("SUBSCRIBERS_OR_REGISTERED_USERS",plan.getValue().getRecipientScope());
        assertEquals(sendAt.plusHours(1),plan.getValue().getSubjectStartsAt());
    }

    @Test void completedBusinessOccurrenceIsNotRecreatedAfterOrdinarySave() {
        var sendAt=LocalDateTime.now().plusHours(2);
        when(planMapper.selectActiveBySource(1,7L)).thenReturn(List.of());
        when(planMapper.hasCompletedOccurrence(1,7L,"start",sendAt.plusHours(1))).thenReturn(true);
        service().reconcile(1,7L,4L,List.of(spec(sendAt)),LocalDateTime.now());
        verify(planMapper,never()).insert(any(NotifyPlan.class)); verify(jobService,never()).create(any());
    }

    private ReminderSpec spec(LocalDateTime sendAt) {
        return new ReminderSpec("start", 8L, sendAt, "SUBSCRIBERS",
                NotifyType.ACTIVITY_REMIND, "title", "content", sendAt.plusHours(1), "体育馆");
    }
}
