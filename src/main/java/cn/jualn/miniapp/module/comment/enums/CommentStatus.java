package cn.jualn.miniapp.module.comment.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * Comment status.
 */
@Getter
@AllArgsConstructor
public enum CommentStatus {
    PENDING(0, "待审核"),
    NORMAL(1, "正常"),
    REJECTED(2, "审核拒绝"),
    USER_DELETED(3, "用户删除"),
    ADMIN_DELETED(4, "管理员删除");

    private final int code;
    private final String desc;

    public static CommentStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElse(null);
    }
}
