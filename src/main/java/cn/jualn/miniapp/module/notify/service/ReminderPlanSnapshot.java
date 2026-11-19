package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.bo.ReminderPlanBO;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;

/** Notification-owned persistence mapping; never exposes a mutable plan to business extensions. */
public final class ReminderPlanSnapshot {
    private ReminderPlanSnapshot() { }

    public static ReminderPlanBO of(NotifyPlan plan) {
        if (plan == null) return null;
        return ReminderPlanBO.builder().id(plan.getId()).sourceType(plan.getSourceType())
                .sourceId(plan.getSourceId()).timelineId(plan.getTimelineId()).ruleKey(plan.getRuleKey())
                .generation(plan.getGeneration()).recipientScope(plan.getRecipientScope())
                .notifyType(plan.getNotifyType()).title(plan.getTitle()).content(plan.getContent())
                .subjectStartsAt(plan.getSubjectStartsAt()).subjectLocation(plan.getSubjectLocation())
                .sendAt(plan.getSendAt()).status(plan.getStatus()).build();
    }
}
