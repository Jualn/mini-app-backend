package cn.jualn.miniapp.module.timeline.service;

import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineScheduleRequest;
import cn.jualn.miniapp.module.timeline.vo.TimelineScheduleVO;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Canonical schedule validation and mapping over the legacy precision-aware storage shape. */
public final class TimelineSchedulePolicy {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private TimelineSchedulePolicy() {
    }

    public static void apply(TimelineScheduleRequest schedule, TimelineItemBO.TimelineItemBOBuilder target) {
        if (schedule == null || schedule.getKind() == null) {
            throw new IllegalArgumentException("schedule.kind is required");
        }
        switch (schedule.getKind()) {
            case "EXACT_POINT" -> {
                require(schedule.getTime() != null && schedule.getStartTime() == null && schedule.getEndTime() == null
                        && schedule.getDate() == null && schedule.getStartDate() == null && schedule.getEndDate() == null
                        && blank(schedule.getTimeDescription()), "EXACT_POINT schedule fields conflict");
                target.startTime(local(schedule.getTime())).endTime(null)
                        .startPrecision(2).endPrecision(0).timeDescription(null);
            }
            case "EXACT_RANGE" -> {
                require(schedule.getTime() == null && schedule.getStartTime() != null && schedule.getEndTime() != null
                        && schedule.getDate() == null && schedule.getStartDate() == null && schedule.getEndDate() == null
                        && blank(schedule.getTimeDescription()), "EXACT_RANGE schedule fields conflict");
                require(!schedule.getEndTime().isBefore(schedule.getStartTime()), "INVALID_TIME_RANGE");
                target.startTime(local(schedule.getStartTime())).endTime(local(schedule.getEndTime()))
                        .startPrecision(2).endPrecision(2).timeDescription(null);
            }
            case "DATE_POINT" -> {
                require(schedule.getTime() == null && schedule.getStartTime() == null && schedule.getEndTime() == null
                        && schedule.getDate() != null && schedule.getStartDate() == null && schedule.getEndDate() == null
                        && blank(schedule.getTimeDescription()), "DATE_POINT schedule fields conflict");
                target.startTime(schedule.getDate().atStartOfDay()).endTime(null)
                        .startPrecision(1).endPrecision(0).timeDescription(null);
            }
            case "DATE_RANGE" -> {
                require(schedule.getTime() == null && schedule.getStartTime() == null && schedule.getEndTime() == null
                        && schedule.getDate() == null && schedule.getStartDate() != null && schedule.getEndDate() != null
                        && blank(schedule.getTimeDescription()), "DATE_RANGE schedule fields conflict");
                require(!schedule.getEndDate().isBefore(schedule.getStartDate()), "INVALID_TIME_RANGE");
                target.startTime(schedule.getStartDate().atStartOfDay())
                        .endTime(schedule.getEndDate().atStartOfDay())
                        .startPrecision(1).endPrecision(1).timeDescription(null);
            }
            case "TEXT" -> {
                require(!blank(schedule.getTimeDescription()) && schedule.getTime() == null
                        && schedule.getStartTime() == null && schedule.getEndTime() == null && schedule.getDate() == null
                        && schedule.getStartDate() == null && schedule.getEndDate() == null,
                        "TEXT schedule fields conflict");
                target.startTime(null).endTime(null).startPrecision(0).endPrecision(0)
                        .timeDescription(schedule.getTimeDescription().trim());
            }
            default -> throw new IllegalArgumentException("Unsupported schedule kind");
        }
    }

    public static TimelineScheduleVO view(TimelineItemDTO item) {
        return view(item.getStartTime(), item.getEndTime(), item.getStartPrecision(), item.getEndPrecision(), item.getTimeDescription());
    }

    public static TimelineScheduleVO view(TimelineItemBO item) {
        return view(item.getStartTime(), item.getEndTime(), item.getStartPrecision(), item.getEndPrecision(), item.getTimeDescription());
    }

    private static TimelineScheduleVO view(LocalDateTime start, LocalDateTime end, Integer startPrecision,
            Integer endPrecision, String description) {
        if (Integer.valueOf(2).equals(startPrecision) && start != null) {
            if (end != null && !Integer.valueOf(2).equals(endPrecision)) throw new IllegalStateException("Invalid stored exact-precision schedule");
            if (end == null) return new TimelineScheduleVO("EXACT_POINT", offset(start), null, null, null, null, null, null);
            return new TimelineScheduleVO("EXACT_RANGE", null, offset(start), offset(end), null, null, null, null);
        }
        if (Integer.valueOf(1).equals(startPrecision) && start != null) {
            if (end != null && !Integer.valueOf(1).equals(endPrecision)) throw new IllegalStateException("Invalid stored date-precision schedule");
            if (end == null) return new TimelineScheduleVO("DATE_POINT", null, null, null, start.toLocalDate(), null, null, null);
            return new TimelineScheduleVO("DATE_RANGE", null, null, null, null, start.toLocalDate(), end.toLocalDate(), null);
        }
        if (start == null && end == null && !blank(description)) {
            return new TimelineScheduleVO("TEXT", null, null, null, null, null, null, description);
        }
        throw new IllegalStateException("Timeline node has no canonical schedule");
    }

    private static LocalDateTime local(java.time.OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
    }

    private static java.time.OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
