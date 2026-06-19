package cn.jualn.miniapp.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 微信订阅消息模板静态配置。
 *
 * <p>模板数量少时用枚举维护，不走 DB，减少一次查询。
 * 后续模板超过 10 个或需要运营动态配置时，再迁移到 DB 表，
 * 迁移时只需改 getByNotifyType() 实现，调用方不变。</p>
 *
 * <h2>fieldKeys 说明</h2>
 * <p>微信模板的占位符 key，顺序与 NotifyPayload.wxValues 的数组顺序一一对应。</p>
 *
 * <h2>接入新模板步骤</h2>
 * <ol>
 *   <li>微信后台申请模板，拿到 templateId</li>
 *   <li>在此枚举新增一个常量，填写 templateId / page / fieldKeys</li>
 *   <li>NotifyPayload 的 wxValues 按 fieldKeys 顺序组装值</li>
 * </ol>
 */
@Getter
@RequiredArgsConstructor
public enum WxMpNotifyTemplate {

    /** 评论通知：thing1=内容标题，thing2=评论摘要 */
    COMMENT(NotifyType.COMMENTED_ME,
            "zzmihiosaF4UVKQxeY_wakMcfQPpMnP_j1lLqMEzVug",
            "/subpkg_community/pages/detail/detail/",
            new String[]{"thing1", "thing2"}),

    /** 回复通知：thing1=原评论摘要，thing2=回复摘要 */
    REPLY(NotifyType.REPLIED_ME,
            "tmpl_reply_id_from_wx",
            "/pages/post/detail",
            new String[]{"thing1", "thing2"}),

    /** 点赞通知：thing1=内容标题，number2=点赞数 */
    LIKE(NotifyType.LIKED_ME,
            "tmpl_like_id_from_wx",
            "/pages/post/detail",
            new String[]{"thing1", "number2"}),

    /** 活动提醒：thing1=活动名称，time2=开始时间，thing3=场景（"距开始还有1小时"） */
    ACTIVITY_REMIND(NotifyType.ACTIVITY_REMIND,
            "tmpl_activity_remind_id_from_wx",
            "/pages/activity/detail",
            new String[]{"thing1", "time2", "thing3"}),

    /** 考试提醒：thing1=考试名称，time2=关键时间，thing3=场景（"报名即将截止"） */
    EXAM_REMIND(NotifyType.EXAM_REMIND,
            "tmpl_exam_remind_id_from_wx",
            "/pages/exam/detail",
            new String[]{"thing1", "time2", "thing3"}),

    /** 审核结果：thing1=内容标题，thing2=结果（"审核通过"/"审核拒绝"），thing3=备注 */
    AUDIT_RESULT(NotifyType.AUDIT_RESULT,
            "tmpl_audit_result_id_from_wx",
            "/pages/post/detail",
            new String[]{"thing1", "thing2", "thing3"}),

    /** 系统广播：thing1=公告标题，thing2=内容摘要 */
    SYSTEM_BROADCAST(NotifyType.SYSTEM,
            "tmpl_system_broadcast_id_from_wx",
            "/pages/notification/list",
            new String[]{"thing1", "thing2"});

    /** 对应 notification.type */
    private final NotifyType notifyType;
    /** 微信后台模板 ID */
    private final String templateId;
    /** 点击通知跳转页面路径 */
    private final String pagePath;
    /** 模板变量 key 顺序（与 wxValues 数组下标对应） */
    private final String[] fieldKeys;

    /**
     * 根据通知类型获取模板配置。
     *
     * @param notifyType notification.type 值
     * @return 对应模板；null 表示不推微信（如该类型未申请模板）
     */
    public static WxMpNotifyTemplate getByNotifyType(NotifyType notifyType) {
        for (WxMpNotifyTemplate t : values()) {
            if (t.notifyType == notifyType) return t;
        }
        return null;
    }
}