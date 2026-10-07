package cn.jualn.miniapp.infrastructure.async.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryScheduleTest {
    private final RetrySchedule schedule = new RetrySchedule();

    @Test
    void usesBoundedExponentialStepsWithAtMostTwentyPercentJitter() {
        assertRange(schedule.delay(1), Duration.ofSeconds(10), Duration.ofSeconds(12));
        assertRange(schedule.delay(2), Duration.ofMinutes(1), Duration.ofSeconds(72));
        assertRange(schedule.delay(3), Duration.ofMinutes(5), Duration.ofMinutes(6));
        assertRange(schedule.delay(20), Duration.ofMinutes(30), Duration.ofMinutes(36));
    }

    private void assertRange(Duration actual, Duration minimum, Duration maximum) {
        assertTrue(actual.compareTo(minimum) >= 0, () -> "below minimum: " + actual);
        assertTrue(actual.compareTo(maximum) <= 0, () -> "above maximum: " + actual);
    }
}
