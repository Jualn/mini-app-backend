package cn.jualn.miniapp.module.timeline.service;

import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionableTimelineChangeDetectorTest {
    @Test
    void onlyTwoConfirmedExactValuesProduceATimeChange() {
        LocalDateTime oldTime = LocalDateTime.of(2026, 10, 1, 9, 0);
        assertTrue(ActionableTimelineChangeDetector.exactTimeChanged(
                List.of(node("ACTIVITY_START", oldTime, 2, "A")),
                List.of(node("ACTIVITY_START", oldTime.plusHours(1), 2, "A")),
                Set.of("ACTIVITY_START")));
        assertFalse(ActionableTimelineChangeDetector.exactTimeChanged(
                List.of(node("ACTIVITY_START", oldTime, 1, "A")),
                List.of(node("ACTIVITY_START", oldTime.plusHours(1), 2, "A")),
                Set.of("ACTIVITY_START")));
        assertFalse(ActionableTimelineChangeDetector.exactTimeChanged(
                List.of(), List.of(node("ACTIVITY_START", oldTime, 2, "A")), Set.of("ACTIVITY_START")));
    }

    @Test
    void blankLocationTransitionRemainsAnUnresolvedProductBranch() {
        assertTrue(ActionableTimelineChangeDetector.effectiveLocationChanged(
                List.of(node("PUBLIC_EVENT_START", null, 0, "A")),
                List.of(node("PUBLIC_EVENT_START", null, 0, "B")), Set.of("PUBLIC_EVENT_START")));
        assertFalse(ActionableTimelineChangeDetector.effectiveValueChanged("", "新地点"));
        assertFalse(ActionableTimelineChangeDetector.effectiveLocationChanged(
                List.of(node("PUBLIC_EVENT_START", null, 0, null)),
                List.of(node("PUBLIC_EVENT_START", null, 0, "B")), Set.of("PUBLIC_EVENT_START")));
    }

    private TimelineItemDTO node(String semantic, LocalDateTime time, int precision, String location) {
        return TimelineItemDTO.builder().nodeType(semantic).startTime(time)
                .startPrecision(precision).location(location).build();
    }
}
