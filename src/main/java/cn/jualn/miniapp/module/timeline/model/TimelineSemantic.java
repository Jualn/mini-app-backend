package cn.jualn.miniapp.module.timeline.model;

import cn.jualn.miniapp.common.enums.TargetType;

import java.util.Locale;

/**
 * Stable business meaning stored in {@code timeline.node_type}.
 *
 * <p>The schedule/precision fields describe point/range/date/text structure, while label is
 * display text. Reminder and participation policies must use this semantic code and must never
 * infer it from label, display order, or the first exact node.</p>
 */
public enum TimelineSemantic {
    ACTIVITY_START,
    PUBLIC_EVENT_START,
    REGISTRATION_START,
    REGISTRATION_END,
    MATERIAL_SUBMISSION,
    PRELIMINARY,
    SEMIFINAL,
    FINAL,
    EXAM,
    RESULT,
    CERTIFICATE_COLLECTION,
    ADMISSION_TICKET,
    OTHER;

    public static final String ACTIVITY_INPUT_PATTERN =
            "ACTIVITY_START|REGISTRATION_START|REGISTRATION_END|MATERIAL_SUBMISSION|PRELIMINARY|SEMIFINAL|FINAL|EXAM|RESULT|CERTIFICATE_COLLECTION|ADMISSION_TICKET|OTHER";

    public static final String PUBLIC_EVENT_INPUT_PATTERN =
            "PUBLIC_EVENT_START|REGISTRATION_START|REGISTRATION_END|MATERIAL_SUBMISSION|PRELIMINARY|SEMIFINAL|FINAL|EXAM|RESULT|CERTIFICATE_COLLECTION|ADMISSION_TICKET|OTHER";

    public static TimelineSemantic requireKnown(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Timeline semantic is required");
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported Timeline semantic: " + value, exception);
        }
    }

    /** Keeps legacy/unknown persisted values readable without promoting them to a known semantic. */
    public static String normalizeForRead(String value) {
        try {
            return requireKnown(value).name();
        } catch (IllegalArgumentException exception) {
            return OTHER.name();
        }
    }

    public boolean supports(TargetType targetType) {
        if (this == ACTIVITY_START) {
            return targetType == TargetType.ACTIVITY;
        }
        if (this == PUBLIC_EVENT_START) {
            return targetType == TargetType.EXAM;
        }
        return targetType == TargetType.ACTIVITY || targetType == TargetType.EXAM;
    }
}
