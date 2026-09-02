package cn.jualn.miniapp.module.report.mapper;

import lombok.Data;

@Data
public class AdminReportCaseSummaryRow {
    private Long pendingCases;
    private Long urgentCases;
    private Long reportsToday;
    private Long completedToday;
}
