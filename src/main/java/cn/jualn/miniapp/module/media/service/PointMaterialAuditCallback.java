package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.audit.annotation.AuditTarget;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import org.springframework.stereotype.Component;

/** The callback stores evidence only; a subsequent authorized register command owns registration. */
@Component
@AuditTarget(AuditScene.POINTS_MATERIAL)
public class PointMaterialAuditCallback implements AuditResultCallback {
    @Override public void onPass(Long targetId, Long auditLogId) { }
    @Override public void onReject(Long targetId, Long auditLogId, String reason) { }
}
