package cn.jualn.miniapp.module.notify.async;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class ReminderCatchUpJobHandler implements JobHandler<ReminderCatchUpPayload> {
    private final NotifyService notify;
    public String jobType() { return "notification.reminder.catch-up"; }
    public int schemaVersion() { return 1; }
    public Class<ReminderCatchUpPayload> payloadType() { return ReminderCatchUpPayload.class; }
    public void handle(ReminderCatchUpPayload payload) { notify.catchUpReminder(payload, null); }
    public void handle(ReminderCatchUpPayload payload, JobExecutionContext context) { notify.catchUpReminder(payload, context); }
}
