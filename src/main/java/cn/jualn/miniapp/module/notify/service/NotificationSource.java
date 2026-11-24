package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.notify.bo.ReminderPlanBO;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import java.util.List;

/** Business owner supplies facts; notify owns batching, ownership, persistence and recovery. */
public interface NotificationSource {
    int sourceType();
    TargetType targetType();
    long upperBound(long sourceId, String scope);
    List<Long> recipients(long sourceId, String scope, long after, long upper, int limit);
    NotifyPayload reminder(long userId, ReminderPlanBO plan);
    default List<NotifyPayload> reminders(List<Long> users, ReminderPlanBO plan) {
        return users.stream().map(user -> reminder(user, plan)).filter(java.util.Objects::nonNull).toList();
    }
    default java.util.Map<String,Object> content(NotifyPayload payload) { return java.util.Map.of(); }
}
