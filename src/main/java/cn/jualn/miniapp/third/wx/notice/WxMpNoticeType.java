package cn.jualn.miniapp.third.wx.notice;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 服务号业务通知类型。
 * <p>
 * key 用于 application.yml 里的 wx.mp.notice-templates 配置。
 * 不要在业务代码里直接写模板 ID。
 */
@Getter
@AllArgsConstructor
public enum WxMpNoticeType {

    COMMENT_REPLY("comment_reply", "评论回复通知"),
    REPLY("reply", "回复通知"),
    LIKE("like", "点赞通知"),

    ACTIVITY_START("activity_start", "活动开始提醒"),
    ACTIVITY_APPLY("activity_apply", "活动报名通知"),

    EXAM_REMIND("exam_remind", "考试提醒"),
    AUDIT_RESULT("audit_result", "审核结果通知"),
    SYSTEM_NOTICE("system_notice", "系统通知");

    private final String key;
    private final String name;

    public static WxMpNoticeType fromKey(String key) {
        for (WxMpNoticeType type : values()) {
            if (type.key.equals(key)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知服务号通知类型：" + key);
    }
}