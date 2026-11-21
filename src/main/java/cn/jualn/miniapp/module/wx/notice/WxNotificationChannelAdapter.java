package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.module.notify.channel.NotificationChannelAdapter;
import cn.jualn.miniapp.module.notify.channel.NotificationSendResult;
import cn.jualn.miniapp.module.notify.channel.NotificationTemplateStatus;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.Predicate;

/** Converts the existing V1 WeChat snapshot and classified result at the provider boundary. */
public final class WxNotificationChannelAdapter implements NotificationChannelAdapter {
    private final String channel;
    private final WxNotificationTemplateRegistry registry;
    private final WxNoticeDeliveryAdapter provider;
    private final Predicate<NotifyType> configured;
    private final ObjectMapper json;
    public WxNotificationChannelAdapter(String channel, WxNoticeDeliveryAdapter provider,
            Predicate<NotifyType> configured, ObjectMapper json, WxNotificationTemplateRegistry registry) {
        this.channel=channel;this.provider=provider;this.configured=configured;this.json=json;this.registry=registry;
    }
    @Override public String configurationRevision(){return registry.revision();}
    @Override public NotificationTemplateStatus templateStatus(NotifyType type){
        var status=registry.status(type.name(),channel);
        if("READY".equals(status.status()) && !provider.isDeliveryAvailable(type))
            return new NotificationTemplateStatus("INVALID",java.util.List.of("ACCOUNT_MISMATCH"),null,null,null);
        return status;
    }
    @Override public java.util.List<NotificationTemplateStatus> subscriptionTemplates(NotifyType type){
        return provider.isDeliveryAvailable(type)?registry.subscriptionTemplates(type.name(),channel):java.util.List.of();
    }
    @Override public java.util.List<NotificationTemplateStatus> subscriptionTemplates(NotifyType type, boolean mentorQuestion) {
        if (!provider.isDeliveryAvailable(type)) return java.util.List.of();
        var template = registry.resolve(type.name(), channel, Map.of("mentorAnswer", mentorQuestion));
        if (template == null) return java.util.List.of();
        return registry.subscriptionTemplates(type.name(), channel).stream()
                .filter(item -> template.templateId().equals(item.templateId())).toList();
    }
    @Override public String channel() {return channel;}
    @Override public boolean isEnabled() {return provider.isChannelEnabled();}
    @Override public boolean isTemplateConfigured(NotifyType type) {return configured.test(type);}
    @Override public boolean isDeliveryAvailable(NotifyType type) {return provider.isDeliveryAvailable(type);}
    @Override public JsonNode prepare(NotifyType type,Map<String,Object> frozen) {
        var snapshot=provider.prepare(type,frozen);
        return snapshot==null?null:json.valueToTree(snapshot);
    }
    private WxDeliveryTemplateSnapshot decode(JsonNode snapshot) {
        try {return json.treeToValue(snapshot,WxDeliveryTemplateSnapshot.class);}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {throw new IllegalArgumentException("Invalid frozen channel snapshot",invalid);}
    }
    @Override public void validateSnapshot(JsonNode snapshot) {decode(snapshot);}
    @Override public boolean isFrozenAvailable(JsonNode snapshot,NotifyType type) {
        return provider.isFrozenAvailable(decode(snapshot),type);
    }
    @Override public NotificationSendResult deliver(Long user,JsonNode snapshot,String notificationId,Runnable before) {
        var result=provider.deliver(user,decode(snapshot),notificationId,before);
        return result==null?NotificationSendResult.unknown():new NotificationSendResult(
                NotificationSendResult.Outcome.valueOf(result.outcome().name()),result.providerCode(),result.messageId());
    }
}
