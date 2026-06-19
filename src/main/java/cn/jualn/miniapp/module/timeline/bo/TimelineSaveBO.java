package cn.jualn.miniapp.module.timeline.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 时间线覆盖保存 BO（服务层/跨服务传输）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineSaveBO {

    private TargetType targetType;
    private Long targetId;
    private List<TimelineItemBO> timelines;
}