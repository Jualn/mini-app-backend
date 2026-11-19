package cn.jualn.miniapp.module.admin.query.vo;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.audit.vo.admin.AdminReviewPageVO;
import cn.jualn.miniapp.module.notify.vo.admin.AdminNotificationPlanSummaryVO;
import cn.jualn.miniapp.module.report.vo.admin.AdminReportCasePageVO;
import cn.jualn.miniapp.module.user.vo.admin.AdminUserSummaryVO;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

public record AdminDashboardVO(Instant generatedAt, Instant dayStart, Instant dayEnd,
        Partition<AdminUserSummaryVO> users, Partition<AdminReviewPageVO.Summary> reviews,
        Partition<AdminReportCasePageVO.Summary> reports, Partition<AdminNotificationPlanSummaryVO> notifications) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Partition<T>(String status, Instant evaluatedAt, T summary, AdminQuery.Problem problem) {}
}
