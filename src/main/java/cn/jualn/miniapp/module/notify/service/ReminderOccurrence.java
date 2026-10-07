package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.entity.NotifyPlan;

/** Scheduling identity and offset are deliberately absent from canonical business identity. */
public final class ReminderOccurrence {
    private ReminderOccurrence() { }
    public static String sourceKey(NotifyPlan plan, long userId) {
        if (plan.getNotifyType() != null && plan.getNotifyType() >= 8 && plan.getNotifyType() <= 11
                && (plan.getSubjectStartsAt() == null || plan.getSourceId() == null)) {
            throw new IllegalStateException("Canonical reminder has no frozen business anchor; reconcile before execution");
        }
        if (plan.getNotifyType() == null || plan.getNotifyType() < 8 || plan.getNotifyType() > 11) {
            // Pre-anchor compatibility plans cannot invent an occurrence from sendAt or current subject state.
            return "plan:" + plan.getId() + ":user:" + userId;
        }
        return "reminder:" + plan.getSourceType() + ":" + plan.getSourceId() + ":" + plan.getNotifyType()
                + ":" + plan.getSubjectStartsAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant()
                + ":user:" + userId;
    }
}
