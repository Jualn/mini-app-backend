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
    SYSTEM(7,"系统通知");

    private final int code;
    private final String desc;

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
