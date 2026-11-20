package cn.jualn.miniapp.module.notify.model;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import java.util.Arrays;
import java.util.List;

/** First-release preference ownership; independent of message-center box categories. */
public enum NotificationCategory {
    ACTIVITY, PUBLIC_EVENT, COMMENT, REPLY, LIKE, AUDIT_RESULT, SYSTEM, LEARNING_TASK, POINTS;

    public static NotificationCategory of(NotifyType type) {
        if (type == null) return null;
        return switch (type) {
            case ACTIVITY_START_REMINDER, ACTIVITY_REGISTRATION_START_REMINDER, ACTIVITY_REGISTRATION_DEADLINE_REMINDER,
                 ACTIVITY_CANCELLED, ACTIVITY_TIME_CHANGED, ACTIVITY_LOCATION_CHANGED, ACTIVITY_ENDED_EARLY -> ACTIVITY;
            case PUBLIC_EVENT_START_REMINDER, PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER, PUBLIC_EVENT_CANCELLED,
                 PUBLIC_EVENT_TIME_CHANGED, PUBLIC_EVENT_LOCATION_CHANGED -> PUBLIC_EVENT;
            case POST_COMMENTED -> COMMENT;
            case COMMENT_REPLIED -> REPLY;
            case POST_LIKED, COMMENT_LIKED -> LIKE;
            case AUDIT_RESULT, POST_CONTENT_HANDLED, POST_REPORT_RESOLVED -> AUDIT_RESULT;
            case SYSTEM -> SYSTEM;
            case LEARNING_TASK_ASSIGNED, LEARNING_TASK_ASSIGNMENT_ADDED, LEARNING_TASK_ASSIGNMENT_REVOKED,
                 LEARNING_TASK_FIRST_DEADLINE_REMINDER, LEARNING_TASK_SUPPLEMENT_DEADLINE_REMINDER,
                 LEARNING_TASK_REVIEW_APPROVED, LEARNING_TASK_REVIEW_REJECTED, LEARNING_TASK_CANCELLED,
                 LEARNING_TASK_DEADLINE_CHANGED -> LEARNING_TASK;
            case POINT_APPLICATION_APPROVED, POINT_APPLICATION_REJECTED, POINT_GRANT_REVERSED, POINT_GRANT_CORRECTED -> POINTS;
            default -> null;
        };
    }
    public static List<NotifyType> types() { return Arrays.stream(NotifyType.values()).filter(t -> of(t) != null).toList(); }
    public List<Integer> codes() { return types().stream().filter(t -> of(t) == this).map(NotifyType::getCode).toList(); }
}
