package cn.jualn.miniapp.module.notify.payload.data;


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
                NoticeKeys.TITLE, title != null ? title : "",
                NoticeKeys.CONTENT, content != null ? content : "",
                NoticeKeys.SCENE, scene != null ? scene : ""
        );
    }
}
