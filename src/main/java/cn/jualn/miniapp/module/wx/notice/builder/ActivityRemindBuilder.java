package cn.jualn.miniapp.module.wx.notice.builder;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ActivityRemindBuilder implements WxNoticeBuilder {

    private final ActivityMapper activityMapper;

    @Override
    public NotifyType notifyType() {
        return NotifyType.ACTIVITY_REMIND;
    }

    @Override
    public Map<String, Object> build(NotifyPayload payload) {
        Map<String, Object> data = new HashMap<>();

        // 1. 触发路径：有 wxData 则作为基础
        if (payload.getWxData() != null) {
            data.putAll(payload.getWxData().toMap());
        }

        // 2. 广播路径：按 targetId 查 Activity 补全字段
        if (payload.getTargetId() != null) {
            Activity activity = activityMapper.selectById(payload.getTargetId());
            if (activity != null) {
                data.put("activityTitle", activity.getTitle());
                data.put("startTime", activity.getStartTime());
                data.put("location", activity.getLocation());
            }
        }

        // 3. DB查不到时用 payload 兜底
        data.putIfAbsent("activityTitle", payload.getTitle());

        // 4. 公共字段（targetId 用于 pagePath 变量替换）
        putCommonFields(data, payload);
        return data;
    }
}
