package cn.jualn.miniapp.module.report.bo;

import cn.jualn.miniapp.module.report.enums.ReportStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminReportCaseDecisionBO {
    private Long operatorId;
    private String targetType;
    private Long targetId;
    private ReportStatus decision;
    private String remark;
}
