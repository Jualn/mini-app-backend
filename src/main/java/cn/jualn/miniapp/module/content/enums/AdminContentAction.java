package cn.jualn.miniapp.module.content.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

@Getter
@AllArgsConstructor
public enum AdminContentAction {
    PIN("pin"),
    UNPIN("unpin"),
    FEATURE("feature"),
    UNFEATURE("unfeature"),
    TAKE_DOWN("take-down"),
    RESTORE("restore");

    private final String code;

    public static AdminContentAction fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
