package cn.jualn.miniapp.module.wx.notice.data;

import cn.jualn.miniapp.module.wx.constant.WxNoticeKeys;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * 活动提醒通知数据载荷。
 * 对应 NotifyType.ACTIVITY_REMIND
 *
 * @param activityId    活动ID → pagePath 变量 {activityId}
 * @param activityTitle 活动名称 → YAML source: activityTitle
 * @param startTime     开始时间 → YAML source: startTime
 * @param location      活动地点 → YAML source: location（有 default-value）
 */
public record ActivityRemindNoticeData(
        Long activityId,
        String activityTitle,
        LocalDateTime startTime,
        String location
) implements NoticeData {

    public ActivityRemindNoticeData {
        Objects.requireNonNull(activityId, "activityId must not be null");
        Objects.requireNonNull(activityTitle, "activityTitle must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
    }

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                WxNoticeKeys.ACTIVITY_ID, activityId,
                WxNoticeKeys.ACTIVITY_TITLE, activityTitle,
                WxNoticeKeys.START_TIME, startTime,
                WxNoticeKeys.LOCATION, location != null ? location : ""
        );
    }
}
