package cn.jualn.miniapp.module.activity.controller.admin;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.activity.converter.AdminActivityRegistrationResourceConverter;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityRegistrationQuery;
import cn.jualn.miniapp.module.activity.service.ActivityRegistrationService;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityRegistrationPageResourceVO;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operations over applicants' personal registration data. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/v1/admin/activities/{activityId}/registrations")
public class AdminActivityRegistrationController {
    private final ActivityRegistrationService service;
    private final AdminActivityRegistrationResourceConverter converter;

    @GetMapping
    public ResponseEntity<AdminActivityRegistrationPageResourceVO> list(
            @PathVariable @Positive Long activityId, @Valid AdminActivityRegistrationQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_READ);
        var value = service.pageAdminContract(activityId, converter.status(query),
                query.getPage(), query.getPageSize());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(converter.page(value));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable @Positive Long activityId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_EXPORT);
        boolean xlsx = format == ExportFormat.XLSX;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "attachment; filename=activity-" + activityId
                        + "-registrations." + (xlsx ? "xlsx" : "csv"))
                .contentType(MediaType.parseMediaType(xlsx
                        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        : "text/csv;charset=UTF-8"))
                .body(xlsx ? service.exportXlsx(activityId, converter.status(status))
                        : service.exportCsv(activityId, converter.status(status)));
    }

    public enum ExportFormat { CSV, XLSX }
}
