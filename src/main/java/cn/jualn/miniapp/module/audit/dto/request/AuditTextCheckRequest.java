package cn.jualn.miniapp.module.audit.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 文本审核请求。
 *
 * <p>接收审核 API 的输入参数，带有 Jakarta Validation 校验注解，确保数据合法性。</p>
 */
@Data
public class AuditTextCheckRequest {

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
     * 待审核的文本内容。
     * 必填，不能为空、带空格、最多 5000 个字。
     */
    @NotBlank(message = "content 不能为空")
    @Size(max = 5000, message = "content 不能超过5000字")
    private String content;

    /**
     * 微信审核场景值：1-资料；2-评论；3-论坛；4-社交日志。
     * 可选，不填时默认 3（论坛）。
     */
    @Min(value = 1, message = "scene 范围为 1-4")
    @Max(value = 4, message = "scene 范围为 1-4")
    private Integer scene;

    /**
     * 小程序用户 openid，需要是两小时内访问过小程序的的用户。
     * 可选，最多 64 个字。
     */
    @Size(max = 64, message = "openid 长度不能超过64")
    private String openid;
}
