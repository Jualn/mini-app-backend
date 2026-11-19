package cn.jualn.miniapp.module.activity.service;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityStagePolicyTest {
    final LocalDateTime now = LocalDateTime.of(2026,11,2,12,0);
    final ActivityParticipationPolicy admission = new ActivityParticipationPolicy();
    TimelineFactBO main(int precision, LocalDateTime start, LocalDateTime end) {
        return TimelineFactBO.builder().nodeType("ACTIVITY_START").startPrecision(precision).endPrecision(end == null ? 0 : precision)
                .startTime(start).endTime(end).build();
    }
    TimelineFactBO deadline(LocalDateTime at) {
        return TimelineFactBO.builder().nodeType("REGISTRATION_END").startPrecision(2).startTime(at).build();
    }
    @Test void exactMainUsesLeftClosedRightOpenRangeAndPointHasNoInventedEnd() {
        var start = main(2, now, now.plusHours(2));
        assertEquals("NOT_STARTED", ActivityStagePolicy.state(0,List.of(start),now.minusNanos(1)));
        assertEquals("ONGOING", ActivityStagePolicy.state(0,List.of(start),now));
        assertEquals("ENDED", ActivityStagePolicy.state(0,List.of(start),now.plusHours(2)));
        assertEquals("ONGOING", ActivityStagePolicy.state(0,List.of(main(2,now,null)),now.plusYears(2)));
    }
    @Test void calendarRangeIncludesLastDayWithoutInventingMidnightEndpoint() {
        var node = main(1,now.minusDays(1),now);
        assertEquals("ONGOING",ActivityStagePolicy.state(0,List.of(node),now.withHour(23).withMinute(59)));
        assertEquals("ENDED",ActivityStagePolicy.state(0,List.of(node),now.plusDays(1).withHour(0)));
        assertEquals("ONGOING",ActivityStagePolicy.state(0,List.of(main(1,now,null)),now.plusYears(1)));
    }
    @Test void ambiguousAndTextMainRemainUnknownAndLifecycleWins() {
        assertEquals("UNKNOWN",ActivityStagePolicy.state(0,List.of(main(2,now,null),main(2,now,null)),now));
        assertEquals("UNKNOWN",ActivityStagePolicy.state(0,List.of(TimelineFactBO.builder().nodeType("ACTIVITY_START").startPrecision(0).timeDescription("TBD").build()),now));
        assertEquals("CANCELLED",ActivityStagePolicy.state(2,List.of(),now));
        assertEquals("ENDED",ActivityStagePolicy.state(1,List.of(),now));
    }
    @Test void malformedMainCannotSupplyAStageOrCancellationUpperBound() {
        var malformed=main(2,now,now);assertEquals("UNKNOWN",ActivityStagePolicy.state(0,List.of(malformed),now));
        malformed=main(2,now,null);malformed.setEndPrecision(2);
        assertEquals("UNKNOWN",ActivityStagePolicy.state(0,List.of(malformed),now));
        assertFalse(admission.canCancel(0,false,false,List.of(malformed),now));
    }
    @Test void ordinaryAdmissionClosesAtMainStartWhileContinuousUsesDeadlineAndMainEnd() {
        var nodes = List.of(main(2,now,now.plusHours(3)),deadline(now.plusHours(2)));
        assertEquals("CLOSED",admission.evaluate(1,0,2,nodes,1,1L,now,false));
        assertEquals("OPEN",admission.evaluate(1,0,2,nodes,null,0L,now,true));
        assertEquals("CLOSED",admission.evaluate(1,0,2,nodes,null,0L,now.plusHours(2),true));
        assertEquals("UNAVAILABLE",admission.evaluate(1,0,2,nodes,null,0L,now.plusHours(3),true));
    }
    @Test void cancellationIgnoresPublicationAndRegistrationLowerBoundButHasReliableUpperBound() {
        assertTrue(admission.canCancel(0,false,false,List.of(main(2,now.plusHours(1),null),deadline(now.minusHours(1))),now));
        assertFalse(admission.canCancel(0,false,false,List.of(main(2,now,null),deadline(now.plusHours(1))),now));
        assertTrue(admission.canCancel(0,false,true,List.of(main(2,now.minusHours(1),null),deadline(now.plusHours(1))),now));
        assertFalse(admission.canCancel(0,false,true,List.of(deadline(now)),now));
        assertFalse(admission.canCancel(0,false,false,List.of(),now));
        assertFalse(admission.canCancel(2,false,false,List.of(deadline(now.plusDays(1))),now));
        assertFalse(admission.canCancel(0,true,false,List.of(deadline(now.plusDays(1))),now));
    }
    @Test void dateStartClosesOrdinaryCancellationOnTheStartDate() {
        var date = main(1,now.withHour(0),null);
        assertFalse(admission.canCancel(0,false,false,List.of(date),now.withHour(0)));
        assertTrue(admission.canCancel(0,false,false,List.of(date),now.minusDays(1)));
    }
}
