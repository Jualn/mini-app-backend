package cn.jualn.miniapp.module.notify.async;

import cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext;
import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BusinessNotificationJobHandler implements JobHandler<BusinessNotificationJobPayload> {
    private final NotifyService notifyService;

    @Override public String jobType() { return "business-notification.fanout"; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<BusinessNotificationJobPayload> payloadType() { return BusinessNotificationJobPayload.class; }
    @Override public void handle(BusinessNotificationJobPayload payload) {
        throw new IllegalStateException("business notification requires an owned job execution");
    }
    @Override public void handle(BusinessNotificationJobPayload payload, JobExecutionContext context) {
        notifyService.broadcastBusinessNotification(payload, context);
    }
}
