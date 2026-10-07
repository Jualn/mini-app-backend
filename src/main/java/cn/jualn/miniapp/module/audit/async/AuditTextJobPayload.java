package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.common.enums.AuditScene;

public record AuditTextJobPayload(long auditLogId, AuditScene auditScene, long targetId,
                                  String content, int scene, Long actorUserId) {
}
