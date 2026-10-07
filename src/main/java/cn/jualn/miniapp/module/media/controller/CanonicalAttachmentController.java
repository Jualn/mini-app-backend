package cn.jualn.miniapp.module.media.controller;

import cn.jualn.miniapp.module.media.converter.AttachmentConverter;
import cn.jualn.miniapp.module.media.service.AttachmentReadService;
import cn.jualn.miniapp.module.media.vo.AttachmentVO;
import cn.jualn.miniapp.module.media.dto.request.CreateAttachmentRequest;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
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
    private final MediaService mediaService;
    private final AttachmentConverter converter;

    @GetMapping("/v1/attachments/{attachmentId}")
    public AttachmentVO publicAttachment(@PathVariable Long attachmentId) {
        return converter.toVO(service.getPublicAttachment(attachmentId));
    }

    @GetMapping("/v1/admin/attachments/{attachmentId}")
    public ResponseEntity<AttachmentVO> adminAttachment(@PathVariable Long attachmentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(converter.toVO(service.getAdminAttachment(attachmentId)));
    }

    @PostMapping("/v1/admin/attachments")
    public ResponseEntity<AttachmentVO> create(@RequestBody @Valid CreateAttachmentRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.CONTENT_MANAGE);
        var value = mediaService.registerAttachment(request.getKind(), request.getName(), request.getUrl(),
                AdminStpUtil.STP_LOGIC.getLoginIdAsLong());
        return ResponseEntity.created(URI.create("/v1/admin/attachments/" + value.getId()))
                .body(converter.toVO(value));
    }
}
