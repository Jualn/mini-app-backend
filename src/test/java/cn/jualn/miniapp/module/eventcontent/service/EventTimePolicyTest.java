package cn.jualn.miniapp.module.eventcontent.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class EventTimePolicyTest {
    @Test void dateDeadlineIncludesWholeDay() {
        var start = LocalDateTime.of(2026,9,1,0,0);
        var end = LocalDateTime.of(2026,9,7,0,0);
        assertEquals("OPEN", EventTimePolicy.registration(1,3,start,1,end,1,end.plusHours(23).plusMinutes(59)));
        assertEquals("CLOSED", EventTimePolicy.registration(1,3,start,1,end,1,end.plusDays(1)));
    }
    @Test void preciseDeadlineIsExclusive() {
        var end = LocalDateTime.of(2026,9,7,12,0);
        assertEquals("CLOSED", EventTimePolicy.registration(1,3,end.minusDays(2),2,end,2,end));
    }
    @Test void activityMayBeOngoingWhileRegistrationOpen() {
        var now = LocalDateTime.of(2026,9,7,12,0);
        assertEquals("ONGOING", EventTimePolicy.phase(1,now.minusDays(1),2,now.plusDays(1),2,now));
        assertEquals("OPEN", EventTimePolicy.registration(1,3,now.minusDays(2),2,now.plusHours(1),2,now));
    }
    @Test void unknownTimesDoNotInventAvailability() {
        var now = LocalDateTime.now();
        assertEquals("UNKNOWN", EventTimePolicy.phase(1,null,0,null,0,now));
        assertEquals("UNKNOWN", EventTimePolicy.registration(1,3,null,0,null,0,now));
        assertEquals("UNKNOWN", EventTimePolicy.registration(1,0,now.minusDays(1),2,now.plusDays(1),2,now));
    }
    @Test void cancelledOrUnpublishedIsUnavailable() {
        var now = LocalDateTime.now();
        assertEquals("CANCELLED", EventTimePolicy.phase(3,null,0,null,0,now));
        assertEquals("UNAVAILABLE", EventTimePolicy.registration(0,3,now.minusDays(1),2,now.plusDays(1),2,now));
        assertEquals("UNAVAILABLE", EventTimePolicy.registration(3,1,null,0,null,0,now));
    }
    @Test void datePrecisionRequiresMidnight() {
        assertThrows(BusinessException.class, () -> EventTimePolicy.precision(LocalDateTime.of(2026,9,7,12,0),1));
        assertEquals(0, EventTimePolicy.precision(null,null));
    }
    @Test void minuteStartCanEndOnSameCalendarDate() {
        var date = LocalDateTime.of(2026,9,7,0,0);
        assertDoesNotThrow(() -> EventTimePolicy.range(date.plusHours(9), date, 1));
        assertThrows(BusinessException.class, () -> EventTimePolicy.range(date.plusDays(1), date, 1));
        assertThrows(BusinessException.class, () -> EventTimePolicy.range(date.plusHours(9), date, 2));
    }
}
