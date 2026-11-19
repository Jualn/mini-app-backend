package cn.jualn.miniapp.common.web;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.admin.query.service.AdminDashboardService;
import cn.jualn.miniapp.module.audit.service.AdminReviewService;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanSummaryBO;
import cn.jualn.miniapp.module.notify.service.AdminNotificationPlanService;
import cn.jualn.miniapp.module.report.service.AdminReportCaseService;
import cn.jualn.miniapp.module.user.bo.AdminUserSummaryBO;
import cn.jualn.miniapp.module.user.service.UserService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminQueryBoundaryTest {
    @Test void batchInputsAreBoundedAndNeverSilentlyDeduplicated() {
        for (var ids : List.of(List.<String>of(), List.of("1", "1"), List.of(""),
                IntStream.range(0, 101).mapToObj(Integer::toString).toList(), List.of("x".repeat(129)))) {
            assertThrows(ContractProblemException.class, () -> AdminQuery.validate(ids, "userId", 100));
        }
        var request = new MockHttpServletRequest();
        request.addParameter("userId", "2", "opaque-id", "1");
        assertEquals(List.of("2", "opaque-id", "1"), AdminQuery.values(request, "userId", 100));
        request.addParameter("userId[]", "3");
        assertThrows(ContractProblemException.class, () -> AdminQuery.values(request, "userId", 100));
    }

    @Test void unnamedReadyAndHiddenResourcesHaveDifferentWireShapes() throws Exception {
        var mapper = new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder());
        var ready = mapper.valueToTree(AdminNamesVO.user(new NameReadBO("1", "READY", null, null)));
        var hidden = mapper.valueToTree(AdminNamesVO.user(new NameReadBO("2", "UNAVAILABLE", null, null)));
        assertTrue(ready.has("name"));
        assertTrue(ready.path("name").isNull());
        assertFalse(hidden.has("name"));
        assertFalse(hidden.has("problem"));
    }

    @Test void partitionFailuresPreserveReadyZeroAndShareShanghaiDay() {
        var users = mock(UserService.class);
        var reviews = mock(AdminReviewService.class);
        var reports = mock(AdminReportCaseService.class);
        var notices = mock(AdminNotificationPlanService.class);
        when(users.adminSummary()).thenReturn(AdminUserSummaryBO.builder().total(0L).active(0L).muted(0L).banned(0L).operators(0L).deactivated(0L).build());
        when(reviews.dashboardSummary(any(), any())).thenThrow(new BusinessException(ResultCode.FORBIDDEN));
        when(reports.dashboardSummary(any(), any())).thenThrow(AdminQuery.unavailable());
        when(notices.dashboardSummary(any(), any())).thenAnswer(invocation -> {
            LocalDateTime start = invocation.getArgument(0), end = invocation.getArgument(1);
            assertEquals(start.plusDays(1), end);
            assertEquals(0, start.getHour());
            return AdminNotificationPlanSummaryBO.builder().pending(0L).scheduledToday(0L).sentToday(0L).systemBroadcasts(0L).build();
        });
        var service = new AdminDashboardService(users, reviews, reports, notices);
        var result = service.summary();
        assertEquals("READY", result.users().status());
        assertEquals(0L, result.users().summary().getTotal());
        assertEquals("FORBIDDEN", result.reviews().status());
        assertEquals("ERROR", result.reports().status());
        assertEquals(503, result.reports().problem().status());
        assertEquals(0, result.dayStart().atZone(ZoneId.of("Asia/Shanghai")).getHour());
        when(users.adminSummary()).thenThrow(new ContractProblemException(HttpStatus.UNAUTHORIZED, "/problems/admin-authentication-required", "Session ended"));
        assertThrows(ContractProblemException.class, service::summary);
    }
}
