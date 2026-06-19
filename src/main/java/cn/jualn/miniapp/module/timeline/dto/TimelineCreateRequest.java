package cn.jualn.miniapp.module.timeline.dto;

import cn.jualn.miniapp.module.timeline.dto.request.TimelineItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;


/**
 * 创建时间线请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineCreateRequest {

    @NotNull(message = "目标类型不能为空")
    @Min(value = 1, message = "目标类型有误")
    @Max(value = 3, message = "目标类型有误")
    private Integer targetType;

    @NotNull(message = "目标ID不能为空")
    @Positive(message = "目标ID必须大于0")
    private Long targetId;

    @Valid
    @NotNull(message = "attachments 不能为空")
    private List<TimelineItemRequest> timelineItems;
}
