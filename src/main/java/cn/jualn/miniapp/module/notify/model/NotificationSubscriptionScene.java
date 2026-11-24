package cn.jualn.miniapp.module.notify.model;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import java.util.List;

/** User intent at consent time, independent of delivery event identity. */
public enum NotificationSubscriptionScene {
    POST_PUBLISH, MENTOR_QUESTION_PUBLISH, COMMENT_PUBLISH, MENTOR_ANSWER_PUBLISH,
    ACTIVITY_FOLLOW, PUBLIC_EVENT_FOLLOW, ACTIVITY_REGISTER, LEARNING_SUBMIT, POINTS_APPLY;

    public boolean resourceRequired() {
        return this == ACTIVITY_FOLLOW || this == PUBLIC_EVENT_FOLLOW || this == ACTIVITY_REGISTER;
    }
    public NotificationCategory category() {
        return switch (this) {
            case POST_PUBLISH, MENTOR_QUESTION_PUBLISH -> NotificationCategory.COMMENT;
            case COMMENT_PUBLISH, MENTOR_ANSWER_PUBLISH -> NotificationCategory.REPLY;
            case ACTIVITY_FOLLOW, ACTIVITY_REGISTER -> NotificationCategory.ACTIVITY;
            case PUBLIC_EVENT_FOLLOW -> NotificationCategory.PUBLIC_EVENT;
            case LEARNING_SUBMIT -> NotificationCategory.LEARNING_TASK;
            case POINTS_APPLY -> NotificationCategory.POINTS;
        };
    }
    public List<NotifyType> types() {
        return switch (this) {
            case POST_PUBLISH, MENTOR_QUESTION_PUBLISH -> List.of(NotifyType.POST_COMMENTED);
            case COMMENT_PUBLISH, MENTOR_ANSWER_PUBLISH -> List.of(NotifyType.COMMENT_REPLIED);
            case LEARNING_SUBMIT -> List.of(NotifyType.LEARNING_TASK_REVIEW_APPROVED, NotifyType.LEARNING_TASK_REVIEW_REJECTED);
            case POINTS_APPLY -> List.of(NotifyType.POINT_APPLICATION_APPROVED, NotifyType.POINT_APPLICATION_REJECTED);
            default -> List.of();
        };
    }
}
