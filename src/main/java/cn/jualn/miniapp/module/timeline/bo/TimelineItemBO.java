package cn.jualn.miniapp.module.timeline.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineItemBO {
    private String nodeType;
    private String location;
    private Integer startPrecision;
    private Integer endPrecision;
    private String timeDescription;
    private Long id;


    private String label;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer sortOrder;
}
