package cn.jualn.miniapp.module.notify.bo;

/** Current delivery evidence; a referenced but unavailable plan must not fall back to legacy data. */
public record NotificationDeliveryBO(Integer type, Integer targetType, Long targetId, Long userId,
                                     boolean planReferenced, ReminderPlanBO plan) {
}
