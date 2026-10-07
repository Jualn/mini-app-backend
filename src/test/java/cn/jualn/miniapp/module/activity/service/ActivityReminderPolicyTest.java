package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityReminderPolicyTest {
    private final ActivityReminderPolicy policy = new ActivityReminderPolicy();
    private final LocalDateTime now = LocalDateTime.of(2027, 3, 1, 9, 0);

    @Test
    void createsStartAndPlatformDeadlineRulesFromExplicitSemantics() {
        Activity activity = Activity.builder().title("志愿活动").publishStatus(1).lifecycleStatus(0)
                .registrationMode(2).build();
        List<ReminderSpec> specs = policy.evaluate(activity, List.of(
                point(11L, "ACTIVITY_START", now.plusHours(5), "任意展示标题", 9),
                point(12L, "REGISTRATION_END", now.plusDays(3), "活动开始", 0)), now);

        assertEquals(2, specs.size());
        assertEquals("ACTIVITY_START_REMINDER", specs.get(0).ruleKey());
        assertEquals(now.plusHours(4), specs.get(0).sendAt());
        assertEquals(NotifyType.ACTIVITY_START_REMINDER, specs.get(0).notificationType());
        assertEquals(now.plusHours(5), specs.get(0).subjectStartsAt());
        assertEquals("ACTIVITY_REGISTRATION_DEADLINE_REMINDER", specs.get(1).ruleKey());
        assertEquals("SUBSCRIBERS_NOT_REGISTERED", specs.get(1).recipientScope());
        assertEquals(now.plusDays(2), specs.get(1).sendAt());
    }

    @Test
    void ignoresLabelsOrderOtherExactNodesAndAmbiguousSemantics() {
        Activity activity = Activity.builder().publishStatus(1).lifecycleStatus(0).registrationMode(2).build();
        assertTrue(policy.evaluate(activity, List.of(
                point(1L, "OTHER", now.plusHours(5), "活动开始", 0),
                point(2L, "EXAM", now.plusHours(6), "开始", 1)), now).isEmpty());
        assertTrue(policy.evaluate(activity, List.of(
                point(3L, "ACTIVITY_START", now.plusHours(5), "A", 0),
                point(4L, "ACTIVITY_START", now.plusHours(6), "B", 1)), now).isEmpty());
    }

    @Test
    void skipsIneligibleImprecisePastAndExternalDeadlineRules() {
        TimelineItemDTO dateOnly = point(5L, "ACTIVITY_START", now.plusDays(2), "开始", 0);
        dateOnly.setStartPrecision(1);
        assertTrue(policy.evaluate(Activity.builder().publishStatus(0).lifecycleStatus(0).build(),
                List.of(point(1L, "ACTIVITY_START", now.plusHours(2), "x", 0)), now).isEmpty());
        assertTrue(policy.evaluate(Activity.builder().publishStatus(1).lifecycleStatus(2).build(),
                List.of(point(1L, "ACTIVITY_START", now.plusHours(2), "x", 0)), now).isEmpty());
        assertTrue(policy.evaluate(Activity.builder().publishStatus(1).lifecycleStatus(0).build(),
                List.of(dateOnly), now).isEmpty());
        assertEquals(1, policy.evaluate(Activity.builder().publishStatus(1).lifecycleStatus(0).build(),
                List.of(point(6L, "ACTIVITY_START", now.plusMinutes(30), "x", 0)), now).size());

        List<ReminderSpec> external = policy.evaluate(
                Activity.builder().publishStatus(1).lifecycleStatus(0).registrationMode(3).build(),
                List.of(point(7L, "REGISTRATION_END", now.plusDays(3), "截止", 0)), now);
        assertTrue(external.isEmpty());
    }

    private TimelineItemDTO point(long id, String semantic, LocalDateTime time, String label, int order) {
        return TimelineItemDTO.builder().id(id).nodeType(semantic).label(label).sortOrder(order)
                .startPrecision(2).endPrecision(0).startTime(time).build();
    }
}
