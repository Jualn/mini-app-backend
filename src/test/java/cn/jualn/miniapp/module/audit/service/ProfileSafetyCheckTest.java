package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProfileSafetyCheckTest {
    private final WxClient client = mock(WxClient.class);
    private final AuditReservationService reservations = mock(AuditReservationService.class);
    private final ContentAuditLogMapper logs = mock(ContentAuditLogMapper.class);
    private final ProfileSafetyCheckService safety = new ProfileSafetyCheckService(client, reservations, logs);
    private WxMsgSecCheckResponse decision(String suggest) {
        var response = new WxMsgSecCheckResponse(); var result = new WxMsgSecCheckResponse.ResultInfo();
        result.setSuggest(suggest); response.setResult(result); return response;
    }
    private void media(int status) {
        when(reservations.reserveAuditLogs(any())).thenReturn(AuditReserveResultBO.builder().mediaItems(List.of(
                AuditReserveResultBO.MediaItem.builder().auditLogId(3L).mediaType(MediaType.IMAGE).build())).build());
        when(logs.selectById(3L)).thenReturn(ContentAuditLog.builder().id(3L).targetId(7L)
                .targetType(AuditScene.USER_AVATAR.getCode()).finalResult(status).build());
    }
    @Test void authoritativeTextDecisionsDistinguishPassRejectionAndUnavailable() {
        when(client.msgSecCheck(any())).thenReturn(decision("pass")); assertDoesNotThrow(() -> safety.checkText("synthetic", "text"));
        when(client.msgSecCheck(any())).thenReturn(decision("risky"));
        assertEquals(422, assertThrows(ContractProblemException.class, () -> safety.checkText("synthetic", "text")).getStatus().value());
        for (String suggest : new String[]{null, "review", "unknown"}) {
            when(client.msgSecCheck(any())).thenReturn(decision(suggest));
            assertEquals(503, assertThrows(ContractProblemException.class, () -> safety.checkText("synthetic", "text")).getStatus().value());
        }
    }
    @Test void providerFailureIs503AndRetainsDiagnosticCause() {
        var failure = new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "wx", "synthetic outage");
        when(client.msgSecCheck(any())).thenThrow(failure);
        assertSame(failure, assertThrows(ContractProblemException.class, () -> safety.checkText("synthetic", "text")).getCause());
    }
    @Test void emptyBioClearNeedsNoNonexistentContentCheck() {
        safety.checkText("synthetic", ""); safety.checkText("synthetic", null); verifyNoInteractions(client);
    }
    @Test void mediaOnlySucceedsFromCommittedFinalPass() {
        media(1); assertDoesNotThrow(() -> safety.checkMedia(7L, AuditScene.USER_AVATAR, "https://media.example/frozen"));
        media(2); assertEquals(422, assertThrows(ContractProblemException.class,
                () -> safety.checkMedia(7L, AuditScene.USER_AVATAR, "https://media.example/frozen")).getStatus().value());
    }
    @Test void mediaDeadlineReturns503AndLatePassOnlyChangesAuditEvidence() {
        media(0); ReflectionTestUtils.setField(safety, "mediaCheckWaitMillis", 0L);
        assertEquals(503, assertThrows(ContractProblemException.class,
                () -> safety.checkMedia(7L, AuditScene.USER_AVATAR, "https://media.example/frozen")).getStatus().value());
        // No publication continuation exists in this service. All calls are reservation or read.
        verify(logs).selectById(3L); verify(logs, never()).updateById(any(ContentAuditLog.class));
    }
    @Test void interruptedWaitPreservesInterruptAndCannotSucceed() {
        media(0); Thread.currentThread().interrupt();
        try {
            assertThrows(ContractProblemException.class, () -> safety.checkMedia(7L, AuditScene.USER_AVATAR, "https://media.example/frozen"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
