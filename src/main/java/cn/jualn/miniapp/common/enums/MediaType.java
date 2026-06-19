package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MediaType {

    URL(1),
    IMAGE(2),
    PDF(3),
    WORD(4);

    private final int code;

    public static MediaType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (MediaType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null; // 默认返回 null 或抛出异常，视业务需求而定
    }
}
