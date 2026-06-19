package cn.jualn.miniapp.module.interact.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 互动分享请求。
 */
@Data
public class InteractShareRequest {

    @NotNull(message = "targetType 不能为空")
    private TargetType targetType;

    @NotNull(message = "targetId 不能为空")
    private Long targetId;

    @Min(value = 1, message = "platform 不合法")
    @Max(value = 2, message = "platform 不合法")
    private Integer platform;
}

