package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.infrastructure.async.job.UnknownOutcomeException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.module.audit.bo.AuditMediaCheckBO;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientException;

@Component
@RequiredArgsConstructor
public class AuditMediaJobHandler implements JobHandler<AuditMediaJobPayload> {
    private final AuditService auditService;
    private final UserService userService;

    @Override public String jobType() { return "audit.media.submit"; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<AuditMediaJobPayload> payloadType() { return AuditMediaJobPayload.class; }

    @Override
    public void handle(AuditMediaJobPayload payload) {
        try {
            auditService.doMediaCheck(AuditMediaCheckBO.builder()
                    .auditLogId(payload.auditLogId()).auditScene(payload.auditScene()).targetId(payload.targetId())
                    .mediaUrl(payload.mediaUrl()).mediaType(payload.mediaType()).scene(payload.scene())
                    .openid(payload.actorUserId() == null ? null : userService.getMiniOpenid(payload.actorUserId()))
                    .build());
        } catch (ExternalServiceException | WebClientException uncertain) {
            throw new UnknownOutcomeException("Media audit submission outcome is unknown", uncertain);
        }
    }
}
