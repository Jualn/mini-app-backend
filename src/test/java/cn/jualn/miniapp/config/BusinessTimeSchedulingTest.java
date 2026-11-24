package cn.jualn.miniapp.config;

import java.time.*;
import java.util.TimeZone;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ResourceLock("default-time-zone")
class BusinessTimeSchedulingTest {
    @ParameterizedTest @ValueSource(strings={"UTC", "America/Los_Angeles", "Asia/Shanghai"})
    void dailyScheduleUsesBeijingMidnightAndPreservesInstantClock(String hostZone) throws Exception {
        var original = TimeZone.getDefault();
        var scheduler = new SchedulingConfig().taskScheduler();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(hostZone));
            var zone = scheduler.getClock().getZone();
            assertEquals(ZoneId.of("Asia/Shanghai"), zone);
            var fixed = Clock.fixed(Instant.parse("2026-10-28T16:00:00Z"), zone);
            var trigger = new CronTrigger("0 25 4 * * *", zone);
            assertEquals(Instant.parse("2026-10-28T20:25:00Z"), trigger.nextExecution(new SimpleTriggerContext(fixed)));
            assertEquals(ZoneOffset.UTC, new TimeConfig().applicationClock().getZone());
            // Exercise the actual Logback date converter with the application file pattern.
            String xml;
            try (var stream = new ClassPathResource("logback-spring.xml").getInputStream()) {
                xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            var pattern = java.util.regex.Pattern.compile("name=\"TEXT_PATTERN\"\\s+value=\"([^\"]+)\"").matcher(xml);
            org.junit.jupiter.api.Assertions.assertTrue(pattern.find());
            var context = new LoggerContext();
            var layout = new PatternLayout(); layout.setContext(context); layout.setPattern(pattern.group(1).substring(pattern.group(1).indexOf("%d{"), pattern.group(1).indexOf("}") + 1)); layout.start();
            try {
                var event = new LoggingEvent("", context.getLogger("probe"), Level.INFO, "business time", null, null);
                event.setMDCPropertyMap(java.util.Map.of());
                event.setTimeStamp(fixed.instant().toEpochMilli());
                org.junit.jupiter.api.Assertions.assertTrue(layout.doLayout(event).startsWith("2026-10-29 00:00:00.000"));
            } finally { layout.stop(); context.stop(); }
        } finally {
            scheduler.shutdown();
            TimeZone.setDefault(original);
        }
    }
}
