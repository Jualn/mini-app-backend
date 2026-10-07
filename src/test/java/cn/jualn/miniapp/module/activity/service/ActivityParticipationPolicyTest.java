package cn.jualn.miniapp.module.activity.service;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import java.util.List;

class ActivityParticipationPolicyTest {
    private final ActivityParticipationPolicy policy = new ActivityParticipationPolicy(new ActivityFormAvailability(true));
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 13, 12, 0);

    @Test
    void optionalStartAndExactWindowBoundaries() {
        assertEquals("OPEN", state(null, now.plusHours(1), 9L));
        assertEquals("OPEN", state(now, now.plusHours(1), 9L));
        assertEquals("NOT_OPEN", state(now.plusSeconds(1), now.plusHours(1), 9L));
        assertEquals("CLOSED", state(null, now, 9L));
        assertEquals("FULL", state(null, now.plusHours(1), 10L));
    }

    @Test
    void terminalAndPrimaryEndOverrideCapacityAndExternalEntry() {
        assertEquals("UNAVAILABLE", policy.evaluate(1, 1, 2, timeline(null, now.plusHours(1)), 10, 1L, now));
        assertEquals("EXTERNAL", policy.evaluate(1, 0, 3, List.of(), 10, null, now));
        assertEquals("NO_REGISTRATION", policy.evaluate(1, 0, 1, List.of(), null, null, now));
    }

    @Test
    void platformRequiresStandardExactNodesAndExternalDoesNotGuessAmbiguousWindows() {
        var dateEnd = TimelineItemDTO.builder().nodeType("REGISTRATION_END")
                .startTime(now.plusDays(1).toLocalDate().atStartOfDay()).startPrecision(1).build();
        assertEquals("UNAVAILABLE", policy.evaluate(1, 0, 2, List.of(dateEnd), 10, 1L, now));
        assertEquals("EXTERNAL", policy.evaluate(1, 0, 3, List.of(dateEnd), null, null, now));

        var exactEnd = TimelineItemDTO.builder().nodeType("REGISTRATION_END")
                .startTime(now.minusMinutes(1)).startPrecision(2).endPrecision(0).build();
        assertEquals("EXTERNAL", policy.evaluate(1, 0, 3, List.of(dateEnd, exactEnd), null, null, now));

        var exactRangeEnd = TimelineItemDTO.builder().nodeType("REGISTRATION_END")
                .startTime(now.minusHours(1)).endTime(now.plusHours(1))
                .startPrecision(2).endPrecision(2).build();
        assertEquals("UNAVAILABLE", policy.evaluate(1, 0, 2, List.of(exactRangeEnd), 10, 1L, now));
    }

    private String state(LocalDateTime start, LocalDateTime end, long submitted) {
        return policy.evaluate(1, 0, 2, timeline(start, end), 10, submitted, now);
    }

    private List<TimelineItemDTO> timeline(LocalDateTime start, LocalDateTime end) {
        var nodes = new java.util.ArrayList<TimelineItemDTO>();
        if (start != null) nodes.add(TimelineItemDTO.builder().nodeType("REGISTRATION_START")
                .startTime(start).startPrecision(2).endPrecision(0).build());
        nodes.add(TimelineItemDTO.builder().nodeType("REGISTRATION_END")
                .startTime(end).startPrecision(2).endPrecision(0).build());
        return nodes;
    }
}
