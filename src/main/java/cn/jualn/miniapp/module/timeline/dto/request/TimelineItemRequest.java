package cn.jualn.miniapp.module.timeline.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TimelineItemRequest {
    @NotBlank(message = "时间线标签不能为空")
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
