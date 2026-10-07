package cn.jualn.miniapp.module.timeline.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/** Canonical mutually-exclusive timeline schedule request. */
@Data
public class TimelineScheduleRequest {
    @Pattern(regexp = "EXACT_POINT|EXACT_RANGE|DATE_POINT|DATE_RANGE|TEXT")
    private String kind;
    private OffsetDateTime time;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private LocalDate date;
    private LocalDate startDate;
    private LocalDate endDate;
    @Size(min = 1, max = 500)
    private String timeDescription;
}
