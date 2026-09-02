package cn.jualn.miniapp.module.media.controller;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.media.dto.request.MediaUploadCredentialRequest;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用附件控制器。
 *
 * <p>提供附件管理与上传凭证接口，路由前缀为 /v1/media。</p>
 */
@Validated
@RestController
@RequestMapping("/v1/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    /**
     * 获取前端直传 COS 的 STS 上传凭证。
     *
     * @param req 凭证请求（目标类型、文件名）
     * @return 上传凭证
     */
    @PostMapping("/upload/credential")
    public Result<CosUploadCredentialDTO> getUploadCredential(@Valid @RequestBody MediaUploadCredentialRequest req) {
        return Result.ok(mediaService.generateUploadCredential(req.getTargetType(), req.getFileNames()));
    }
}
