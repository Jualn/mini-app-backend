package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.module.audit.bo.AuditTextCheckBO;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditTextJobHandler implements JobHandler<AuditTextJobPayload> {
    private final AuditService auditService;
    private final UserWechatIdentityService userService;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.audit.service.ContentSafetyCheckExecution execution;

    @Override public String jobType() { return "audit.text.submit"; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<AuditTextJobPayload> payloadType() { return AuditTextJobPayload.class; }

    @Override
    public void handle(AuditTextJobPayload payload) {
        long logId=execution==null?payload.auditLogId():execution.prepare(payload.auditLogId());
        if(logId==0) return;
        String openid;
        try {openid=payload.actorUserId()==null?null:userService.getMiniOpenid(payload.actorUserId());}
        catch(RuntimeException failure) {if(execution!=null) execution.failedBeforeInvocation(logId);throw failure;}
        try { auditService.processTextAudit(AuditTextCheckBO.builder()
                .auditLogId(logId).auditScene(payload.auditScene()).targetId(payload.targetId())
                .content(payload.content()).scene(payload.scene())
                .openid(openid)
                .build());
        } catch(RuntimeException failure) {
            if(!cn.jualn.miniapp.module.audit.service.ContentSafetyReviewService.supported(payload.auditScene())) throw failure;
            if(execution!=null) execution.unknown(logId);
            throw new cn.jualn.miniapp.infrastructure.async.job.UnknownOutcomeException("Text check outcome requires reconciliation",failure);
        }
    }
}
