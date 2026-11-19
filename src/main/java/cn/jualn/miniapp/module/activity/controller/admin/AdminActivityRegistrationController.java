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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operations over applicants' personal registration data. */
@RestController
@Validated
@RequiredArgsConstructor
public class AdminActivityRegistrationController {
    private final ActivityRegistrationService service;
    private final AdminActivityRegistrationResourceConverter converter;

    @GetMapping("/v1/admin/activities/registration-candidates")
    public ResponseEntity<AdminActivityRegistrationResourceConverter.CandidatePage> candidates(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_READ);
        var result = cn.jualn.miniapp.common.web.AdminQuery.read(() ->
                service.pageCandidates(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), q, page, pageSize));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(converter.candidates(result));
    }

    @GetMapping("/v1/admin/activities/{activityId}/registrations")
    public ResponseEntity<AdminActivityRegistrationPageResourceVO> list(
            @PathVariable @Positive Long activityId, @Valid AdminActivityRegistrationQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_READ);
        var value = service.pageAdminContract(activityId, converter.status(query),
                query.getPage(), query.getPageSize());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(converter.page(value));
    }

    @org.springframework.web.bind.annotation.PostMapping("/v1/admin/activities/{activityId}/registrations/{registrationId}:invalidate")
    public ResponseEntity<cn.jualn.miniapp.module.activity.vo.admin.AdminActivityRegistrationResourceVO> invalidate(
            @PathVariable @Positive Long activityId, @PathVariable @Positive Long registrationId,
            @org.springframework.web.bind.annotation.RequestHeader(value="If-Match", required=false) String match,
            @org.springframework.web.bind.annotation.RequestBody com.fasterxml.jackson.databind.JsonNode request) {
        AdminStpUtil.STP_LOGIC.checkPermission("activity:registration:dispose");
        if (request == null || !request.isObject() || request.size() != 1 || !request.path("reason").isTextual())
            throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(
                    new cn.jualn.miniapp.common.exception.ContractProblemException.Violation("body", "/reason", "INVALID", "A reason is required"));
        var expected = new cn.jualn.miniapp.module.activity.converter.PersonalRegistrationConverter().versionCondition(match);
        var value = service.invalidateContract(activityId, registrationId, request.path("reason").textValue(), expected);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(AdminActivityRegistrationResourceConverter.etag(value)).body(converter.registration(value));
    }

    @GetMapping("/v1/admin/activities/{activityId}/registrations/export")
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
