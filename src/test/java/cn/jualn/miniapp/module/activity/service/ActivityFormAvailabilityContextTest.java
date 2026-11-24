package cn.jualn.miniapp.module.activity.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.LocalDateTime;
import java.util.List;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import static org.assertj.core.api.Assertions.assertThat;

class ActivityFormAvailabilityContextTest {
    @Test void platformRegistrationNeedsNoSwitchButStillRequiresPublicationAndAnExactWindow() {
        new ApplicationContextRunner().withBean(ActivityParticipationPolicy.class).run(context -> {
            assertThat(context).hasNotFailed();
            var policy = context.getBean(ActivityParticipationPolicy.class);
            var now = LocalDateTime.of(2026, 10, 21, 12, 0);
            var end = new TimelineFactBO();
            end.setNodeType("REGISTRATION_END");
            end.setStartTime(now.plusHours(1));
            end.setStartPrecision(2);
            assertThat(policy.evaluate(1, 0, 2, List.of(end), null, 0L, now)).isEqualTo("OPEN");
            assertThat(policy.evaluate(0, 0, 2, List.of(end), null, 0L, now)).isEqualTo("UNAVAILABLE");
            assertThat(policy.evaluate(1, 0, 2, List.of(), null, 0L, now)).isEqualTo("UNAVAILABLE");
        });
    }
}
