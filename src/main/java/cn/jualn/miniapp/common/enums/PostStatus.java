package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 帖子状态枚举。
 */
@Getter
@AllArgsConstructor
public enum PostStatus {
    DRAFT(0, "草稿"),
    AUDITING(1, "审核中"),
    PUBLISHED(2, "已发布"),
    REJECTED(3, "审核拒绝"),
    DELETED(4, "已删除");

    private final int code;
    private final String desc;

    public static PostStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.code == code)
                .findFirst()
                .orElse(null);
    }
}