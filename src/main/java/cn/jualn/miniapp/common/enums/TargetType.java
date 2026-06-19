package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 多态目标类型枚举
 * 用于timeline、comment等多态关联场景
 */
@Getter
@AllArgsConstructor
public enum TargetType {
    POST(1, "帖子","post"),
    ACTIVITY(2, "活动","activity"),
    EXAM(3, "考试信息","exam"),
    COMMENT(4,"评论","comment"),
    NOTIFICATION(5,"通知","notification"),
    USER(6,"用户","user"),;

    private final int code;
    private final String desc;
    private final String key;

    public static TargetType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code == code)
                .findFirst()
                .orElse(null);
    }
}
