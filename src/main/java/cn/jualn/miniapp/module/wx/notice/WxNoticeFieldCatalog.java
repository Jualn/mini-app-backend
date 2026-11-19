package cn.jualn.miniapp.module.wx.notice;

import java.util.Map;
import java.util.Set;

/** Explicit frozen keys, not Java properties. Missing historical facts stay missing. */
public final class WxNoticeFieldCatalog {
    private WxNoticeFieldCatalog() { }
    private static final Set<String> COMMON = Set.of("title", "content", "targetId", "targetType", "notificationId", "notifyTime", "scene");
    private static final Map<String, Set<String>> LEGACY = Map.of(
            "audit_result", Set.of("auditContent", "auditResult", "auditReason", "auditTime"),
            "comment_reply", Set.of("postId", "postTitle", "replyUserName", "replyContent", "replyTime"),
            "reply", Set.of("postId", "originalContent", "replyUserName", "replyContent", "replyTime"),
            "activity_start", Set.of("activityId", "activityTitle", "startTime", "location"),
            "activity_apply", Set.of("activityId", "activityTitle", "startTime", "location"),
            "like", Set.of("postId", "postTitle"),
            "exam_remind", Set.of(),
            "system_notice", Set.of());
    private static final Set<String> CANONICAL = Set.of(
            "ACTIVITY_REGISTRATION_START_REMINDER", "ACTIVITY_REGISTRATION_DEADLINE_REMINDER", "ACTIVITY_START_REMINDER",
            "ACTIVITY_CANCELLED", "ACTIVITY_TIME_CHANGED", "ACTIVITY_LOCATION_CHANGED", "ACTIVITY_ENDED_EARLY",
            "PUBLIC_EVENT_START_REMINDER", "PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER",
            "PUBLIC_EVENT_CANCELLED", "PUBLIC_EVENT_TIME_CHANGED", "PUBLIC_EVENT_LOCATION_CHANGED");
    public static boolean knownType(String type) { return type != null && (CANONICAL.contains(type) || LEGACY.containsKey(type)); }
    public static boolean allows(String type, String source) {
        if (source == null) return false;
        if (COMMON.contains(source)) return true;
        if (LEGACY.containsKey(type)) return LEGACY.get(type).contains(source);
        if (!CANONICAL.contains(type)) return false;
        if (Set.of("subjectTitle", "changesText").contains(source)) return true;
        return switch (type) {
            case "ACTIVITY_START_REMINDER" -> Set.of("activityId", "activityTitle", "startTime", "location", "businessTime").contains(source);
            case "ACTIVITY_REGISTRATION_START_REMINDER" -> Set.of("registrationStartTime", "businessTime", "location").contains(source);
            case "ACTIVITY_REGISTRATION_DEADLINE_REMINDER", "PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER" -> Set.of("registrationDeadlineTime", "businessTime", "location").contains(source);
            case "PUBLIC_EVENT_START_REMINDER" -> Set.of("publicEventStartTime", "businessTime", "location").contains(source);
            default -> false;
        };
    }
    public static Object read(Map<String, Object> frozen, String source) {
        if ("subjectTitle".equals(source) || "changesText".equals(source)) {
            Object raw = frozen.get("presentation");
            if (!(raw instanceof Map<?, ?> presentation)) return null;
            if ("subjectTitle".equals(source)) return presentation.get("subjectTitle");
            Object changes = presentation.get("changes");
            if (!(changes instanceof java.util.List<?> list)) return null;
            return list.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .map(c -> c.get("label") + ": " + c.get("before") + " → " + c.get("after"))
                    .collect(java.util.stream.Collectors.joining("；"));
        }
        return frozen.get(source);
    }
}
