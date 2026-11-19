package cn.jualn.miniapp.module.timeline.service;

import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
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

    private TimelineFactBO node(String semantic, LocalDateTime time, int precision, String location) {
        return TimelineFactBO.builder().nodeType(semantic).startTime(time)
                .startPrecision(precision).location(location).build();
    }

    @Test void activitySupportsConfirmedDeterminationWithdrawalAndAggregationWithoutExpandingPublicEvent() {
        var time = LocalDateTime.of(2030,1,1,9,0);
        var unknown = node("REGISTRATION_START",null,0,null);
        var exact = node("REGISTRATION_START",time,2,null);
        var changes = ActionableTimelineChangeDetector.activityTimeChanges(List.of(unknown),List.of(exact));
        org.junit.jupiter.api.Assertions.assertEquals("原待定",changes.get(0).before());
        org.junit.jupiter.api.Assertions.assertEquals("现待定",ActionableTimelineChangeDetector.activityTimeChanges(List.of(exact),List.of(unknown)).get(0).after());
        org.junit.jupiter.api.Assertions.assertEquals("已撤回",ActionableTimelineChangeDetector.activityTimeChanges(List.of(exact),List.of()).get(0).after());
        assertTrue(ActionableTimelineChangeDetector.timeChanges(List.of(unknown),List.of(exact),Set.of("REGISTRATION_START")).isEmpty());
        assertTrue(ActionableTimelineChangeDetector.activityTimeChanges(List.of(exact),List.of(exact)).isEmpty());
        assertTrue(ActionableTimelineChangeDetector.activityTimeChanges(List.of(exact),List.of(exact,exact)).isEmpty());
        assertTrue(ActionableTimelineChangeDetector.activityTimeChanges(List.of(node("OTHER",null,0,null)),List.of(node("OTHER",time,2,null))).isEmpty());
        assertFalse(ActionableTimelineChangeDetector.activityTimeChanges(List.of(node("ACTIVITY_START",time,1,null)),List.of(node("ACTIVITY_START",time,2,null))).isEmpty());
        var range = node("ACTIVITY_START",time,2,null); range.setEndTime(time.plusHours(1)); range.setEndPrecision(2);
        assertTrue(ActionableTimelineChangeDetector.activityTimeChanges(List.of(node("ACTIVITY_START",time,2,null)),List.of(range)).isEmpty());
        var changedEnd = node("ACTIVITY_START",time,2,null); changedEnd.setEndTime(time.plusHours(2)); changedEnd.setEndPrecision(2);
        assertFalse(ActionableTimelineChangeDetector.activityTimeChanges(List.of(range),List.of(changedEnd)).isEmpty());
        assertFalse(ActionableTimelineChangeDetector.activityTimeChanges(List.of(node("ACTIVITY_START",time,1,null)),List.of(node("ACTIVITY_START",time.plusDays(1),1,null))).isEmpty());
        assertTrue(ActionableTimelineChangeDetector.activityLocationChanged(null,"A"));
        assertTrue(ActionableTimelineChangeDetector.activityLocationChanged("A",null));
        assertFalse(ActionableTimelineChangeDetector.activityLocationChanged(" A ","A"));
        range.setEndTime(time.plusHours(2));
        assertTrue(ActionableTimelineChangeDetector.activityTimeChanges(List.of(range),List.of(range)).isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(2,ActionableTimelineChangeDetector.activityTimeChanges(List.of(),List.of(exact,range)).size());
    }
}
