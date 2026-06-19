package cn.jualn.miniapp.module.timeline.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineCreateBO {

    private TargetType targetType;

    private Long targetId;

    private String label;

    private String description;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer sortOrder;
}
