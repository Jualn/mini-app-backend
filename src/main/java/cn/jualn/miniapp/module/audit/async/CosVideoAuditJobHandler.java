package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.module.audit.service.CosVideoAuditWriter;
import cn.jualn.miniapp.third.cos.client.CosVideoAuditClient;
import cn.jualn.miniapp.third.cos.config.CosVideoAuditProperties;
import cn.jualn.miniapp.third.cos.dto.CosVideoAuditResult;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class CosVideoAuditJobHandler implements JobHandler<CosVideoAuditPayload> {
    private final CosVideoAuditWriter writer;
    private final CosVideoAuditClient client;
    private final CosVideoAuditProperties policy;
    @Override public String jobType() { return CosVideoAuditWriter.JOB_TYPE; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<CosVideoAuditPayload> payloadType() { return CosVideoAuditPayload.class; }
    @Override public void handle(CosVideoAuditPayload payload) { throw new IllegalStateException("Video audit requires job ownership"); }
    @Override public void handle(CosVideoAuditPayload payload,JobExecutionContext context) {
        if(!policy.isEnabled()) throw new JobYieldException("CI video auditing disabled");
        if(payload.auditId()<=0 || payload.sequence()<0) throw new IllegalArgumentException("Invalid video audit job");
        context.prepareBatch();
        var row=writer.begin(payload,context); if(row==null) return;
        if("UNKNOWN".equals(row.getState())) throw new UnknownOutcomeException("CI video submission needs reconciliation",null);
        if("SUBMITTING".equals(row.getState())) {
            CosVideoAuditResult result;
            try {
                result=client.submit(row.getBucket(),row.getRegion(),row.getObjectKey(),CosVideoAuditWriter.dataId(row),row.getBizType(),row.getSnapshotIntervalSeconds());
                if(result==null || !Objects.equals(CosVideoAuditWriter.dataId(row),result.dataId()) || !Objects.equals(row.getObjectKey(),result.objectKey())) throw new IllegalArgumentException("CI submission identity mismatch");
            } catch(RuntimeException uncertain) {
                writer.unknown(payload,context);
                throw new UnknownOutcomeException("CI video submission outcome unknown",uncertain);
            }
            // Persist the provider ID first; only a later authenticated GET can establish approval.
            writer.submitted(payload,result.jobId(),policy.getPollIntervalSeconds(),context);
        } else if("RUNNING".equals(row.getState())) {
            CosVideoAuditResult result;
            try { result=client.query(row.getBucket(),row.getRegion(),row.getProviderJobId()); }
            catch(ExternalServiceException failure) { throw new RetryableRemoteException("CI video result query unavailable",failure); }
            writer.observed(payload,result,policy.getPollIntervalSeconds(),context);
        }
    }
}
