package cn.jualn.miniapp.module.wx.notice.data;

import cn.jualn.miniapp.module.wx.constant.WxNoticeKeys;

import java.util.Map;

/**
 * 广播/系统通知数据载荷（NotifyPlan fan-out 路径使用）。
 * 对应 NotifyType.SYSTEM
 *
 * @param title   通知标题
 * @param content 通知内容
 * @param scene   场景说明
 */
public record BroadcastNoticeData(
        String title,
        String content,
        String scene
) implements NoticeData {

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                WxNoticeKeys.TITLE, title != null ? title : "",
                WxNoticeKeys.CONTENT, content != null ? content : "",
                WxNoticeKeys.SCENE, scene != null ? scene : ""
        );
    }
}
