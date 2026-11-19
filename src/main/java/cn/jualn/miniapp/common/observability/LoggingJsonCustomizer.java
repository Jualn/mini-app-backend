package cn.jualn.miniapp.common.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small native Boot customization; arbitrary MDC and fluent fields are excluded.
 */
public final class LoggingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]");
    private static final Set<String> CORRELATION = Set.of("traceId", "operationId", "messageId", "jobId", "attempt");
    private static final Set<String> EVENT_FIELDS = Set.of("method", "route", "status", "durationMs",
            "result", "errorCategory", "exceptionType");
    private static final Set<String> FIELDS = Set.of(
            "@timestamp", "@version", "message", "logger_name", "thread_name", "level", "level_value",
            "stack_trace", "traceId", "operationId", "messageId", "jobId", "attempt",
            "method", "route", "status", "durationMs", "result", "errorCategory", "exceptionType");

    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
        for (String key : CORRELATION) {
            members.add(key, event -> {
                String value = event.getMDCPropertyMap().get(key);
                return ObservabilityContext.valid(key, value) ? value : null;
            }).whenNotNull();
        }
        for (String key : EVENT_FIELDS) {
            members.add(key, event -> eventField(event, key)).whenNotNull();
        }
        // Boot's path filter predicate returns true for fields to REMOVE.
        members.applyingPathFilter(path -> !FIELDS.contains(path.toString()));
        members.applyingValueProcessor((path, value) -> {
            if (!(value instanceof String text)) return value;
            String field = path.toString();
            if ("@timestamp".equals(field)) {
                return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.ofHours(8)).toString();
            }
            if ("stack_trace".equals(field)) return text;
            return safeText(text);
        });
    }

    private static Object eventField(ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() == null) return null;
        for (var pair : event.getKeyValuePairs()) {
            if (key.equals(pair.key) && (pair.value instanceof String || pair.value instanceof Number
                    || pair.value instanceof Boolean)) return pair.value;
        }
        return null;
    }

    private static String safeText(String value) {
        String bounded = value.substring(0, Math.min(value.length(), 2048));
        return CONTROL.matcher(bounded).replaceAll("_");
    }
}
