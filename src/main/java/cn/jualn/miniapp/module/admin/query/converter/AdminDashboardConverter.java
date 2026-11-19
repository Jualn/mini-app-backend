package cn.jualn.miniapp.module.admin.query.converter;

import cn.jualn.miniapp.module.admin.query.bo.AdminDashboardBO;
import cn.jualn.miniapp.module.admin.query.vo.AdminDashboardVO;
import cn.jualn.miniapp.module.audit.vo.admin.AdminReviewPageVO;
import cn.jualn.miniapp.module.notify.vo.admin.AdminNotificationPlanSummaryVO;
import cn.jualn.miniapp.module.report.vo.admin.AdminReportCasePageVO;
import cn.jualn.miniapp.module.user.vo.admin.AdminUserSummaryVO;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class AdminDashboardConverter {
    public AdminDashboardVO toVO(AdminDashboardBO value) {
        return new AdminDashboardVO(value.generatedAt(), value.dayStart(), value.dayEnd(),
                partition(value.users(), s -> AdminUserSummaryVO.builder().total(s.getTotal()).active(s.getActive())
                        .muted(s.getMuted()).banned(s.getBanned()).operators(s.getOperators()).deactivated(s.getDeactivated()).build()),
                partition(value.reviews(), s -> {
                    var result = new AdminReviewPageVO.Summary();
                    result.setPending(s.getPending());
                    result.setHighRisk(s.getHighRisk());
                    result.setTodayCompleted(s.getTodayCompleted());
                    result.setOldestWaitingMinutes(s.getOldestWaitingMinutes());
                    return result;
                }),
                partition(value.reports(), s -> AdminReportCasePageVO.Summary.builder().pendingCases(s.getPendingCases())
                        .urgentCases(s.getUrgentCases()).reportsToday(s.getReportsToday()).completedToday(s.getCompletedToday()).build()),
                partition(value.notifications(), s -> AdminNotificationPlanSummaryVO.builder().pending(s.getPending())
                        .scheduledToday(s.getScheduledToday()).sentToday(s.getSentToday()).systemBroadcasts(s.getSystemBroadcasts()).build()));
    }
    private <A, B> AdminDashboardVO.Partition<B> partition(AdminDashboardBO.Partition<A> value, Function<A, B> convert) {
        return new AdminDashboardVO.Partition<>(value.status(), value.evaluatedAt(),
                value.summary() == null ? null : convert.apply(value.summary()), value.problem());
    }
}
