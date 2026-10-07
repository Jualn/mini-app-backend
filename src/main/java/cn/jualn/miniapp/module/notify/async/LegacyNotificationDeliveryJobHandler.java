package cn.jualn.miniapp.module.notify.async;

import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.infrastructure.async.job.UnknownOutcomeException;
import org.springframework.stereotype.Component;

/** Keeps v1 payloads readable without silently replaying sends lacking Delivery history. */
@Component
public class LegacyNotificationDeliveryJobHandler implements JobHandler<LegacyNotificationDeliveryJobPayload> {

    @Override public String jobType() { return "notification.wechat-deliver"; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<LegacyNotificationDeliveryJobPayload> payloadType() {
        return LegacyNotificationDeliveryJobPayload.class;
    }

    @Override
    public void handle(LegacyNotificationDeliveryJobPayload payload) {
        throw new UnknownOutcomeException(
                "Legacy v1 WeChat job has no Delivery attempt history; requires explicit reconciliation", null);
    }
}
