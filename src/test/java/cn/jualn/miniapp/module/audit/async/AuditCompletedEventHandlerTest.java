package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import cn.jualn.miniapp.infrastructure.async.message.EventConsumptionService;
import cn.jualn.miniapp.infrastructure.async.message.MessageEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditCompletedEventHandlerTest {
    @Test
    void dispatchesPassedResultToSceneCallback() {
        AuditResultCallback callback = mock(AuditResultCallback.class);
        EventConsumptionService consumption = mock(EventConsumptionService.class);
        when(consumption.beginOnce("audit-completed-v1", "m1")).thenReturn(true);
        AuditCompletedEventHandler handler = new AuditCompletedEventHandler(
                Map.of(AuditScene.COMMENT, callback), consumption);

        handler.handle(new AuditCompletedEventPayload(11L, AuditScene.COMMENT, 22L, true, null), envelope());

        verify(callback).onPass(22L, 11L);
    }

    @Test
    void missingCallbackIsPermanentPoisonEvidence() {
        EventConsumptionService consumption = mock(EventConsumptionService.class);
        when(consumption.beginOnce("audit-completed-v1", "m1")).thenReturn(true);
        AuditCompletedEventHandler handler = new AuditCompletedEventHandler(Map.of(), consumption);

        assertThrows(IllegalArgumentException.class, () -> handler.handle(
                new AuditCompletedEventPayload(11L, AuditScene.COMMENT, 22L, false, "risk"), envelope()));
    }

    private MessageEnvelope envelope() {
        return new MessageEnvelope("m1", "audit.completed", 1, OffsetDateTime.now(), null,
                new ObjectMapper().createObjectNode());
    }
}
