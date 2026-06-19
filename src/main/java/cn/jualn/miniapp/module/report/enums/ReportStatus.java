package cn.jualn.miniapp.module.report.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 举报处理状态。
 */
@Getter
@AllArgsConstructor
public enum ReportStatus {

    PENDING(0, "待处理"),
    VIOLATION_HANDLED(1, "违规已处理"),
    NORMAL_HANDLED(2, "正常已处理");

    private final int code;
    private final String desc;

    public static ReportStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElse(null);
    }
}

