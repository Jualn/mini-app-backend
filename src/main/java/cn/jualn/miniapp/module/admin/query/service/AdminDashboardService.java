package cn.jualn.miniapp.module.admin.query.service;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.admin.query.bo.AdminDashboardBO;
import cn.jualn.miniapp.module.audit.service.AdminReviewService;
import cn.jualn.miniapp.module.notify.service.AdminNotificationPlanService;
import cn.jualn.miniapp.module.report.service.AdminReportCaseService;
import cn.jualn.miniapp.module.user.service.UserService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {
    private final UserService users;
    private final AdminReviewService reviews;
    private final AdminReportCaseService reports;
    private final AdminNotificationPlanService notifications;

    public AdminDashboardBO summary() {
        var zone = ZoneId.of("Asia/Shanghai");
        var start = LocalDate.now(zone).atStartOfDay();
        var end = start.plusDays(1);
        var userSummary = partition(users::adminSummary);
        var reviewSummary = partition(() -> reviews.dashboardSummary(start, end));
        var reportSummary = partition(() -> reports.dashboardSummary(start, end));
        var notificationSummary = partition(() -> notifications.dashboardSummary(start, end));
        return new AdminDashboardBO(Instant.now(), start.atZone(zone).toInstant(), end.atZone(zone).toInstant(),
                userSummary, reviewSummary, reportSummary, notificationSummary);
    }

    private <T> AdminDashboardBO.Partition<T> partition(Supplier<T> read) {
        try {
            var summary = read.get();
            if (summary == null) {
                throw AdminQuery.unavailable();
            }
            return new AdminDashboardBO.Partition<>("READY", Instant.now(), summary, null);
        } catch (RuntimeException failure) {
            AdminQuery.rethrowAuthentication(failure);
            if (AdminQuery.hidden(failure)) {
                return new AdminDashboardBO.Partition<>("FORBIDDEN", null, null, null);
            }
            return new AdminDashboardBO.Partition<>("ERROR", null, null, AdminQuery.problem(failure));
        }
    }
}
