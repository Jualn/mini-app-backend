package cn.jualn.miniapp.module.wx.notice.builder;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ActivityRemindBuilder implements WxNoticeBuilder {

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

        // 历史 payload 只允许消费已冻结字段；微信映射层不得回查 Activity。
        data.putIfAbsent("activityTitle", payload.getTitle());

        // 公共字段（targetId 用于 pagePath 变量替换）
        putCommonFields(data, payload);
        return data;
    }
}
