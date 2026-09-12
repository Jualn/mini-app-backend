package cn.jualn.miniapp.module.exam.controller.admin;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.exam.converter.AdminPublicEventConverter;
import cn.jualn.miniapp.module.exam.dto.admin.*;
import cn.jualn.miniapp.module.exam.vo.admin.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated @RestController @RequiredArgsConstructor
@RequestMapping("/v1/admin/public-events")
public class AdminPublicEventController {
    private final ExamService examService;
    private final AdminPublicEventConverter converter;
    @GetMapping public Result<AdminPublicEventPageVO> page(@Valid AdminPublicEventPageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_READ);
        return Result.ok(converter.toPageVO(examService.pageAdminPublicEvents(converter.toQueryBO(query))));
    }
    @GetMapping("/{id}") public Result<AdminPublicEventDetailVO> detail(@PathVariable @Positive Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_READ);
        return Result.ok(converter.toDetailVO(examService.getAdminPublicEvent(id)));
    }
    @PostMapping public Result<AdminPublicEventDetailVO> create(@Valid @RequestBody AdminPublicEventSaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        return Result.ok(converter.toDetailVO(examService.createAdminPublicEvent(
                converter.toSaveBO(request, null, AdminStpUtil.STP_LOGIC.getLoginIdAsLong()))));
    }
    @PutMapping("/{id}") public Result<AdminPublicEventDetailVO> update(@PathVariable @Positive Long id,
            @Valid @RequestBody AdminPublicEventSaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        return Result.ok(converter.toDetailVO(examService.updateAdminPublicEvent(
                converter.toSaveBO(request, id, AdminStpUtil.STP_LOGIC.getLoginIdAsLong()))));
    }
    @PostMapping("/{id}/publish") public Result<Void> publish(@PathVariable @Positive Long id) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        examService.publishAdminPublicEvent(id, AdminStpUtil.STP_LOGIC.getLoginIdAsLong());
        return Result.ok(null);
    }
    @PostMapping("/{id}/take-down") public Result<Void> takeDown(@PathVariable @Positive Long id,
            @Valid @RequestBody AdminPublicEventReasonRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        examService.takeDownAdminPublicEvent(id, AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), request.getReason());
        return Result.ok(null);
    }
    @PostMapping("/{id}/cancel") public Result<Void> cancel(@PathVariable @Positive Long id,
            @Valid @RequestBody AdminPublicEventReasonRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        examService.cancelAdminPublicEvent(id, AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), request.getReason());
        return Result.ok(null);
    }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable @Positive Long id,
            @Valid @RequestBody AdminPublicEventReasonRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        examService.removeAdminPublicEvent(id, AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), request.getReason());
        return Result.ok(null);
    }
}
