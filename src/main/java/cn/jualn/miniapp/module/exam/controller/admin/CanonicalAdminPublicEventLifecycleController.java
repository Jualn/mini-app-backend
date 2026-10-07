package cn.jualn.miniapp.module.exam.controller.admin;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.exam.converter.AdminPublicEventConverter;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.exam.vo.admin.AdminPublicEventDetailResourceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Explicit canonical lifecycle routes; shared HTTP assembly stays local. */
@RestController
@RequiredArgsConstructor
public class CanonicalAdminPublicEventLifecycleController {
    private final ExamService service;
    private final AdminPublicEventConverter converter;

    @PostMapping("/v1/admin/public-events/{publicEventId}:publish")
    public ResponseEntity<AdminPublicEventDetailResourceVO> publish(@PathVariable Long publicEventId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return transition(publicEventId, ifMatch, "publish");
    }

    @PostMapping("/v1/admin/public-events/{publicEventId}:unpublish")
    public ResponseEntity<AdminPublicEventDetailResourceVO> unpublish(@PathVariable Long publicEventId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return transition(publicEventId, ifMatch, "unpublish");
    }

    @PostMapping("/v1/admin/public-events/{publicEventId}:cancel")
    public ResponseEntity<AdminPublicEventDetailResourceVO> cancel(@PathVariable Long publicEventId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return transition(publicEventId, ifMatch, "cancel");
    }

    @PostMapping("/v1/admin/public-events/{publicEventId}:end")
    public ResponseEntity<AdminPublicEventDetailResourceVO> end(@PathVariable Long publicEventId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return transition(publicEventId, ifMatch, "end");
    }

    private ResponseEntity<AdminPublicEventDetailResourceVO> transition(
            Long publicEventId, String ifMatch, String action) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        var value = service.transitionAdminPublicEvent(publicEventId,
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), ifMatch, action);
        long version = value.getContractVersion() == null ? 1L : value.getContractVersion();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.of("public-event", publicEventId, version))
                .body(converter.toDetailVO(value));
    }
}
