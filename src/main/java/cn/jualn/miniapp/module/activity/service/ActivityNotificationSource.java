package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.bo.ReminderPlanBO;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.*;
import cn.jualn.miniapp.module.notify.payload.data.ActivityRemindNoticeData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ActivityNotificationSource implements NotificationSource {
    private final ActivityEnrollmentService enrollment;

    public int sourceType() {
        return 1;
    }

    public TargetType targetType() {
        return TargetType.ACTIVITY;
    }

    public long upperBound(long id, String scope) {
        return enrollment.subscriberOrRegisteredUpperBound(id);
    }

    public List<Long> recipients(long id, String scope, long after, long upper, int limit) {
        List<Long> users = switch (scope == null ? "SUBSCRIBERS" : scope) {
            case "SUBSCRIBERS_OR_REGISTERED_USERS" ->
                    enrollment.listSubscriberOrRegisteredUserIds(id, after, upper, limit);
            case "SUBSCRIBERS_NOT_REGISTERED" -> enrollment.listNotifyEnabledUnregisteredUserIds(id, after, limit);
            case "SUBSCRIBERS" -> enrollment.listEnrolledUserIds(id, after, limit);
            default -> throw new IllegalArgumentException("Unsupported Activity recipient scope");
        };
        return users.stream().filter(user -> user <= upper).toList();
    }

    public NotifyPayload reminder(long user, ReminderPlanBO plan) {
        var builder = NotifyPayload.builder().receiverId(user).type(NotifyType.fromCode(plan.getNotifyType()))
                .title(plan.getTitle()).content(plan.getContent()).targetType(targetType()).targetId(plan.getSourceId())
                .sourceKey(ReminderOccurrence.sourceKey(plan, user)).reminderPlanId(plan.getId())
                .businessTime(plan.getSubjectStartsAt()).businessLocation(plan.getSubjectLocation());
        if (Integer.valueOf(8).equals(plan.getNotifyType()) && plan.getSourceId() != null && plan.getTitle() != null && plan.getSubjectStartsAt() != null)
            builder.contentData(new ActivityRemindNoticeData(plan.getSourceId(), plan.getTitle(), plan.getSubjectStartsAt(), plan.getSubjectLocation()));
        return builder.build();
    }

    public Map<String, Object> content(NotifyPayload payload) {
        String key = switch (payload.getType()) {
            case ACTIVITY_START_REMINDER -> "startTime";
            case ACTIVITY_REGISTRATION_START_REMINDER -> "registrationStartTime";
            case ACTIVITY_REGISTRATION_DEADLINE_REMINDER -> "registrationDeadlineTime";
            default -> null;
        };
        if (key == null || payload.getBusinessTime() == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, payload.getBusinessTime().toString());
        result.put("businessTime", payload.getBusinessTime().toString());
        if (payload.getBusinessLocation() != null) result.put("location", payload.getBusinessLocation());
        return result;
    }
}
