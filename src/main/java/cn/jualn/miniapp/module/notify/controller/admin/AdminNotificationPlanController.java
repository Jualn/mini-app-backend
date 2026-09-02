package cn.jualn.miniapp.module.notify.controller.admin;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.notify.converter.AdminNotificationPlanConverter;
import cn.jualn.miniapp.module.notify.dto.admin.AdminNotificationPlanPageQuery;
import cn.jualn.miniapp.module.notify.service.AdminNotificationPlanService;
import cn.jualn.miniapp.module.notify.vo.admin.AdminNotificationPlanItemVO;
import cn.jualn.miniapp.module.notify.vo.admin.AdminNotificationPlanPageVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/notification-plans")
public class AdminNotificationPlanController {

    private final AdminNotificationPlanService notificationPlanService;
    private final AdminNotificationPlanConverter notificationPlanConverter;

    @GetMapping
    public Result<AdminNotificationPlanPageVO> pagePlans(
            @Valid AdminNotificationPlanPageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.NOTICE_READ);
        return Result.ok(notificationPlanConverter.toPageVO(
                notificationPlanService.pagePlans(notificationPlanConverter.toQueryBO(query))));
    }

    @GetMapping("/{id}")
    public Result<AdminNotificationPlanItemVO> getPlan(
            @PathVariable @Positive(message = "通知计划ID必须大于0") Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.NOTICE_READ);
        return Result.ok(notificationPlanConverter.toItemVO(notificationPlanService.getPlan(id)));
    }
}
