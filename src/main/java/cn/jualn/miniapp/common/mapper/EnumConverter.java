package cn.jualn.miniapp.common.mapper;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.report.enums.ReportReason;
import cn.jualn.miniapp.module.report.enums.ReportStatus;
import org.springframework.stereotype.Component;

@Component
public class EnumConverter {

    // MediaType
    public MediaType toMediaType(Integer code) {
        if (code == null) return null;
        return MediaType.fromCode(code);
    }
    public Integer fromMediaType(MediaType mediaType) {
        return mediaType == null ? null : mediaType.getCode();
    }

    // TargetType
    public TargetType toTargetType(Integer code) {
        if (code == null) return null;
        return TargetType.fromCode(code);
    }
    public Integer fromTargetType(TargetType targetType) {
        return targetType == null ? null : targetType.getCode();
    }

    // PostStatusEnum
    public PostStatus toPostStatus(Integer code) {
        if (code == null) return null;
        return PostStatus.fromCode(code);
    }
    public Integer fromPostStatus(PostStatus status) {
        return status == null ? null : status.getCode();
    }

    // AuditStatus
    public AuditStatus toAuditStatus(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> AuditStatus.PENDING;
            case 1 -> AuditStatus.PASS;
            case 2 -> AuditStatus.REJECT;
            default -> null;
        };
    }
    public Integer fromAuditStatus(AuditStatus status) {
        return status == null ? null : status.getCode();
    }

    // ActivityCategory
    public ActivityCategory toActivityCategory(Integer code) {
        if (code == null) return null;
        return ActivityCategory.fromCode(code);
    }
    public Integer fromActivityCategory(ActivityCategory category) {
        return category == null ? null : category.getCode();
    }

    // ActivityStatus
    public ActivityStatus toActivityStatus(Integer code) {
        if (code == null) return null;
        return ActivityStatus.fromCode(code);
    }
    public Integer fromActivityStatus(ActivityStatus status) {
        return status == null ? null : status.getCode();
    }

    // ReportStatus
    public ReportStatus toReportStatus(Integer code) {
        if (code == null) return null;
        return ReportStatus.fromCode(code);
    }
    public Integer fromReportStatus(ReportStatus status) {
        return status == null ? null : status.getCode();
    }

    // ReportReason
    public ReportReason toReportReason(Integer code) {
        if (code == null) return null;
        return ReportReason.fromCode(code);
    }
    public Integer fromReportReason(ReportReason status) {
        return status == null ? null : status.getCode();
    }

    // AuditScene
    public AuditScene toAuditScene(Integer code) {
        if (code == null) return null;
        return AuditScene.fromCode(code);
    }
    public Integer fromAuditScene(AuditScene scene) {
        return scene == null ? null : scene.getCode();
    }
}

