package cn.jualn.miniapp.module.audit.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 多媒体审核请求。
 *
 * <p>接收审核 API 的输入参数，带有 Jakarta Validation 校验注解，确保数据合法性，提交后异步处理。</p>
 */
@Data
public class AuditMediaCheckRequest {

    /**
     * 目标类型：1-帖子 2-活动 3-考试信息 4-评论。
     * 必填，不能为空。
     */
    @NotNull(message = "targetType 不能为空")
    private TargetType targetType;

    /**
     * 目标内容 ID。
     * 必填，不能为空。
     */
    @NotNull(message = "targetId 不能为空")
    private Long targetId;

    /**
     * 待审核媒体的公网 URL，微信服务器会下载并审核。
     * 必填，不能为空、带空格，最多 512 个字。
     */
    @NotBlank(message = "mediaUrl 不能为空")
    @Size(max = 512, message = "mediaUrl 长度不能超过512")
    private String mediaUrl;

    /**
     * 媒体类型：1-音频、 2-图片。
     * 必填，只支持 1 或 2。
     */
    @NotNull(message = "mediaType 不能为空")
    @Min(value = 1, message = "mediaType 只支持 1 或 2")
    @Max(value = 2, message = "mediaType 只支持 1 或 2")
    private Integer mediaType;

    /**
     * 微信审核场景值：1-资料；2-评论；3-论坛；4-社交日志。
     * 可选，不填时默认 3（论坛）。
     */
    @Min(value = 1, message = "scene 范围为 1-4")
    @Max(value = 4, message = "scene 范围为 1-4")
    private Integer scene;

    /**
     * 小程序用户 openid，可选。
     * 最多 64 个字。
     */
    @Size(max = 64, message = "openid 长度不能超过64")
    private String openid;
}
