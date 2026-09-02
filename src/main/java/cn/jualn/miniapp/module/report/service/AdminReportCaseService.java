package cn.jualn.miniapp.module.report.service;

import cn.jualn.miniapp.module.report.bo.AdminReportCaseDetailBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCaseDecisionBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCasePageBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCaseQueryBO;

public interface AdminReportCaseService {
    AdminReportCasePageBO pageCases(AdminReportCaseQueryBO query);

    AdminReportCaseDetailBO getCaseDetail(String targetType, Long targetId);

    void resolveCase(AdminReportCaseDecisionBO command);
}
