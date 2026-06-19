package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

@Getter
@AllArgsConstructor
public enum UserRole {
    USER(1, "普通用户"),
    OPR(2, "运营人员"),
    ADMIN(3, "管理员");

    private final int code;
    private final String desc;

    public static UserRole fromCode(Integer code) {
        if (code == null) {
            return USER;
        }
        return Arrays.stream(values())
                .filter(role -> role.code == code)
                .findFirst()
                .orElse(USER);
    }
}
