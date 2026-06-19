package cn.jualn.miniapp.module.timeline.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 时间线视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineVO {

    private Long id;

    /** 1-活动 2-考试信息 3-帖子 */
    private Integer targetType;

    private Long targetId;

    private String label;

    private String description;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    private Integer sortOrder;
}
