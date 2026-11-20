package cn.jualn.miniapp.module.notify.bo;

public final class NotificationCatalogBO {
    private NotificationCatalogBO() {}

    public record Options(java.time.OffsetDateTime evaluatedAt,String configurationRevision,int maxTemplatesPerRequest,java.util.List<Option> items) {}
    public record Option(String templateId,String title,String subscriptionKind,java.util.List<String> categories,java.util.List<String> notificationTypes) {}
    public record Status(java.time.OffsetDateTime evaluatedAt,String configurationRevision,java.util.List<StatusItem> items) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record StatusItem(String category,String notificationType,String channel,String status,java.util.List<String> issues,String templateId,String title,String subscriptionKind) {}
    public record Summary(java.time.OffsetDateTime evaluatedAt,java.time.OffsetDateTime from,java.time.OffsetDateTime until,java.util.List<SummaryItem> items) {}
    public record SummaryItem(String notificationType,String channel,String outcome,String reason,long count) {}
}
