package cn.jualn.miniapp.module.audit.converter;

import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.enums.AuditScene;

/** Pure conversion of audit-owned codes; contains no business decisions or persistence access. */
public final class AuditEnumMappings {
    private AuditEnumMappings() { }

    public static AuditStatus toAuditStatus(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> AuditStatus.PENDING;
            case 1 -> AuditStatus.PASS;
            case 2 -> AuditStatus.REJECT;
            default -> null;
        };
    }
    public static Integer fromAuditStatus(AuditStatus status) {
        return status == null ? null : status.getCode();
    }

    public static AuditScene toAuditScene(Integer code) {
        if (code == null) return null;
        return AuditScene.fromCode(code);
    }
    public static Integer fromAuditScene(AuditScene scene) {
        return scene == null ? null : scene.getCode();
    }
}
