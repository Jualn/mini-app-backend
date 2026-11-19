package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.mapper.ActivityEnrollmentMapper;
import cn.jualn.miniapp.module.notify.reminder.ReminderSubjectStatus;
import cn.jualn.miniapp.module.notify.reminder.ReminderExecution;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/** Activity-owned execution eligibility. Content revisions are not reminder generations. */
@Component
@RequiredArgsConstructor
public class ActivityReminderSubjectStatus implements ReminderSubjectStatus {
    private final ActivityMapper activityMapper;
    private final TimelineService timelineService;
    private final ActivityEnrollmentMapper enrollmentMapper;
    private final ActivityReminderPolicy policy = new ActivityReminderPolicy();

    @Override public int sourceType() { return 1; }

    @Override public boolean isCurrentAndEligible(long sourceId, Long generation, Long timelineId, LocalDateTime now) {
        // Incomplete pre-anchor intents cannot prove current execution eligibility.
        return false;
    }

    @Override public boolean isCurrentAndEligible(ReminderExecution intent, LocalDateTime now) {
        if (intent.anchor() == null || !intent.anchor().isAfter(now)) return false;
        Activity activity = activityMapper.selectReminderStatus(intent.sourceId());
        return matches(activity, intent, now);
    }

    @Override public boolean lockCurrentAndEligible(ReminderExecution intent, LocalDateTime now) {
        if (intent.anchor() == null || !intent.anchor().isAfter(now)) return false;
        return matches(activityMapper.lockReminderStatus(intent.sourceId()), intent, now);
    }

    private boolean matches(Activity activity, ReminderExecution intent, LocalDateTime now) {
        return policy.evaluate(activity, timelineService.listTimelinesByTarget(TargetType.ACTIVITY, intent.sourceId()), now)
                .stream().anyMatch(spec -> Objects.equals(spec.timelineId(), intent.timelineId())
                        && Objects.equals(spec.ruleKey(), intent.ruleKey())
                        && Objects.equals(spec.notificationType().getCode(), intent.notificationType())
                        && Objects.equals(spec.subjectStartsAt(), intent.anchor())
                        && Objects.equals(spec.sendAt(), intent.sendAt()));
    }

    public boolean isRecipient(long activityId, long userId, NotifyType type, String scope) {
        boolean subscriber = enrollmentMapper.isNotifyEnabledSubscriber(activityId, userId);
        if ("SUBSCRIBERS_NOT_REGISTERED".equals(scope))
            return subscriber && !enrollmentMapper.isSubmittedRegistrant(activityId, userId);
        if ("SUBSCRIBERS".equals(scope) || type == NotifyType.ACTIVITY_REGISTRATION_START_REMINDER
                || type == NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER) return subscriber;
        return subscriber || enrollmentMapper.isSubmittedRegistrant(activityId, userId);
    }

    /** Direct events keep their frozen content; cancellation and explicit end require their own lifecycle. */
    public boolean isDirectEventEligible(long activityId, long userId, NotifyType type) {
        Activity activity = activityMapper.selectReminderStatus(activityId);
        return directSubjectEligible(activity, type)
                && isRecipient(activityId, userId, type, "SUBSCRIBERS_OR_REGISTERED_USERS");
    }

    @Override public boolean lockBusinessEventEligible(long sourceId, Integer notificationType) {
        return directSubjectEligible(activityMapper.lockReminderStatus(sourceId), NotifyType.fromCode(notificationType));
    }

    private boolean directSubjectEligible(Activity activity, NotifyType type) {
        if (activity == null || type == null || !Integer.valueOf(1).equals(activity.getPublishStatus())) return false;
        if (type == NotifyType.ACTIVITY_CANCELLED) return Integer.valueOf(2).equals(activity.getLifecycleStatus());
        if (type == NotifyType.ACTIVITY_ENDED_EARLY) return Integer.valueOf(1).equals(activity.getLifecycleStatus());
        // A committed time/location event stays meaningful after a later lifecycle change.
        return type == NotifyType.ACTIVITY_TIME_CHANGED || type == NotifyType.ACTIVITY_LOCATION_CHANGED;
    }

    /** Compatibility for frozen older start notifications without an internal plan link. */
    public boolean isStartDeliveryEligible(long activityId, long userId, LocalDateTime anchor, LocalDateTime now) {
        if (anchor == null || !anchor.isAfter(now) || !isRecipient(activityId, userId, NotifyType.ACTIVITY_START_REMINDER, null)) return false;
        Activity activity = activityMapper.selectReminderStatus(activityId);
        return policy.evaluate(activity, timelineService.listTimelinesByTarget(TargetType.ACTIVITY, activityId), now).stream()
                .anyMatch(spec -> spec.notificationType() == NotifyType.ACTIVITY_START_REMINDER
                        && anchor.equals(spec.subjectStartsAt()));
    }
}
