package cn.jualn.miniapp.module.exam.controller.admin;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.exam.converter.AdminPublicEventConverter;
import cn.jualn.miniapp.module.exam.dto.admin.AdminPublicEventPageQuery;
import cn.jualn.miniapp.module.exam.dto.admin.AdminPublicEventSaveRequest;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.exam.vo.admin.AdminPublicEventDetailResourceVO;
import cn.jualn.miniapp.module.exam.vo.admin.AdminPublicEventPageVO;
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

/** Thin HTTP boundary for the canonical PublicEvent management resource. */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/public-events")
public class AdminPublicEventController {
    private final ExamService examService;
    private final AdminPublicEventConverter converter;

    @GetMapping
    public ResponseEntity<AdminPublicEventPageVO> list(@Valid AdminPublicEventPageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_READ);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(converter.toPageVO(examService.pageAdminPublicEvents(converter.toQueryBO(query))));
    }

    @PostMapping
    public ResponseEntity<AdminPublicEventDetailResourceVO> create(
            @RequestBody @Valid AdminPublicEventSaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        var value = examService.createAdminPublicEvent(converter.toSaveBO(
                request, null, AdminStpUtil.STP_LOGIC.getLoginIdAsLong()));
        return ResponseEntity.created(URI.create("/v1/admin/public-events/" + value.getId()))
                .cacheControl(CacheControl.noStore()).eTag(etag(value)).body(converter.toDetailVO(value));
    }

    @GetMapping("/{publicEventId}")
    public ResponseEntity<AdminPublicEventDetailResourceVO> get(
            @PathVariable @Positive Long publicEventId) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_READ);
        var value = examService.getAdminPublicEvent(publicEventId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(etag(value)).body(converter.toDetailVO(value));
    }

    @PutMapping("/{publicEventId}")
    public ResponseEntity<AdminPublicEventDetailResourceVO> replace(
            @PathVariable @Positive Long publicEventId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody @Valid AdminPublicEventSaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        var value = examService.replaceAdminPublicEvent(converter.toSaveBO(
                request, publicEventId, AdminStpUtil.STP_LOGIC.getLoginIdAsLong()), ifMatch);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(etag(value)).body(converter.toDetailVO(value));
    }

    private String etag(cn.jualn.miniapp.module.exam.bo.ExamDetailBO value) {
        return StrongEtag.of("public-event", value.getId(),
                value.getContractVersion() == null ? 1L : value.getContractVersion());
    }
}
