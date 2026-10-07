package cn.jualn.miniapp.module.timeline.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineScheduleVO(
        String kind,
        OffsetDateTime time,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        LocalDate date,
        LocalDate startDate,
        LocalDate endDate,
        String timeDescription) {
}
