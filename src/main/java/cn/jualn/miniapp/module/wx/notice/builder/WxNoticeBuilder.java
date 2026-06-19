package cn.jualn.miniapp.module.wx.notice.builder;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 微信服务号订阅通知数据构造器。
 * <p>
 * 各通知类型实现本接口，负责从 NotifyPayload 提取/组装微信模板所需字段，
 * 返回的 Map key 需要与 YAML 里 wx.mp.notice-templates.*.fields.*.source 一致。
 */
public interface WxNoticeBuilder {

    NotifyType notifyType();

    Map<String, Object> build(NotifyPayload payload);

    /**
     * 向 data 中写入公共字段：targetId、targetType、notifyTime，
     * 供 WxMpNoticeFieldRenderer.resolvePath 做 pagePath 变量替换。
     */
    default void putCommonFields(Map<String, Object> data, NotifyPayload payload) {
        data.putIfAbsent("targetId", payload.getTargetId());
        data.putIfAbsent("targetType", payload.getTargetType() != null ? payload.getTargetType().name() : "");
        data.putIfAbsent("notifyTime", LocalDateTime.now());
    }
}
