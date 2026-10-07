package cn.jualn.miniapp.module.activity.controller.admin;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.activity.converter.AdminActivityConverter;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityDetailResourceVO;

/** Canonical colon-style Activity lifecycle commands. */
@RestController
@RequiredArgsConstructor
public class CanonicalAdminActivityLifecycleController {
    private final ActivityService service;
    private final AdminActivityConverter converter;

    @PostMapping("/v1/admin/activities/{activityId}:publish")
    public ResponseEntity<AdminActivityDetailResourceVO> publish(@PathVariable Long activityId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return transition(activityId, ifMatch, "publish");
    }

    @PostMapping("/v1/admin/activities/{activityId}:unpublish")
    public ResponseEntity<AdminActivityDetailResourceVO> unpublish(@PathVariable Long activityId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return transition(activityId, ifMatch, "unpublish");
    }

    @PostMapping("/v1/admin/activities/{activityId}:cancel")
    public ResponseEntity<AdminActivityDetailResourceVO> cancel(@PathVariable Long activityId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return transition(activityId, ifMatch, "cancel");
    }

    @PostMapping("/v1/admin/activities/{activityId}:end")
    public ResponseEntity<AdminActivityDetailResourceVO> end(@PathVariable Long activityId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return transition(activityId, ifMatch, "end");
    }

    private ResponseEntity<AdminActivityDetailResourceVO> transition(Long activityId, String ifMatch, String action) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        var value = service.transitionAdminActivity(
                activityId, AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), ifMatch, action);
        long version = value.getContractVersion() == null ? 1L : value.getContractVersion();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.of("activity", activityId, version))
                .body(converter.toDetailVO(value));
    }
}
