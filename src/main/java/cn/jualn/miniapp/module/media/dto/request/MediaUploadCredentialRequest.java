package cn.jualn.miniapp.module.media.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 获取上传凭证请求。
 *
 * <p>用于前端直传 COS 前申请 STS 上传凭证。</p>
 */
@Data
public class MediaUploadCredentialRequest {

	/** 目标类型：1-帖子 2-活动 3-考试信息。 */
    @NotNull(message = "targetType 不能为空")
    private TargetType targetType;

	/** 前端原始文件名，用于生成对象键后缀。 */
    @NotEmpty(message = "fileNames 不能为空")
    @Size(max = 9,message = "最多上传9个附件")
    private List<@NotBlank(message = "fileName 不能为空") @Size(max = 255, message = "fileName 长度不能超过255")String> fileNames;
}
