package cn.jualn.miniapp.module.notify.async;

import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActivityBusinessNotificationJobHandler
        implements JobHandler<ActivityBusinessNotificationJobPayload> {
    private final NotifyService notifyService;

    @Override public String jobType() { return "activity.business-notification.fanout"; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<ActivityBusinessNotificationJobPayload> payloadType() {
        return ActivityBusinessNotificationJobPayload.class;
    }
    @Override public void handle(ActivityBusinessNotificationJobPayload payload) {
        notifyService.notifyActivitySubscribers(payload.activityId(), payload.eventKey(),
                payload.title(), payload.content());
    }
}
