package cn.jualn.miniapp.module.notify.async;

import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.module.notify.service.NotificationDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationDeliveryJobHandler implements JobHandler<NotificationDeliveryJobPayload> {
    private final NotificationDeliveryService deliveryService;

    @Override
    public String jobType() {
        return "notification.wechat-deliver";
    }

    @Override
    public int schemaVersion() {
        return 2;
    }

    @Override
    public Class<NotificationDeliveryJobPayload> payloadType() {
        return NotificationDeliveryJobPayload.class;
    }

    @Override
    public void handle(NotificationDeliveryJobPayload payload) {
        deliveryService.execute(payload.deliveryId());
    }

    @Override
    public void handle(NotificationDeliveryJobPayload payload,
                       cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext context) {
        deliveryService.execute(payload.deliveryId(), context);
    }
}
