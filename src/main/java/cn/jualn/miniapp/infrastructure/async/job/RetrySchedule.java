package cn.jualn.miniapp.infrastructure.async.job;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetrySchedule {
    private static final Duration[] STEPS = {
            Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30)
    };

    public Duration delay(int completedAttempt) {
        Duration base = STEPS[Math.min(Math.max(completedAttempt - 1, 0), STEPS.length - 1)];
        long jitter = ThreadLocalRandom.current().nextLong(base.toMillis() / 5 + 1);
        return base.plusMillis(jitter);
    }
}
