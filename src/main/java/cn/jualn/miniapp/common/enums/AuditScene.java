package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AuditScene {

    POST(1, TargetType.POST, "帖子审核", "post"),

    ACTIVITY(2, TargetType.ACTIVITY, "活动审核", "activity"),

    EXAM(3, TargetType.EXAM, "考试信息审核", "exam"),

    COMMENT(4, TargetType.COMMENT, "评论审核", "comment"),

    USER_NICKNAME(5, TargetType.USER, "用户昵称审核", "user_nickname"),

    USER_AVATAR(6, TargetType.USER, "用户头像审核", "user_avatar"),

    USER_BIO(7, TargetType.USER, "用户简介审核", "user_bio"),

    USER_BACKGROUND(8, TargetType.USER, "用户背景图审核", "user_background");

    private final int code;
    private final TargetType targetType;
    private final String desc;
    private final String key;

    public static AuditScene fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (AuditScene scene : values()) {
            if (scene.code == code) {
                return scene;
            }
        }
        return null;
    }
}
