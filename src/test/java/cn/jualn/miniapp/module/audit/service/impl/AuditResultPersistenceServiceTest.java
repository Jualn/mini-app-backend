package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.infrastructure.async.outbox.OutboxService;
import cn.jualn.miniapp.module.audit.async.AuditCompletedEventPayload;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditResultPersistenceServiceTest {
    @Test
    void appendsOutboxOnlyForWinningPendingTransition() {
        ContentAuditLogMapper mapper = mock(ContentAuditLogMapper.class);
        OutboxService outbox = mock(OutboxService.class);
        when(mapper.update(any(ContentAuditLog.class), any())).thenReturn(1);
        AuditResultPersistenceService service = new AuditResultPersistenceService(mapper, outbox);

        boolean completed = service.completeText(10L, AuditScene.COMMENT, 20L, "trace",
                AuditStatus.PASS, "{}", null);

        assertTrue(completed);
        verify(outbox).append(eq("audit.completed"), eq(1), nullable(String.class), eq("audit-log"), eq("10"),
                any(AuditCompletedEventPayload.class));
    }

    @Test
    void duplicateResultDoesNotCreateAnotherEvent() {
        ContentAuditLogMapper mapper = mock(ContentAuditLogMapper.class);
        OutboxService outbox = mock(OutboxService.class);
        when(mapper.update(any(ContentAuditLog.class), any())).thenReturn(0);
        AuditResultPersistenceService service = new AuditResultPersistenceService(mapper, outbox);

        boolean completed = service.completeText(10L, AuditScene.COMMENT, 20L, "trace",
                AuditStatus.PASS, "{}", null);

        assertFalse(completed);
        verify(outbox, never()).append(any(), eq(1), any(), any(), any(), any());
    }
}
