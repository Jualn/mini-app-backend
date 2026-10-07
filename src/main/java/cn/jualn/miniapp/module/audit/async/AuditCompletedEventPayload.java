package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.common.enums.AuditScene;

public record AuditCompletedEventPayload(long auditLogId, AuditScene auditScene, long targetId,
                                         boolean passed, String reason) {
}
