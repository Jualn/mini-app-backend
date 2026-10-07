package cn.jualn.miniapp.module.exam.service;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicEventReminderPolicyTest {
    private final PublicEventReminderPolicy policy = new PublicEventReminderPolicy();
    private final LocalDateTime now = LocalDateTime.of(2027, 4, 1, 9, 0);

    @Test
    void createsIndependentStartAndDeadlineRules() {
        ExamInfo event = ExamInfo.builder().title("等级考试").publishStatus(1).lifecycleStatus(0).build();
        List<ReminderSpec> specs = policy.evaluate(event, List.of(
                point(21L, "PUBLIC_EVENT_START", now.plusHours(4), "报名截止", 7),
                point(22L, "REGISTRATION_END", now.plusDays(2), "事项开始", 0)), now);

        assertEquals(2, specs.size());
        assertEquals("PUBLIC_EVENT_START_REMINDER", specs.get(0).ruleKey());
        assertEquals(now.plusHours(3), specs.get(0).sendAt());
        assertEquals(NotifyType.PUBLIC_EVENT_START_REMINDER, specs.get(0).notificationType());
        assertEquals("PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER", specs.get(1).ruleKey());
        assertEquals(NotifyType.PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER,
                specs.get(1).notificationType());
    }

    @Test
    void neverTreatsExamLabelOrderOrFirstExactPointAsStartSemantic() {
        ExamInfo event = ExamInfo.builder().publishStatus(1).lifecycleStatus(0).build();
        assertTrue(policy.evaluate(event, List.of(
                point(1L, "EXAM", now.plusHours(4), "公共事项开始", 0),
                point(2L, "OTHER", now.plusHours(5), "开始", 1)), now).isEmpty());
    }

    @Test
    void skipsAmbiguousMalformedPastAndIneligibleRules() {
        ExamInfo event = ExamInfo.builder().publishStatus(1).lifecycleStatus(0).build();
        assertTrue(policy.evaluate(event, List.of(
                point(1L, "PUBLIC_EVENT_START", now.plusHours(4), "A", 0),
                point(2L, "PUBLIC_EVENT_START", now.plusHours(5), "B", 1)), now).isEmpty());
        TimelineItemDTO malformedRange = point(3L, "PUBLIC_EVENT_START", now.plusHours(5), "A", 0);
        malformedRange.setEndTime(now.plusHours(6));
        malformedRange.setEndPrecision(1);
        assertTrue(policy.evaluate(event, List.of(malformedRange), now).isEmpty());
        assertEquals(1, policy.evaluate(event,
                List.of(point(4L, "PUBLIC_EVENT_START", now.plusMinutes(30), "A", 0)), now).size());
        assertTrue(policy.evaluate(ExamInfo.builder().publishStatus(2).lifecycleStatus(0).build(),
                List.of(point(5L, "PUBLIC_EVENT_START", now.plusHours(5), "A", 0)), now).isEmpty());
    }

    private TimelineItemDTO point(long id, String semantic, LocalDateTime time, String label, int order) {
        return TimelineItemDTO.builder().id(id).nodeType(semantic).label(label).sortOrder(order)
                .startPrecision(2).endPrecision(0).startTime(time).build();
    }
}
