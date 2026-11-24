package cn.jualn.miniapp.module.notify.channel;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Delivery owns scheduling and outcomes; each channel owns its opaque frozen provider payload. */
public interface NotificationChannelAdapter {
    String channel();
    boolean isEnabled();
    String configurationRevision();
    NotificationTemplateStatus templateStatus(NotifyType type);
    default java.util.List<NotificationTemplateStatus> subscriptionTemplates(NotifyType type) { return java.util.List.of(templateStatus(type)); }
    default java.util.List<NotificationTemplateStatus> subscriptionTemplates(NotifyType type, boolean mentorQuestion) {
        return subscriptionTemplates(type);
    }
    boolean isTemplateConfigured(NotifyType type);
    boolean isDeliveryAvailable(NotifyType type);
    JsonNode prepare(NotifyType type, Map<String,Object> frozenContent);
    void validateSnapshot(JsonNode snapshot);
    boolean isFrozenAvailable(JsonNode snapshot, NotifyType type);
    NotificationSendResult deliver(Long userId, JsonNode snapshot, String notificationId, Runnable beforeProviderCall);
}
