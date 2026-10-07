package cn.jualn.miniapp.module.timeline.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardTimelinePolicyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 12, 0);

    @Test
    void currentStageWinsByDisplayOrderAndNodeKeyRegardlessOfTypeOrTitle() {
        var laterOrder = exact("a", "REGISTRATION_END", "看起来像截止", NOW.minusHours(1), NOW.plusHours(1), 2);
        var selectedByKey = date("b", "OTHER", "普通文字", NOW.minusDays(1), NOW.toLocalDate().atStartOfDay(), 1);
        var firstByKey = date("a", "EXAM", "并不解析标题", NOW.minusDays(1), NOW.toLocalDate().atStartOfDay(), 1);

        assertEquals("a", CardTimelinePolicy.select(
                List.of(laterOrder, selectedByKey, firstByKey), NOW).getNodeKey());
    }

    @Test
    void exactEndIsExclusiveAndDateEndIsInclusive() {
        var endedExact = exact("exact", "OTHER", "", NOW.minusHours(1), NOW, 0);
        var currentDate = date("date", "OTHER", "", NOW.minusDays(1), NOW.toLocalDate().atStartOfDay(), 5);

        assertEquals("date", CardTimelinePolicy.select(List.of(endedExact, currentDate), NOW).getNodeKey());
    }

    @Test
    void nearestFutureUsesCalendarDateThenDateBeforeExactThenStableOrder() {
        var exactMorning = exact("exact-1", "OTHER", "", NOW.plusDays(1).withHour(9), null, 0);
        var exactAfternoon = exact("exact-2", "OTHER", "", NOW.plusDays(1).withHour(15), null, 0);
        var date = date("date", "OTHER", "", NOW.plusDays(1), null, 9);

        assertEquals("date", CardTimelinePolicy.select(
                List.of(exactAfternoon, exactMorning, date), NOW).getNodeKey());
    }

    @Test
    void textIsFallbackOnlyAndHistoricalComputableNodesAreNotSelected() {
        var historical = exact("past", "FINAL", "已结束", NOW.minusDays(2), NOW.minusDays(1), 0);
        var textB = text("b", "RESULT", "等待通知", 1);
        var textA = text("a", "OTHER", "时间待定", 1);

        assertEquals("a", CardTimelinePolicy.select(List.of(historical, textB, textA), NOW).getNodeKey());
        assertNull(CardTimelinePolicy.select(List.of(historical), NOW));
    }

    @Test
    void instantaneousExactIsCurrentOnlyAtItsExactInstant() {
        var instant = exact("instant", "OTHER", "", NOW, null, 0);
        assertEquals("instant", CardTimelinePolicy.select(List.of(instant), NOW).getNodeKey());
        assertNull(CardTimelinePolicy.select(List.of(instant), NOW.plusNanos(1)));
    }

    @Test
    void zeroLengthExactRangeRemainsRangeAndIsCurrentOnlyAtItsBoundary() {
        var range = exact("range", "OTHER", "", NOW, NOW, 0);
        assertEquals("EXACT_RANGE", TimelineSchedulePolicy.view(range).kind());
        assertEquals("range", CardTimelinePolicy.select(List.of(range), NOW).getNodeKey());
        assertNull(CardTimelinePolicy.select(List.of(range), NOW.plusNanos(1)));
    }

    private TimelineItemDTO exact(String key, String type, String title, LocalDateTime start,
            LocalDateTime end, int order) {
        return TimelineItemDTO.builder().nodeKey(key).nodeType(type).label(title)
                .startPrecision(2).endPrecision(end == null ? 0 : 2)
                .startTime(start).endTime(end).sortOrder(order).build();
    }

    private TimelineItemDTO date(String key, String type, String title, LocalDateTime start,
            LocalDateTime end, int order) {
        return TimelineItemDTO.builder().nodeKey(key).nodeType(type).label(title)
                .startPrecision(1).endPrecision(end == null ? 0 : 1)
                .startTime(start).endTime(end).sortOrder(order).build();
    }

    private TimelineItemDTO text(String key, String type, String description, int order) {
        return TimelineItemDTO.builder().nodeKey(key).nodeType(type).label("irrelevant")
                .startPrecision(0).endPrecision(0).timeDescription(description)
                .sortOrder(order).build();
    }
}
