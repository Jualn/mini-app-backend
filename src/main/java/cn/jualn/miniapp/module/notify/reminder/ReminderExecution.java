package cn.jualn.miniapp.module.notify.reminder;

import java.time.LocalDateTime;

/** Internal immutable intent passed to the business owner; never part of the HTTP contract. */
public record ReminderExecution(long sourceId, Long generation, Long timelineId, Integer notificationType,
                                String ruleKey, LocalDateTime anchor, LocalDateTime sendAt) { }
