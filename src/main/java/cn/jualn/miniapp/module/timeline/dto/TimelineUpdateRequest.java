package cn.jualn.miniapp.module.timeline.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


/**
 * 更新时间线请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineUpdateRequest {

    @NotNull(message = "时间线ID不能为空")
    @Positive(message = "ID必须大于0")
    private Long id;

    @Size(max = 64, message = "标签不能超过64字")
    private String label;

    @Size(max = 255, message = "补充说明不能超过255字")
    private String description;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @Min(value = 0, message = "排序号不能为负数")
    @Max(value = 127, message = "排序号不能超过127")
    private Integer sortOrder;
}
