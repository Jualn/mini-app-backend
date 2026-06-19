package cn.jualn.miniapp.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ActivityCategory {

    OTHER(0,"其他"),
    TALENT_SHOW(1,"文体比赛"),
    VOLUNTEER_SERVICE(2,"志愿公益"),
    POLITICAL_THEME(3,"思政主题"),
    ACADEMIC_SEMINAR(4,"学术讲座"),
    SPORTS_EVENT(5,"体育运动"),
//    SOCIAL_PRACTICE(6,"社会实践"),



    ;
    private final Integer code;
    private final String desc;

    public static ActivityCategory fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ActivityCategory c : values()) {
            if (c.code.equals(code)) {
                return c;
            }
        }
        return null; // 或抛出异常，视业务需求而定
    }
}
