package cn.jualn.miniapp.module.activity.controller.admin;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO;
import cn.jualn.miniapp.module.activity.converter.AdminActivityConverter;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityDeleteRequest;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityPageQuery;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityReasonRequest;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivitySaveRequest;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityDetailVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityDraftVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityPageVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivitySavedVO;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/activities")
public class AdminActivityController {

    private final ActivityService activityService;
    private final AdminActivityConverter adminActivityConverter;

    @GetMapping
    public Result<AdminActivityPageVO> pageActivities(@Valid AdminActivityPageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_READ);
        return Result.ok(adminActivityConverter.toPageVO(
                activityService.pageAdminActivities(adminActivityConverter.toQueryBO(query))));
    }

    @GetMapping("/{id}")
    public Result<AdminActivityDetailVO> getActivity(
            @PathVariable @Positive(message = "活动ID必须大于0") Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_READ);
        return Result.ok(adminActivityConverter.toDetailVO(activityService.getAdminActivityDetail(id)));
    }

    @GetMapping("/{id}/draft")
    public Result<AdminActivityDraftVO> getActivityDraft(
            @PathVariable @Positive(message = "活动ID必须大于0") Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        return Result.ok(adminActivityConverter.toDraftVO(activityService.getAdminActivityDraft(id)));
    }

    @PostMapping({"", "/drafts"})
    public Result<AdminActivitySavedVO> createActivity(@RequestBody @Valid AdminActivitySaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        long operatorId = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        AdminActivitySaveBO command = adminActivityConverter.toSaveBO(request, null, operatorId);
        Long id = activityService.createAdminActivity(command);
        return Result.ok(AdminActivitySavedVO.builder().id(id.toString()).savedAt(LocalDateTime.now()).build());
    }

    @PutMapping({"/{id}", "/{id}/draft"})
    public Result<AdminActivitySavedVO> updateActivity(
            @PathVariable @Positive(message = "活动ID必须大于0") Long id,
            @RequestBody @Valid AdminActivitySaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        long operatorId = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        activityService.updateAdminActivity(adminActivityConverter.toSaveBO(request, id, operatorId));
        return Result.ok(AdminActivitySavedVO.builder().id(id.toString()).savedAt(LocalDateTime.now()).build());
    }

    @PostMapping("/{id}/submit-review")
    public Result<Void> submitReview(@PathVariable @Positive(message = "活动ID必须大于0") Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        activityService.submitAdminActivityReview(id, AdminStpUtil.STP_LOGIC.getLoginIdAsLong());
        return Result.ok(null);
    }

    @PostMapping("/{id}/pin")
    public Result<Void> pinActivity(@PathVariable @Positive(message = "活动ID必须大于0") Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        activityService.updateAdminActivityPinned(id, AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), true);
        return Result.ok(null);
    }

    @DeleteMapping("/{id}/pin")
    public Result<Void> unpinActivity(@PathVariable @Positive(message = "活动ID必须大于0") Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        activityService.updateAdminActivityPinned(id, AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), false);
        return Result.ok(null);
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancelActivity(
            @PathVariable @Positive(message = "活动ID必须大于0") Long id,
            @RequestBody @Valid AdminActivityReasonRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        activityService.cancelAdminActivity(
                id,
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),
                request.getReason().trim());
        return Result.ok(null);
    }

    @PostMapping("/{id}/end-early")
    public Result<Void> endActivityEarly(
            @PathVariable @Positive(message = "活动ID必须大于0") Long id,
            @RequestBody @Valid AdminActivityReasonRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        activityService.endAdminActivityEarly(
                id,
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),
                request.getReason().trim());
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> removeActivity(
            @PathVariable @Positive(message = "活动ID必须大于0") Long id,
            @RequestBody @Valid AdminActivityDeleteRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        activityService.removeAdminActivity(
                id,
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),
                request.getReason().trim());
        return Result.ok(null);
    }
}
