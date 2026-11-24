package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
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

    public List<ReminderSpec> evaluate(Activity activity, List<TimelineFactBO> timeline, LocalDateTime now) {
        if (activity == null || !Integer.valueOf(1).equals(activity.getPublishStatus())
                || !Integer.valueOf(0).equals(activity.getLifecycleStatus())) {
            return List.of();
        }
        LocalDateTime evaluatedAt = now == null ? LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")) : now;
        if ("ENDED".equals(ActivityStagePolicy.state(activity.getLifecycleStatus(), timeline, evaluatedAt))) return List.of();
        java.util.ArrayList<ReminderSpec> result = new java.util.ArrayList<>();
        addUniqueRule(result, timeline, TimelineSemantic.ACTIVITY_START.name(), START_OFFSET,
                "ACTIVITY_START_REMINDER", "SUBSCRIBERS_OR_REGISTERED_USERS", NotifyType.ACTIVITY_START_REMINDER,
                activity.getTitle(), "活动即将开始", evaluatedAt);
        if (Integer.valueOf(2).equals(activity.getRegistrationMode())
                || Integer.valueOf(3).equals(activity.getRegistrationMode())
                || Integer.valueOf(4).equals(activity.getRegistrationMode())) {
            if (!validRegistrationWindow(timeline, activity.getRegistrationMode())) return List.copyOf(result);
            addUniqueRule(result, timeline, TimelineSemantic.REGISTRATION_START.name(), START_OFFSET,
                    "ACTIVITY_REGISTRATION_START_REMINDER", "SUBSCRIBERS",
                    NotifyType.ACTIVITY_REGISTRATION_START_REMINDER, activity.getTitle(), "活动报名即将开始", evaluatedAt);
            addUniqueRule(result, timeline, TimelineSemantic.REGISTRATION_END.name(),
                    REGISTRATION_DEADLINE_OFFSET, "ACTIVITY_REGISTRATION_DEADLINE_REMINDER",
                    "SUBSCRIBERS", NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER,
                    activity.getTitle(), "活动报名即将截止", evaluatedAt);
        }
        return List.copyOf(result);
    }

    private void addUniqueRule(List<ReminderSpec> result, List<TimelineFactBO> timeline,
                               String semantic, Duration offset, String ruleKey, String scope,
                               NotifyType type, String subjectTitle, String message, LocalDateTime now) {
        List<TimelineFactBO> matches = safe(timeline).stream()
                .filter(node -> semantic.equals(node.getNodeType())).toList();
        if (matches.size() != 1) return;
        TimelineFactBO node = matches.get(0);
        if (!hasExactStart(node) || node.getId() == null
                || (!TimelineSemantic.ACTIVITY_START.name().equals(semantic) && node.getEndTime() != null)) return;
        LocalDateTime sendAt = node.getStartTime().minus(offset);
        String title = subjectTitle == null || subjectTitle.isBlank() ? message : subjectTitle;
        result.add(new ReminderSpec(ruleKey, node.getId(), sendAt, scope, type, title, message,
                node.getStartTime(), node.getLocation()));
    }

    private boolean hasExactStart(TimelineFactBO node) {
        if (!Integer.valueOf(2).equals(node.getStartPrecision()) || node.getStartTime() == null) return false;
        if (node.getEndTime() == null) {
            return node.getEndPrecision() == null || Integer.valueOf(0).equals(node.getEndPrecision());
        }
        return Integer.valueOf(2).equals(node.getEndPrecision())
                && !node.getEndTime().isBefore(node.getStartTime());
    }

    private List<TimelineFactBO> safe(List<TimelineFactBO> timeline) {
        return timeline == null ? List.of() : timeline;
    }

    private boolean validRegistrationWindow(List<TimelineFactBO> timeline, Integer mode) {
        List<TimelineFactBO> starts = safe(timeline).stream().filter(n -> "REGISTRATION_START".equals(n.getNodeType())).toList();
        List<TimelineFactBO> ends = safe(timeline).stream().filter(n -> "REGISTRATION_END".equals(n.getNodeType())).toList();
        if (starts.size() > 1 || ends.size() > 1) return false;
        boolean platform = Integer.valueOf(2).equals(mode) || Integer.valueOf(4).equals(mode);
        if (platform && (ends.size() != 1 || !point(ends.get(0))
                || (!starts.isEmpty() && !point(starts.get(0))))) return false;
        return starts.isEmpty() || ends.isEmpty() || !point(starts.get(0)) || !point(ends.get(0))
                || starts.get(0).getStartTime().isBefore(ends.get(0).getStartTime());
    }

    private boolean point(TimelineFactBO node) {
        return hasExactStart(node) && node.getEndTime() == null;
    }
}
