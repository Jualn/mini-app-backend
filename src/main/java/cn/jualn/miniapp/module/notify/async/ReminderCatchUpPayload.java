package cn.jualn.miniapp.module.notify.async;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
public record ReminderCatchUpPayload(long activityId, long userId, ReminderSpec rule) { }
