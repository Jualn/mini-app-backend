package cn.jualn.miniapp.module.media.controller;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.media.vo.AdminAttachmentQueryVO;

import cn.jualn.miniapp.module.media.converter.AttachmentConverter;
import cn.jualn.miniapp.module.media.service.AttachmentReadService;
import cn.jualn.miniapp.module.media.vo.AttachmentVO;
import cn.jualn.miniapp.module.media.dto.request.CreateAttachmentRequest;
import cn.jualn.miniapp.module.media.service.MediaAccess;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CanonicalAttachmentController {
    private final AttachmentReadService service;
    private final MediaAccess mediaService;
    private final cn.jualn.miniapp.module.media.service.AttachmentRegistrationService registration;
    private final AttachmentConverter converter;
    private final cn.jualn.miniapp.module.media.service.AttachmentManagementService management;

    @GetMapping("/v1/admin/attachments/batch")
    public ResponseEntity<AdminAttachmentQueryVO.Batch> batch(jakarta.servlet.http.HttpServletRequest request) {
        var ids = AdminQuery.values(request, "attachmentId", 100);
        var at = java.time.Instant.now();
        var storedIds = ids.stream().map(AdminQuery::storedId).filter(java.util.Objects::nonNull).toList();
        var results = mediaService.readAdminAttachments(storedIds, AdminStpUtil.STP_LOGIC.getLoginIdAsLong()).stream()
                .collect(java.util.stream.Collectors.toMap(item -> item.attachmentId().toString(), converter::toReadVO));
        var items = ids.stream().map(id -> results.getOrDefault(id,
                new AdminAttachmentQueryVO.Item(id, "UNAVAILABLE", null, null))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new AdminAttachmentQueryVO.Batch(at, items));
    }

    @GetMapping("/v1/attachments/{attachmentId}")
    public ResponseEntity<AttachmentVO> publicAttachment(@PathVariable Long attachmentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(converter.toVO(service.getPublicAttachment(attachmentId)));
    }

    @GetMapping("/v1/admin/attachments/{attachmentId}")
    public ResponseEntity<AttachmentVO> adminAttachment(@PathVariable Long attachmentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(AdminQuery.read(() -> converter.toVO(service.getAdminAttachment(attachmentId))));
    }

    public record MyPage(java.util.List<AttachmentVO> items,int page,int pageSize,long totalItems) {}
    @GetMapping("/v1/admin/attachments")
    public ResponseEntity<MyPage> mine(@org.springframework.web.bind.annotation.RequestParam(defaultValue="1") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="20") int pageSize) {
        var result=AdminQuery.read(() -> management.list(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),page,pageSize));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MyPage(
            result.items().stream().map(converter::toVO).toList(),page,pageSize,result.totalItems()));
    }
    @org.springframework.web.bind.annotation.DeleteMapping("/v1/admin/attachments/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable long attachmentId) {
        AdminQuery.read(() -> { management.delete(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),attachmentId); return true; });
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/v1/admin/attachments")
    public ResponseEntity<AttachmentVO> create(@org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String key, @RequestBody @Valid CreateAttachmentRequest request) {
        var value = registration.register(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),key,
                request.getKind(),request.getName(),request.getUrl());
        return ResponseEntity.created(URI.create("/v1/admin/attachments/" + value.getId()))
                .cacheControl(CacheControl.noStore())
                .body(converter.toVO(value));
    }
}
