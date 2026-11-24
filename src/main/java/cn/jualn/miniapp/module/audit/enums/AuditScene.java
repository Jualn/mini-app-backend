package cn.jualn.miniapp.module.audit.enums;

import cn.jualn.miniapp.common.enums.TargetType;
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

    USER_BACKGROUND(8, TargetType.USER, "用户背景图审核", "user_background"),
    ARTICLE(9, TargetType.ARTICLE, "资讯候选媒体审核", "article"),
    POST_EDIT(10, TargetType.POST, "帖子候选媒体审核", "post_edit"),
    OFFICIAL_CONTENT_CANDIDATE(11, TargetType.OFFICIAL_CONTENT, "官方宣传候选媒体校验", "official_content_candidate"),

    POINTS_MATERIAL(12, TargetType.USER, "积分私有图片审核", "points_material"),
    LEARNING_TASK_MATERIAL(13, TargetType.USER, "学习任务私有图片审核", "learning_task_material");

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
