package cn.jualn.miniapp.module.report.converter;

import cn.jualn.miniapp.module.report.enums.ReportStatus;
import cn.jualn.miniapp.module.report.enums.ReportReason;

/** Pure conversion of report-owned codes; contains no business decisions or persistence access. */
public final class ReportEnumMappings {
    private ReportEnumMappings() { }

    public static ReportStatus toReportStatus(Integer code) {
        if (code == null) return null;
        return ReportStatus.fromCode(code);
    }
    public static Integer fromReportStatus(ReportStatus status) {
        return status == null ? null : status.getCode();
    }

    public static ReportReason toReportReason(Integer code) {
        if (code == null) return null;
        return ReportReason.fromCode(code);
    }
    public static Integer fromReportReason(ReportReason status) {
        return status == null ? null : status.getCode();
    }
}
