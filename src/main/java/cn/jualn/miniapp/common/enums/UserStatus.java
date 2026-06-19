package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 用户状态枚举。
 */
@Getter
@AllArgsConstructor
public enum UserStatus {
    NORMAL(1, "正常"),
    MUTED(2, "禁言"),
    BANNED(3, "封禁");

    private final int code;
    private final String desc;

    public static UserStatus fromCode(Integer code) {
        if (code == null) {
            return NORMAL;
        }
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElse(NORMAL);
    }
}

