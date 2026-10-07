package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.timeline.model.TimelineSemantic;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Activity-owned reminder rule catalog. Only explicit Timeline semantic codes are considered.
 */
public class ActivityReminderPolicy {
    private static final Duration START_OFFSET = Duration.ofHours(1);
    private static final Duration REGISTRATION_DEADLINE_OFFSET = Duration.ofHours(24);

    public List<ReminderSpec> evaluate(Activity activity, List<TimelineItemDTO> timeline, LocalDateTime now) {
        if (activity == null || !Integer.valueOf(1).equals(activity.getPublishStatus())
                || !Integer.valueOf(0).equals(activity.getLifecycleStatus())) {
            return List.of();
        }
        LocalDateTime evaluatedAt = now == null ? LocalDateTime.now() : now;
        java.util.ArrayList<ReminderSpec> result = new java.util.ArrayList<>();
        addUniqueRule(result, timeline, TimelineSemantic.ACTIVITY_START.name(), START_OFFSET,
                "ACTIVITY_START_REMINDER", "SUBSCRIBERS", NotifyType.ACTIVITY_START_REMINDER,
                activity.getTitle(), "活动即将开始", evaluatedAt);
        if (Integer.valueOf(2).equals(activity.getRegistrationMode())
                || Integer.valueOf(4).equals(activity.getRegistrationMode())) {
            addUniqueRule(result, timeline, TimelineSemantic.REGISTRATION_END.name(),
                    REGISTRATION_DEADLINE_OFFSET, "ACTIVITY_REGISTRATION_DEADLINE_REMINDER",
                    "SUBSCRIBERS_NOT_REGISTERED", NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER,
                    activity.getTitle(), "活动报名即将截止", evaluatedAt);
        }
        return List.copyOf(result);
    }

    private void addUniqueRule(List<ReminderSpec> result, List<TimelineItemDTO> timeline,
                               String semantic, Duration offset, String ruleKey, String scope,
                               NotifyType type, String subjectTitle, String message, LocalDateTime now) {
        List<TimelineItemDTO> matches = safe(timeline).stream()
                .filter(node -> semantic.equals(node.getNodeType())).toList();
        if (matches.size() != 1) return;
        TimelineItemDTO node = matches.get(0);
        if (!hasExactStart(node) || node.getId() == null) return;
        LocalDateTime sendAt = node.getStartTime().minus(offset);
        String title = subjectTitle == null || subjectTitle.isBlank() ? message : subjectTitle;
        result.add(new ReminderSpec(ruleKey, node.getId(), sendAt, scope, type, title, message,
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
