package cn.jualn.miniapp.module.audit.async;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.MediaType;

public record AuditMediaJobPayload(long auditLogId, AuditScene auditScene, long targetId,
                                   String mediaUrl, MediaType mediaType, int scene, Long actorUserId) {
}
