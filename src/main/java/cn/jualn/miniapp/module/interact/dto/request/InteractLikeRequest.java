package cn.jualn.miniapp.module.interact.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 互动点赞请求。
 */
@Data
public class InteractLikeRequest {

    @NotNull(message = "targetType 不能为空")
    private TargetType targetType;

    @NotNull(message = "targetId 不能为空")
    private Long targetId;
}

