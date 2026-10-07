package cn.jualn.miniapp.module.notify.reminder;

import cn.jualn.miniapp.common.enums.NotifyType;

import java.time.LocalDateTime;

public record ReminderSpec(
        String ruleKey,
        long timelineId,
        LocalDateTime sendAt,
        String recipientScope,
        NotifyType notificationType,
        String title,
        String content,
        LocalDateTime subjectStartsAt,
        String subjectLocation) {
}
