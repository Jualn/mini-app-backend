package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

@Getter
@AllArgsConstructor
public enum ExamStatus {

    DRAFT(0, "草稿"),
    AUDITING(1, "审核中"),
    PUBLISHED(2, "已发布"),
    CLOSED(3,"报名截止"),
    ENDED(4, "已结束"),
    REJECTED(5, "审核不通过"),
    DELETED(6, "已删除");

    private final int code;
    private final String desc;

    public static ExamStatus fromCode(Integer code) {
        if (code == null) {
            return AUDITING;
        }
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElse(AUDITING);
    }
}
