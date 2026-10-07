package cn.jualn.miniapp.module.wx.assembler;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.wx.constant.WxNoticeKeys;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 把 NotifyPayload 转换成微信通知 {@code Map<String, Object>} 的组件。
 * <p>
 * 主要用于兼容旧版 {@link NotifyPayload} 的微信发送数据构造。
 * <p>
 * 注意：
 * 1. 这里不允许查业务表；业务 Factory 必须在创建通知时把稳定字段冻结到 payload 的 wxData 中。
 * 2. 这里不负责处理复杂的业务逻辑，只负责简单的数据转换和必要的校验。
 * 3. 这里不负责查用户表，用户相关的数据放到 consumer 里查并放到 payload 的 wxData 里。
 */
@Component
public class WxNoticePayloadAssembler {

    public Map<String, Object> assemble(NotifyPayload payload) {
        Map<String, Object> data = new HashMap<>();

        // NoticeData.toMap() 的字段优先级高于 NotifyPayload 的基础字段，避免冲突。
        if (payload.getWxData() != null) {
            payload.getWxData().toMap().forEach((key, value) ->
                    putNoConflict(data, key, value)
            );
        }

        // 队列消费中payload的基础字段，只有在 NoticeData.toMap() 没有提供时才放入，避免冲突。
        putIfAbsent(data, WxNoticeKeys.TITLE, payload.getTitle());
        putIfAbsent(data, WxNoticeKeys.CONTENT, payload.getContent());
        putIfAbsent(data, WxNoticeKeys.TARGET_ID, payload.getTargetId());
        putIfAbsent(data, WxNoticeKeys.TARGET_TYPE,
                payload.getTargetType() != null ? payload.getTargetType().name() : "");
        putIfAbsent(data, WxNoticeKeys.NOTIFY_TIME, LocalDateTime.now());

        return data;
    }

    private void putIfAbsent(Map<String, Object> data, String key, Object value) {
        data.putIfAbsent(key, value);
    }

    private void putNoConflict(Map<String, Object> data, String key, Object value) {
        if (data.containsKey(key) && !Objects.equals(data.get(key), value)) {
            throw new BusinessException(
                    ResultCode.WX_NOTICE_PAYLOAD_INVALID,
                    "微信通知字段重复冲突：" + key
            );
        }

        data.putIfAbsent(key, value);
    }
}
