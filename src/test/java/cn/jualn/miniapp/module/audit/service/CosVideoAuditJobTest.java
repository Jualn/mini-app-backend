package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.audit.async.*;
import cn.jualn.miniapp.module.audit.entity.MediaVideoAudit;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.third.cos.client.CosVideoAuditClient;
import cn.jualn.miniapp.third.cos.config.CosVideoAuditProperties;
import cn.jualn.miniapp.third.cos.dto.CosVideoAuditResult;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CosVideoAuditJobTest {
    final CosVideoAuditWriter writer=mock(CosVideoAuditWriter.class);
    final CosVideoAuditClient client=mock(CosVideoAuditClient.class);
    final CosVideoAuditProperties policy=new CosVideoAuditProperties();
    final JobExecutionContext context=mock(JobExecutionContext.class);
    final CosVideoAuditPayload payload=new CosVideoAuditPayload(1,0);
    final CosVideoAuditJobHandler handler=new CosVideoAuditJobHandler(writer,client,policy);
    MediaVideoAudit row(String state) {
        var row=new MediaVideoAudit(); row.setId(1L); row.setState(state); row.setBucket("bucket"); row.setRegion("region");
        row.setObjectKey("article-resource/9/video"); row.setBizType("strategy"); row.setSnapshotIntervalSeconds(1); row.setProviderJobId("v1"); return row;
    }
    @Test void unknownSubmissionIsNotRetriedOrConvertedToApproval() {
        policy.setEnabled(true); when(writer.begin(payload,context)).thenReturn(row("SUBMITTING"));
        when(client.submit(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenThrow(new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR,"cos","timeout"));
        assertThrows(UnknownOutcomeException.class,()->handler.handle(payload,context)); verify(writer).unknown(payload,context);
        when(writer.begin(payload,context)).thenReturn(row("UNKNOWN"));
        assertThrows(UnknownOutcomeException.class,()->handler.handle(payload,context));
        verify(client,times(1)).submit(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt()); verify(writer,never()).submitted(any(),anyString(),anyInt(),any());
    }
    @Test void submissionSuccessOnlySchedulesAuthenticatedReadAndQueryFailuresCanRetry() {
        policy.setEnabled(true); when(writer.begin(payload,context)).thenReturn(row("SUBMITTING"));
        var result=new CosVideoAuditResult("v1","article-video-audit:1","article-resource/9/video","Success",CosVideoAuditResult.Decision.PASS);
        when(client.submit(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenReturn(result);
        handler.handle(payload,context); verify(writer).submitted(payload,"v1",30,context); verify(writer,never()).observed(any(),any(),anyInt(),any());
        when(writer.begin(payload,context)).thenReturn(row("RUNNING")); when(client.query(anyString(),anyString(),anyString())).thenThrow(new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR,"cos","read failed"));
        assertThrows(RetryableRemoteException.class,()->handler.handle(payload,context)); verify(writer,never()).unknown(any(),any());
    }
    @Test void disabledAndStaleWorkCannotCallProvider() {
        assertThrows(JobYieldException.class,()->handler.handle(payload,context)); verifyNoInteractions(writer,client);
        policy.setEnabled(true); when(writer.begin(payload,context)).thenReturn(null); handler.handle(payload,context); verifyNoInteractions(client);
    }
}
