package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

@Getter
@AllArgsConstructor
public enum ActivityStatus {

    /** 目前表示正在状态，不是草稿，因为活动状态在查询的时候经过计算展示 */
    DRAFT(0, "草稿"),
    PENDING(1, "审核中"),
    SIGNUP(2, "报名中"),
    ONGOING(3, "进行中"),
    ENDED(4, "已结束"),
    CANCELED(5, "已取消"),
    REJECTED(6, "审核不通过"),
    DELETED(7, "已删除");

    private final int code;
    private final String desc;

    public static ActivityStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElse(null);
    }
}
