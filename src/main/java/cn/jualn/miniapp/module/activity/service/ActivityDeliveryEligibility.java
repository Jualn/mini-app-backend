package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.service.ActivityReminderSubjectStatus;
import cn.jualn.miniapp.module.notify.bo.NotificationDeliveryBO;
import cn.jualn.miniapp.module.notify.reminder.ReminderExecution;
import cn.jualn.miniapp.module.notify.service.NotificationDeliveryEligibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves internal execution evidence and delegates all Activity facts to their owner.
 */
@Service
@RequiredArgsConstructor
public class ActivityDeliveryEligibility implements NotificationDeliveryEligibility {
    private final ActivityReminderSubjectStatus activityStatus;

    public boolean isEligible(NotificationDeliveryBO notification, Map<String, Object> frozen) {
        NotifyType type = NotifyType.fromCode(notification.type());
        boolean activityType = type == NotifyType.ACTIVITY_REMIND || type == NotifyType.ACTIVITY_START_REMINDER
                || type == NotifyType.ACTIVITY_REGISTRATION_START_REMINDER || type == NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER
                || type == NotifyType.ACTIVITY_CANCELLED || type == NotifyType.ACTIVITY_TIME_CHANGED
                || type == NotifyType.ACTIVITY_LOCATION_CHANGED || type == NotifyType.ACTIVITY_ENDED_EARLY;
        if (!activityType) return true;
        if (!Integer.valueOf(TargetType.ACTIVITY.getCode()).equals(notification.targetType())
                || notification.targetId() == null || notification.userId() == null) return false;
        long activityId = notification.targetId(), userId = notification.userId();
        if (type != NotifyType.ACTIVITY_REMIND && !type.isTimedReminder())
            return activityStatus.isDirectEventEligible(activityId, userId, type);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        if (notification.planReferenced()) {
            var plan = notification.plan();
            if (plan == null || !Integer.valueOf(1).equals(plan.getSourceType())
                    || !Objects.equals(plan.getSourceId(), activityId) || !Objects.equals(plan.getNotifyType(), notification.type())
                    || plan.getStatus() == null || plan.getStatus() == 2) return false;
            return activityStatus.isCurrentAndEligible(new ReminderExecution(activityId, plan.getGeneration(),
                    plan.getTimelineId(), plan.getNotifyType(), plan.getRuleKey(), plan.getSubjectStartsAt(), plan.getSendAt()), now)
                    && activityStatus.isRecipient(activityId, userId, type, plan.getRecipientScope());
        }
        // Pre-link canonical/legacy Activity start snapshots have a reliable startTime; other old
        // reminder types have no implemented external adapter. Never derive an anchor from sendAt.
        Object start = frozen.get("startTime");
        if (start == null || (type != NotifyType.ACTIVITY_REMIND && type != NotifyType.ACTIVITY_START_REMINDER))
            return false;
        LocalDateTime anchor;
        try {
            String value = start.toString();
            anchor = value.endsWith("Z") || value.matches(".*[+-][0-9]{2}:[0-9]{2}$")
                    ? java.time.OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime()
                    : LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException invalid) {
            return false;
        }
        return activityStatus.isStartDeliveryEligible(activityId, userId, anchor, now);
    }

}
