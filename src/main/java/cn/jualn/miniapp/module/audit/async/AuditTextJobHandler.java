package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.module.audit.bo.AuditTextCheckBO;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditTextJobHandler implements JobHandler<AuditTextJobPayload> {
    private final AuditService auditService;
    private final UserService userService;

    @Override public String jobType() { return "audit.text.submit"; }
    @Override public int schemaVersion() { return 1; }
    @Override public Class<AuditTextJobPayload> payloadType() { return AuditTextJobPayload.class; }

    @Override
    public void handle(AuditTextJobPayload payload) {
        auditService.processTextAudit(AuditTextCheckBO.builder()
                .auditLogId(payload.auditLogId()).auditScene(payload.auditScene()).targetId(payload.targetId())
                .content(payload.content()).scene(payload.scene())
                .openid(payload.actorUserId() == null ? null : userService.getMiniOpenid(payload.actorUserId()))
                .build());
    }
}
