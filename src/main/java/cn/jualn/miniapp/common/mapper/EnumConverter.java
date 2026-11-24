package cn.jualn.miniapp.common.mapper;

import cn.jualn.miniapp.common.enums.TargetType;
import org.springframework.stereotype.Component;

/**
 * Conversion of shared polymorphic subject identifiers only.
 */
@Component
public class EnumConverter {
    public TargetType toTargetType(Integer code) {
        return TargetType.fromCode(code);
    }

    public Integer fromTargetType(TargetType type) {
        return type == null ? null : type.getCode();
    }
}
