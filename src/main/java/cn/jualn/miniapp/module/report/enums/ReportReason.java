package cn.jualn.miniapp.module.report.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 举报原因。
 */
@Getter
@AllArgsConstructor
public enum ReportReason {

    ILLEGAL(1, "违规违法"),
    PORNOGRAPHIC(2, "色情低俗"),
    ADVERTISEMENT(3, "广告骚扰"),
    FALSE_INFO(4, "虚假信息"),
    OTHER(5, "其他");

    private final int code;
    private final String desc;

    public static ReportReason fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(reason -> reason.code == code)
                .findFirst()
                .orElse(null);
    }
}

