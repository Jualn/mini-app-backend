package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.infrastructure.async.job.UnknownOutcomeException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.module.audit.bo.AuditMediaCheckBO;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientException;

@Component
@RequiredArgsConstructor
public class AuditMediaJobHandler implements JobHandler<AuditMediaJobPayload> {
    private final AuditService auditService;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.MediaSafetySource> mediaSources;
    private final UserWechatIdentityService userService;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.audit.service.ContentSafetyCheckExecution execution;

    @Override public String jobType() { return "audit.media.submit"; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<AuditMediaJobPayload> payloadType() { return AuditMediaJobPayload.class; }

    @Override
    public void handle(AuditMediaJobPayload payload) {
        long logId=execution==null?payload.auditLogId():execution.prepare(payload.auditLogId());
        if(logId==0) return;
        String openid;
        try {openid=payload.actorUserId()==null?null:userService.getMiniOpenid(payload.actorUserId());}
        catch(RuntimeException failure) {if(execution!=null) execution.failedBeforeInvocation(logId);throw failure;}
        String providerUrl;
        try {providerUrl=payload.mediaUrl()!=null && payload.mediaUrl().contains(cn.jualn.miniapp.module.media.service.MediaDeliveryUrls.PATH)
                ?mediaSources.getObject().resolve(payload.mediaUrl()):payload.mediaUrl();}
        catch(RuntimeException beforeInvocation){if(execution!=null)execution.failedBeforeInvocation(logId);throw beforeInvocation;}
        try {
            auditService.doMediaCheck(AuditMediaCheckBO.builder()
                    .auditLogId(logId).auditScene(payload.auditScene()).targetId(payload.targetId())
                    .mediaUrl(providerUrl).mediaType(payload.mediaType()).scene(payload.scene())
                    .openid(openid)
                    .build());
        } catch (ExternalServiceException | WebClientException uncertain) {
            if(execution!=null) execution.unknown(logId);
            throw new UnknownOutcomeException("Media audit submission outcome is unknown", uncertain);
        }
    }
}
