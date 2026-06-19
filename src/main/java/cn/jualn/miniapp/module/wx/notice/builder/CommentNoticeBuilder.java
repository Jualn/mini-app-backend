package cn.jualn.miniapp.module.wx.notice.builder;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class CommentNoticeBuilder implements WxNoticeBuilder {

    @Override
    public NotifyType notifyType() {
        return NotifyType.COMMENTED_ME;
    }

    @Override
    public Map<String, Object> build(NotifyPayload payload) {
        Map<String, Object> data = new HashMap<>();
        data.put("title", payload.getTitle());
        data.put("content", payload.getContent());
        if (payload.getWxData() != null) {
            data.putAll(payload.getWxData().toMap());
        }
        putCommonFields(data, payload);
        return data;
    }
}
