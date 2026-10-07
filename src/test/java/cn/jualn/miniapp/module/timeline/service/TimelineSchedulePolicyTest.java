package cn.jualn.miniapp.module.timeline.service;

import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineScheduleRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimelineSchedulePolicyTest {
    @Test
    void mapsAllCanonicalBranchesWithoutInventingPrecision() {
        var exactPoint = new TimelineScheduleRequest();
        exactPoint.setKind("EXACT_POINT");
        exactPoint.setTime(OffsetDateTime.parse("2027-03-01T09:00:00+08:00"));
        var exactPointBuilder = TimelineItemBO.builder();
        TimelineSchedulePolicy.apply(exactPoint, exactPointBuilder);
        var exactPointView = TimelineSchedulePolicy.view(exactPointBuilder.build());
        assertEquals("EXACT_POINT", exactPointView.kind());
        assertEquals(exactPoint.getTime(), exactPointView.time());

        var exactRange = new TimelineScheduleRequest();
        exactRange.setKind("EXACT_RANGE");
        exactRange.setStartTime(OffsetDateTime.parse("2027-03-01T09:00:00+08:00"));
        exactRange.setEndTime(OffsetDateTime.parse("2027-03-01T11:00:00+08:00"));
        var exactRangeBuilder = TimelineItemBO.builder();
        TimelineSchedulePolicy.apply(exactRange, exactRangeBuilder);
        var exactRangeView = TimelineSchedulePolicy.view(exactRangeBuilder.build());
        assertEquals("EXACT_RANGE", exactRangeView.kind());
        assertEquals(exactRange.getStartTime(), exactRangeView.startTime());
        assertEquals(exactRange.getEndTime(), exactRangeView.endTime());

        var datePoint = new TimelineScheduleRequest();
        datePoint.setKind("DATE_POINT");
        datePoint.setDate(LocalDate.parse("2027-03-02"));
        var datePointBuilder = TimelineItemBO.builder();
        TimelineSchedulePolicy.apply(datePoint, datePointBuilder);
        var datePointView = TimelineSchedulePolicy.view(datePointBuilder.build());
        assertEquals("DATE_POINT", datePointView.kind());
        assertEquals(datePoint.getDate(), datePointView.date());
        assertNull(datePointView.time());
        assertNull(datePointView.startTime());

        var dateRange = new TimelineScheduleRequest();
        dateRange.setKind("DATE_RANGE");
        dateRange.setStartDate(LocalDate.parse("2027-03-02"));
        dateRange.setEndDate(LocalDate.parse("2027-03-05"));
        var dateRangeBuilder = TimelineItemBO.builder();
        TimelineSchedulePolicy.apply(dateRange, dateRangeBuilder);
        var dateRangeView = TimelineSchedulePolicy.view(dateRangeBuilder.build());
        assertEquals("DATE_RANGE", dateRangeView.kind());
        assertEquals(dateRange.getStartDate(), dateRangeView.startDate());
        assertEquals(dateRange.getEndDate(), dateRangeView.endDate());

        var text = new TimelineScheduleRequest();
        text.setKind("TEXT");
        text.setTimeDescription("具体时间另行通知");
        var textBuilder = TimelineItemBO.builder();
        TimelineSchedulePolicy.apply(text, textBuilder);
        assertEquals("TEXT", TimelineSchedulePolicy.view(textBuilder.build()).kind());
    }

    @Test
    void rejectsMixedBranchesAndReversedRanges() {
        var mixed = new TimelineScheduleRequest();
        mixed.setKind("TEXT");
        mixed.setTimeDescription("待定");
        mixed.setDate(LocalDate.parse("2027-03-02"));
        assertThrows(IllegalArgumentException.class,
                () -> TimelineSchedulePolicy.apply(mixed, TimelineItemBO.builder()));

        var reversed = new TimelineScheduleRequest();
        reversed.setKind("DATE_RANGE");
        reversed.setStartDate(LocalDate.parse("2027-03-03"));
        reversed.setEndDate(LocalDate.parse("2027-03-02"));
        assertThrows(IllegalArgumentException.class,
                () -> TimelineSchedulePolicy.apply(reversed, TimelineItemBO.builder()));

        var reversedExact = new TimelineScheduleRequest();
        reversedExact.setKind("EXACT_RANGE");
        reversedExact.setStartTime(OffsetDateTime.parse("2027-03-01T11:00:00+08:00"));
        reversedExact.setEndTime(OffsetDateTime.parse("2027-03-01T09:00:00+08:00"));
        assertThrows(IllegalArgumentException.class,
                () -> TimelineSchedulePolicy.apply(reversedExact, TimelineItemBO.builder()));

        var incompleteRange = new TimelineScheduleRequest();
        incompleteRange.setKind("EXACT_RANGE");
        incompleteRange.setStartTime(OffsetDateTime.parse("2027-03-01T09:00:00+08:00"));
        assertThrows(IllegalArgumentException.class,
                () -> TimelineSchedulePolicy.apply(incompleteRange, TimelineItemBO.builder()));

        var legacyPointShape = new TimelineScheduleRequest();
        legacyPointShape.setKind("EXACT_POINT");
        legacyPointShape.setStartTime(OffsetDateTime.parse("2027-03-01T09:00:00+08:00"));
        assertThrows(IllegalArgumentException.class,
                () -> TimelineSchedulePolicy.apply(legacyPointShape, TimelineItemBO.builder()));
    }
}
