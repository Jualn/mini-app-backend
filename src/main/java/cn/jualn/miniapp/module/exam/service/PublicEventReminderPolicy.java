package cn.jualn.miniapp.module.exam.service;

import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.timeline.model.TimelineSemantic;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** PublicEvent-owned reminder rule catalog. */
public class PublicEventReminderPolicy {
    private static final Duration START_OFFSET = Duration.ofHours(1);
    private static final Duration REGISTRATION_DEADLINE_OFFSET = Duration.ofHours(24);

    public List<ReminderSpec> evaluate(ExamInfo event, List<TimelineItemDTO> timeline, LocalDateTime now) {
        if (event == null || !Integer.valueOf(1).equals(event.getPublishStatus())
                || !Integer.valueOf(0).equals(event.getLifecycleStatus())) {
            return List.of();
        }
        LocalDateTime evaluatedAt = now == null ? LocalDateTime.now() : now;
        java.util.ArrayList<ReminderSpec> result = new java.util.ArrayList<>();
        addUniqueRule(result, timeline, TimelineSemantic.PUBLIC_EVENT_START.name(), START_OFFSET,
                "PUBLIC_EVENT_START_REMINDER", NotifyType.PUBLIC_EVENT_START_REMINDER,
                event.getTitle(), "公共事项即将开始", evaluatedAt);
        addUniqueRule(result, timeline, TimelineSemantic.REGISTRATION_END.name(),
                REGISTRATION_DEADLINE_OFFSET, "PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER",
                NotifyType.PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER,
                event.getTitle(), "公共事项报名即将截止", evaluatedAt);
        return List.copyOf(result);
    }

    private void addUniqueRule(List<ReminderSpec> result, List<TimelineItemDTO> timeline,
                               String semantic, Duration offset, String ruleKey, NotifyType type,
                               String subjectTitle, String message, LocalDateTime now) {
        List<TimelineItemDTO> matches = safe(timeline).stream()
                .filter(node -> semantic.equals(node.getNodeType())).toList();
        if (matches.size() != 1) return;
        TimelineItemDTO node = matches.get(0);
        if (!hasExactStart(node) || node.getId() == null) return;
        LocalDateTime sendAt = node.getStartTime().minus(offset);
        String title = subjectTitle == null || subjectTitle.isBlank() ? message : subjectTitle;
        result.add(new ReminderSpec(ruleKey, node.getId(), sendAt, "SUBSCRIBERS", type, title, message,
                node.getStartTime(), node.getLocation()));
    }

    private boolean hasExactStart(TimelineItemDTO node) {
        if (!Integer.valueOf(2).equals(node.getStartPrecision()) || node.getStartTime() == null) return false;
        if (node.getEndTime() == null) {
            return node.getEndPrecision() == null || Integer.valueOf(0).equals(node.getEndPrecision());
        }
        return Integer.valueOf(2).equals(node.getEndPrecision())
                && !node.getEndTime().isBefore(node.getStartTime());
    }

    private List<TimelineItemDTO> safe(List<TimelineItemDTO> timeline) {
        return timeline == null ? List.of() : timeline;
    }
}
