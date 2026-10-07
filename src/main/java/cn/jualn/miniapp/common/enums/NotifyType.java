package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 通知类型
 */
@Getter
@AllArgsConstructor
public enum NotifyType {
    COMMENTED_ME(1,"评论了我"),
    REPLIED_ME(2,"回复了我"),
    LIKED_ME(3,"点赞了我"),
    ACTIVITY_REMIND(4,"活动提醒"),
    EXAM_REMIND(5,"考试提醒"),
    AUDIT_RESULT(6,"审核结果"),
    SYSTEM(7,"系统通知"),
    ACTIVITY_START_REMINDER(8, "活动开始提醒"),
    ACTIVITY_REGISTRATION_DEADLINE_REMINDER(9, "活动报名截止提醒"),
    PUBLIC_EVENT_START_REMINDER(10, "公共事项开始提醒"),
    PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER(11, "公共事项报名截止提醒"),
    ACTIVITY_CANCELLED(12, "活动已取消"),
    ACTIVITY_TIME_CHANGED(13, "活动时间已变更"),
    ACTIVITY_LOCATION_CHANGED(14, "活动地点已变更"),
    ACTIVITY_ENDED_EARLY(15, "活动提前结束"),
    PUBLIC_EVENT_CANCELLED(16, "公共事项已取消"),
    PUBLIC_EVENT_TIME_CHANGED(17, "公共事项时间已变更"),
    PUBLIC_EVENT_LOCATION_CHANGED(18, "公共事项地点已变更"),
    POST_COMMENTED(19, "帖子被评论"),
    COMMENT_REPLIED(20, "评论被回复"),
    POST_LIKED(21, "帖子被点赞"),
    COMMENT_LIKED(22, "评论被点赞");

    private final int code;
    private final String desc;

    public NotifyType legacyRepresentation() {
        return switch (this) {
            case POST_COMMENTED -> COMMENTED_ME;
            case COMMENT_REPLIED -> REPLIED_ME;
            case POST_LIKED, COMMENT_LIKED -> LIKED_ME;
            default -> this;
        };
    }

    public static NotifyType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code == code)
                .findFirst()
                .orElse(null);
    }
}
