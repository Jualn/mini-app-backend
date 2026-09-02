package cn.jualn.miniapp.module.audit.controller.admin;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.audit.converter.AdminReviewConverter;
import cn.jualn.miniapp.module.audit.dto.admin.AdminReviewDecisionRequest;
import cn.jualn.miniapp.module.audit.dto.admin.AdminReviewPageQuery;
import cn.jualn.miniapp.module.audit.service.AdminReviewService;
import cn.jualn.miniapp.module.audit.vo.admin.AdminReviewDetailVO;
import cn.jualn.miniapp.module.audit.vo.admin.AdminReviewPageVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/reviews")
public class AdminReviewController {
    private final AdminReviewService adminReviewService;
    private final AdminReviewConverter adminReviewConverter;

    @GetMapping
    public Result<AdminReviewPageVO> pageReviews(@Valid AdminReviewPageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.REVIEW_READ);
        return Result.ok(adminReviewConverter.toPageVO(
                adminReviewService.pageReviews(adminReviewConverter.toQueryBO(query))));
    }

    @GetMapping("/{taskId}")
    public Result<AdminReviewDetailVO> getReviewDetail(
            @PathVariable @Positive(message = "审核任务ID必须大于0") Long taskId) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.REVIEW_READ);
        return Result.ok(adminReviewConverter.toDetailVO(adminReviewService.getReviewDetail(taskId)));
    }

    @PostMapping("/{taskId}/decisions")
    public Result<Void> decide(
            @PathVariable @Positive(message = "审核任务ID必须大于0") Long taskId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9_-]{16,64}", message = "幂等键格式不合法") String idempotencyKey,
            @Valid @RequestBody AdminReviewDecisionRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.REVIEW_DECIDE);
        adminReviewService.decide(adminReviewConverter.toDecisionBO(
                taskId, AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), idempotencyKey, request));
        return Result.ok(null);
    }
}
