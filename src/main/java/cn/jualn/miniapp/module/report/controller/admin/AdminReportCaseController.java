package cn.jualn.miniapp.module.report.controller.admin;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.report.converter.AdminReportCaseConverter;
import cn.jualn.miniapp.module.report.dto.admin.AdminReportCaseDecisionRequest;
import cn.jualn.miniapp.module.report.dto.admin.AdminReportCasePageQuery;
import cn.jualn.miniapp.module.report.service.AdminReportCaseService;
import cn.jualn.miniapp.module.report.vo.admin.AdminReportCaseDetailVO;
import cn.jualn.miniapp.module.report.vo.admin.AdminReportCasePageVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/report-cases")
public class AdminReportCaseController {
    private final AdminReportCaseService adminReportCaseService;
    private final AdminReportCaseConverter adminReportCaseConverter;

    @GetMapping
    public Result<AdminReportCasePageVO> pageCases(@Valid AdminReportCasePageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.REPORT_READ);
        return Result.ok(adminReportCaseConverter.toPageVO(
                adminReportCaseService.pageCases(adminReportCaseConverter.toQueryBO(query))));
    }

    @GetMapping("/{targetType}/{targetId}")
    public Result<AdminReportCaseDetailVO> getCaseDetail(
            @PathVariable @Pattern(regexp = "post|comment", message = "举报目标类型不合法") String targetType,
            @PathVariable @Positive(message = "举报目标ID必须大于0") Long targetId) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.REPORT_READ);
        return Result.ok(adminReportCaseConverter.toDetailVO(
                adminReportCaseService.getCaseDetail(targetType, targetId)));
    }

    @PostMapping("/{targetType}/{targetId}/decision")
    public Result<Void> resolveCase(
            @PathVariable @Pattern(regexp = "post|comment", message = "举报目标类型不合法") String targetType,
            @PathVariable @Positive(message = "举报目标ID必须大于0") Long targetId,
            @Valid @RequestBody AdminReportCaseDecisionRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.REPORT_HANDLE);
        adminReportCaseService.resolveCase(adminReportCaseConverter.toDecisionBO(
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), targetType, targetId, request));
        return Result.ok(null);
    }
}
