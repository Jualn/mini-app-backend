package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.infrastructure.async.message.EventHandler;
import cn.jualn.miniapp.infrastructure.async.message.MessageEnvelope;
import cn.jualn.miniapp.infrastructure.async.message.EventConsumptionService;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AuditCompletedEventHandler implements EventHandler<AuditCompletedEventPayload> {
    private final Map<AuditScene, AuditResultCallback> registry;
    private final EventConsumptionService consumptionService;

    @Override public String topic() { return "audit.completed"; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<AuditCompletedEventPayload> payloadType() { return AuditCompletedEventPayload.class; }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(AuditCompletedEventPayload payload, MessageEnvelope envelope) {
        if (!consumptionService.beginOnce("audit-completed-v1", envelope.messageId())) return;
        AuditResultCallback callback = registry.get(payload.auditScene());
        if (callback == null) {
            throw new IllegalArgumentException("No audit callback for scene " + payload.auditScene());
        }
        if (payload.passed()) {
            callback.onPass(payload.targetId(), payload.auditLogId());
        } else {
            callback.onReject(payload.targetId(), payload.auditLogId(), payload.reason());
        }
    }
}
