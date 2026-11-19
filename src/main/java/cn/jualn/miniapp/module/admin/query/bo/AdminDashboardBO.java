package cn.jualn.miniapp.module.admin.query.bo;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.audit.bo.AdminReviewPageBO;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanSummaryBO;
import cn.jualn.miniapp.module.report.bo.AdminReportCasePageBO;
import cn.jualn.miniapp.module.user.bo.AdminUserSummaryBO;
import java.time.Instant;

public record AdminDashboardBO(Instant generatedAt, Instant dayStart, Instant dayEnd,
        Partition<AdminUserSummaryBO> users, Partition<AdminReviewPageBO.Summary> reviews,
        Partition<AdminReportCasePageBO.Summary> reports, Partition<AdminNotificationPlanSummaryBO> notifications) {
    public record Partition<T>(String status, Instant evaluatedAt, T summary, AdminQuery.Problem problem) {}
}
