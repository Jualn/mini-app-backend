package cn.jualn.miniapp.module.publicevent.controller.admin;

import cn.jualn.miniapp.module.publicevent.service.PublicEventDraftService;
import cn.jualn.miniapp.module.publicevent.service.PublicEventAdminQueries;
import cn.jualn.miniapp.module.publicevent.vo.admin.AdminPublicEventDraftResourceVO;
import cn.jualn.miniapp.module.media.converter.AttachmentConverter;
import cn.jualn.miniapp.module.media.vo.AdminAttachmentQueryVO;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.publicevent.converter.AdminPublicEventConverter;
import cn.jualn.miniapp.module.publicevent.dto.admin.AdminPublicEventPageQuery;
import cn.jualn.miniapp.module.publicevent.dto.admin.AdminPublicEventSaveRequest;
import cn.jualn.miniapp.module.publicevent.vo.admin.AdminPublicEventDetailResourceVO;
import cn.jualn.miniapp.module.publicevent.vo.admin.AdminPublicEventPageVO;
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
    private final PublicEventDraftService publicEventDraftService;
    private final PublicEventAdminQueries publicEventAdminQueries;
    private final AdminPublicEventConverter converter;
    private final cn.jualn.miniapp.module.media.service.MediaAccess media;
    private final AttachmentConverter attachmentConverter;

    @GetMapping
    public ResponseEntity<AdminPublicEventPageVO> list(@Valid AdminPublicEventPageQuery query) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_READ);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(converter.toPageVO(publicEventAdminQueries.pageAdminPublicEvents(converter.toQueryBO(query))));
    }

    @PostMapping
    public ResponseEntity<AdminPublicEventDetailResourceVO> create(
            @RequestBody @Valid AdminPublicEventSaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        var value = publicEventDraftService.createAdminPublicEvent(converter.toSaveBO(
                request, null, AdminStpUtil.STP_LOGIC.getLoginIdAsLong()));
        return ResponseEntity.created(URI.create("/v1/admin/public-events/" + value.getId()))
                .cacheControl(CacheControl.noStore()).eTag(etag(value)).body(detail(value));
    }

    @GetMapping("/{publicEventId}")
    public ResponseEntity<AdminPublicEventDetailResourceVO> get(
            @PathVariable @Positive Long publicEventId) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_READ);
        var value = publicEventAdminQueries.getAdminPublicEvent(publicEventId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(etag(value)).body(detail(value));
    }

    @PutMapping("/{publicEventId}")
    public ResponseEntity<AdminPublicEventDetailResourceVO> replace(
            @PathVariable @Positive Long publicEventId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody @Valid AdminPublicEventSaveRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        var value = publicEventDraftService.replaceAdminPublicEvent(converter.toSaveBO(
                request, publicEventId, AdminStpUtil.STP_LOGIC.getLoginIdAsLong()), ifMatch);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(etag(value)).body(detail(value));
    }

    private AdminPublicEventDetailResourceVO detail(cn.jualn.miniapp.module.publicevent.bo.PublicEventDetailBO value) {
        var response = converter.toDetailVO(value);
        var references = response.draft().getAttachments();
        var ids = references == null ? java.util.List.<Long>of() : references.stream()
                .sorted(java.util.Comparator.comparingInt(AdminPublicEventDraftResourceVO.AttachmentLink::displayOrder)
                        .thenComparing(link -> Long.valueOf(link.attachmentId())))
                .map(link -> Long.valueOf(link.attachmentId())).distinct().toList();
        var items = media.readAdminAttachments(ids, AdminStpUtil.STP_LOGIC.getLoginIdAsLong());
        return response.withAttachmentDetails(new AdminAttachmentQueryVO.Projection(
                items.stream().map(attachmentConverter::toReadVO).toList()));
    }

    private String etag(cn.jualn.miniapp.module.publicevent.bo.PublicEventDetailBO value) {
        return StrongEtag.of("public-event", value.getId(),
                value.getContractVersion() == null ? 1L : value.getContractVersion());
    }
}
