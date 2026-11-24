package cn.jualn.miniapp.module.notify.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationTemplateStatus(String status, List<String> issues, String templateId, String title, String subscriptionKind) {
    public static NotificationTemplateStatus missing() { return new NotificationTemplateStatus("NOT_CONFIGURED",List.of("TEMPLATE_MISSING"),null,null,null); }
    public static NotificationTemplateStatus disabled() { return new NotificationTemplateStatus("DISABLED",List.of("CHANNEL_DISABLED"),null,null,null); }
}
