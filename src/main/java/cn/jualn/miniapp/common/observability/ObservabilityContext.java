package cn.jualn.miniapp.common.observability;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Defines the bounded MDC fields used for execution correlation.
 * Business identifiers remain explicit method/message data and must not be added here ad hoc.
 */
public final class ObservabilityContext {

    public static final String TRACE_ID = "traceId";
    public static final String OPERATION_ID = "operationId";
    public static final String MESSAGE_ID = "messageId";
    public static final String JOB_ID = "jobId";
    public static final String ATTEMPT = "attempt";

    private static final Set<String> PROPAGATED_KEYS = Set.of(
            TRACE_ID, OPERATION_ID, MESSAGE_ID, JOB_ID, ATTEMPT);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private ObservabilityContext() {
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String ensureOperationId() {
        String operationId = MDC.get(OPERATION_ID);
        if (operationId == null) {
            operationId = newTraceId();
            MDC.put(OPERATION_ID, operationId);
        }
        return operationId;
    }

    public static Map<String, String> capture() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String key : PROPAGATED_KEYS) {
            String value = MDC.get(key);
            if (valid(key, value)) {
                snapshot.put(key, value);
            }
        }
        return snapshot;
    }

    public static void install(Map<String, String> context) {
        clear();
        if (context != null && !context.isEmpty()) {
            context.forEach((key, value) -> {
                if (PROPAGATED_KEYS.contains(key) && valid(key, value)) {
                    MDC.put(key, value);
                }
            });
        }
    }

    public static void clear() {
        for (String key : PROPAGATED_KEYS) {
            MDC.remove(key);
        }
    }

    static boolean valid(String key, String value) {
        if (value == null) return false;
        if (!ATTEMPT.equals(key)) return SAFE_ID.matcher(value).matches();
        try {
            return value.length() <= 10 && Integer.parseInt(value) >= 1;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
