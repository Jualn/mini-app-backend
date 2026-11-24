package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.bo.NotificationDeliveryBO;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationEligibility {
    private final List<NotificationDeliveryEligibility> owners;
    private final NotifyPlanMapper plans;
    public boolean isEligible(Notification notification, Map<String,Object> frozen) {
        if (owners.isEmpty()) return true;
        NotifyType type = NotifyType.fromCode(notification.getType());
        Long planId = type != null && (type == NotifyType.ACTIVITY_REMIND || type.isTimedReminder())
                ? planId(notification.getSourceKey(), frozen) : null;
        var plan = planId == null || planId <= 0 ? null : ReminderPlanSnapshot.of(plans.selectById(planId));
        var evidence = new NotificationDeliveryBO(notification.getType(), notification.getTargetType(),
                notification.getTargetId(), notification.getUserId(), planId != null, plan);
        return owners.stream().allMatch(owner -> owner.isEligible(evidence,frozen));
    }

    private Long planId(String sourceKey, Map<String,Object> frozen) {
        Object explicit = frozen.get("reminderPlanId");
        String value = explicit == null ? null : explicit.toString();
        if (value == null && sourceKey != null && sourceKey.matches("plan:[0-9]+:user:[0-9]+"))
            value = sourceKey.split(":")[1];
        if (value == null) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException invalid) { return -1L; }
    }
}
