package cn.jualn.miniapp.module.activity.controller.admin;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.activity.converter.AdminActivityConverter;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityPageQuery;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivitySaveRequest;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityDetailResourceVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityPageVO;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP boundary for the canonical Activity management resource. */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/activities")
public class AdminActivityController {
    private final ActivityService activityService;
    private final AdminActivityConverter converter;

    @GetMapping
    public ResponseEntity<AdminActivityPageVO> list(@Valid AdminActivityPageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_READ);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(converter.toPageVO(activityService.pageAdminActivities(converter.toQueryBO(query))));
    }

    @PostMapping
    public ResponseEntity<AdminActivityDetailResourceVO> create(@RequestBody @Valid AdminActivitySaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        long operatorId = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        Long id = activityService.createAdminActivity(converter.toSaveBO(request, null, operatorId));
        var value = activityService.getAdminActivityDetail(id);
        return ResponseEntity.created(URI.create("/v1/admin/activities/" + id)).cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.of("activity", id, version(value))).body(converter.toDetailVO(value));
    }

    @GetMapping("/{activityId}")
    public ResponseEntity<AdminActivityDetailResourceVO> get(@PathVariable @Positive Long activityId) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_READ);
        var value = activityService.getAdminActivityDetail(activityId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.of("activity", activityId, version(value))).body(converter.toDetailVO(value));
    }

    @PutMapping("/{activityId}")
    public ResponseEntity<AdminActivityDetailResourceVO> replace(@PathVariable @Positive Long activityId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody @Valid AdminActivitySaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        var value = activityService.replaceAdminActivity(
                converter.toSaveBO(request, activityId, AdminStpUtil.STP_LOGIC.getLoginIdAsLong()), ifMatch);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.of("activity", activityId, version(value))).body(converter.toDetailVO(value));
    }

    private long version(cn.jualn.miniapp.module.activity.bo.AdminActivityDetailBO value) {
        return value.getContractVersion() == null ? 1L : value.getContractVersion();
    }
}
