package cn.jualn.miniapp.module.content.enums;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

@Getter
@AllArgsConstructor
public enum AdminContentType {
    POST("post", TargetType.POST),
    COMMENT("comment", TargetType.COMMENT);

    private final String code;
    private final TargetType targetType;

    public static AdminContentType fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
