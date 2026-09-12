package cn.jualn.miniapp.module.activity.controller.admin;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.activity.service.ActivityRegistrationService;
import cn.jualn.miniapp.module.activity.converter.ActivityRegistrationConverter;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityReasonRequest;
import cn.jualn.miniapp.module.activity.vo.admin.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/v1/admin/activities/{activityId}")
public class AdminActivityRegistrationController {
    private final ActivityRegistrationService service;
    private final ActivityRegistrationConverter converter;
    @GetMapping("/form")
    public Result<AdminActivityFormVO> form(@PathVariable @Positive Long activityId) {
        return Result.ok(converter.toAdminFormVO(service.getAdminForm(activityId)));
    }
    @GetMapping("/registrations")
    public Result<AdminActivityRegistrationPageVO> page(@PathVariable @Positive Long activityId,
            @RequestParam(required=false) @Min(1) @Max(3) Integer status,
            @RequestParam(required=false) @Size(max=256) String cursor,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize) {
        var page = service.pageAdmin(activityId, status, cursor, pageSize);
        return Result.ok(new AdminActivityRegistrationPageVO(page.items().stream().map(converter::toAdminVO).toList(), page.hasMore(), page.nextCursor()));
    }
    @GetMapping("/registrations/{registrationId}")
    public Result<AdminActivityRegistrationVO> detail(@PathVariable @Positive Long activityId, @PathVariable @Positive Long registrationId) {
        return Result.ok(converter.toAdminVO(service.getAdminRegistration(activityId, registrationId)));
    }
    @PostMapping("/registrations/{registrationId}/invalidate")
    public Result<Void> invalidate(@PathVariable @Positive Long activityId, @PathVariable @Positive Long registrationId,
            @RequestBody @Valid AdminActivityReasonRequest request) {
        service.invalidate(activityId, registrationId, request.getReason()); return Result.ok(null);
    }
    @GetMapping("/registrations/export")
    public ResponseEntity<byte[]> export(@PathVariable @Positive Long activityId,
            @RequestParam(required=false) @Min(1) @Max(3) Integer status) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=activity-" + activityId + "-registrations.xlsx")
                .header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.export(activityId, status));
    }
}
