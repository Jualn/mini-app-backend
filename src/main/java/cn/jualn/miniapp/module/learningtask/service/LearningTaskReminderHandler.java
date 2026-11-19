package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import org.springframework.stereotype.Component;

@Component
public class LearningTaskReminderHandler implements JobHandler<LearningTaskNotifications.Reminder> {
    private final LearningTaskNotifications notifications;
    public LearningTaskReminderHandler(LearningTaskNotifications notifications) { this.notifications=notifications; }
    public String jobType() { return LearningTaskNotifications.REMINDER_JOB; }
    public int schemaVersion() { return 1; }
    public Class<LearningTaskNotifications.Reminder> payloadType() { return LearningTaskNotifications.Reminder.class; }
    public void handle(LearningTaskNotifications.Reminder payload) { notifications.deliver(payload); }
    public void handle(LearningTaskNotifications.Reminder payload,cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext context) {
        context.prepareBatch();
        notifications.deliver(payload,context);
    }
}
