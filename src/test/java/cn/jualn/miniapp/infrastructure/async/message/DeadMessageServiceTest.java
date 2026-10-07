package cn.jualn.miniapp.infrastructure.async.message;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeadMessageServiceTest {

    @Test
    void terminalEvidenceKeepsCorrelationButDoesNotPersistPayload() {
        DeadMessageMapper mapper = mock(DeadMessageMapper.class);
        DeadMessageService service = new DeadMessageService(mapper);
        MessageEnvelope envelope = new MessageEnvelope("11111111111111111111111111111111", "audit.completed", 1,
                OffsetDateTime.now(), "22222222222222222222222222222222", new TextNode("private payload"));

        service.record("10-0", envelope, 3, "validation", new IllegalArgumentException("bad schema"));

        ArgumentCaptor<DeadMessageRecord> captor = ArgumentCaptor.forClass(DeadMessageRecord.class);
        verify(mapper).upsert(captor.capture());
        DeadMessageRecord record = captor.getValue();
        assertEquals(envelope.messageId(), record.getMessageId());
        assertEquals(envelope.operationId(), record.getOperationId());
        assertEquals("audit.completed", record.getTopic());
        assertTrue(record.getErrorDetail().contains("IllegalArgumentException"));
        assertThrows(NoSuchFieldException.class, () -> DeadMessageRecord.class.getDeclaredField("payload"));
    }
}
