package cn.jualn.miniapp.module.media.controller.admin;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.media.dto.admin.AdminMediaUploadCredentialRequest;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/media")
public class AdminMediaController {

    private final MediaService mediaService;

    @PostMapping("/activity-upload-credentials")
    public Result<CosUploadCredentialDTO> getActivityUploadCredential(
            @Valid @RequestBody AdminMediaUploadCredentialRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_EDIT);
        return Result.ok(mediaService.generateUploadCredential(TargetType.ACTIVITY, request.getFileNames()));
    }
    @PostMapping("/public-event-upload-credentials")
    public Result<CosUploadCredentialDTO> getPublicEventUploadCredential(
            @Valid @RequestBody AdminMediaUploadCredentialRequest request) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.PUBLIC_EVENT_EDIT);
        return Result.ok(mediaService.generateUploadCredential(TargetType.EXAM, request.getFileNames()));
    }
}
