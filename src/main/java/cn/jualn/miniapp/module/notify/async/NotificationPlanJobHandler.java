package cn.jualn.miniapp.module.notify.async;

import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationPlanJobHandler implements JobHandler<NotificationPlanJobPayload> {
    private final NotifyService notifyService;
    @Override public String jobType() { return "notification.plan.fanout"; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<NotificationPlanJobPayload> payloadType() { return NotificationPlanJobPayload.class; }
    @Override public void handle(NotificationPlanJobPayload payload) { notifyService.broadcastPlanFanOut(payload.planId()); }
    @Override public void handle(NotificationPlanJobPayload payload,
                                 cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext context) {
        notifyService.broadcastPlanFanOut(payload.planId(), context);
    }
}
