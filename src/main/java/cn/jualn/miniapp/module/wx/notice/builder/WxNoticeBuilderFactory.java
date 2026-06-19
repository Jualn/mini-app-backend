package cn.jualn.miniapp.module.wx.notice.builder;

import cn.jualn.miniapp.common.enums.NotifyType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 微信通知 Builder 工厂，按 NotifyType 路由到对应 Builder。
 * <p>
 * 新增通知类型时只需实现 WxNoticeBuilder 并标注对应的 NotifyType，
 * 无需修改本类。
 */
@Component
public class WxNoticeBuilderFactory {

    private final Map<NotifyType, WxNoticeBuilder> builderMap;

    public WxNoticeBuilderFactory(List<WxNoticeBuilder> builders) {
        this.builderMap = builders.stream()
                .collect(Collectors.toMap(WxNoticeBuilder::notifyType, Function.identity()));
    }

    public WxNoticeBuilder get(NotifyType type) {
        return builderMap.get(type);
    }
}
