package cn.jualn.miniapp.module.notify.service.impl;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService;

import cn.jualn.miniapp.module.notify.channel.*;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.infrastructure.async.job.RetryableRemoteException;
import cn.jualn.miniapp.module.notify.async.NotificationDeliveryJobPayload;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.entity.NotificationDelivery;
import cn.jualn.miniapp.module.notify.mapper.NotificationDeliveryMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.service.NotificationDeliveryService;
import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.service.SettingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryServiceImpl implements NotificationDeliveryService {
    public static final String WECHAT_CHANNEL = "WECHAT_OFFICIAL_ACCOUNT";

    private final NotificationDeliveryMapper deliveryMapper;
    private final NotificationMapper notificationMapper;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService inboxService;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final NotificationChannels channels;
    private final SettingService settingService;
    private final NotificationPreferenceMapper preferenceMapper;
    private final TransactionTemplate transactionTemplate;
    private final cn.jualn.miniapp.module.notify.service.NotificationEligibility activityEligibility;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean planCanonicalDeliveries(Notification notification, NotifyType type,
                                           Set<NotificationChannel> enabledChannels) {
        if (notification == null || notification.getId() == null || enabledChannels == null) return false;
        boolean visible = false;
        if (enabledChannels.contains(NotificationChannel.IN_APP)) {
            inboxService.enter(notification);
            deliveryMapper.insert(NotificationDelivery.builder()
                    .notificationId(notification.getId())
                    .channel(NotificationChannel.IN_APP.name())
                    .status("DELIVERED")
                    .attemptCount(0)
                    .deliveredAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))
                    .build());
            visible = true;
        }
        if (type != null && enabledChannels.contains(NotificationChannel.WECHAT_OFFICIAL_ACCOUNT)
                && channels.available(WECHAT_CHANNEL,type)) {
            createExternalDelivery(notification, type, WECHAT_CHANNEL);
        }
        if (type != null && enabledChannels.contains(NotificationChannel.WECHAT_MINI_PROGRAM)
                && channels.available(NotificationChannel.WECHAT_MINI_PROGRAM.name(),type)) {
            createExternalDelivery(notification, type, NotificationChannel.WECHAT_MINI_PROGRAM.name());
        }
        return visible;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void planWeChatDelivery(Notification notification, NotifyType type) {
        if (notification == null || notification.getId() == null || type == null
                || !channels.available(WECHAT_CHANNEL,type)) return;
        createExternalDelivery(notification, type, WECHAT_CHANNEL);
    }

    private void createExternalDelivery(Notification notification, NotifyType type, String channel) {
        NotificationDelivery delivery = NotificationDelivery.builder()
                .notificationId(notification.getId())
                .channel(channel)
                .status("PENDING")
                .attemptCount(0)
                .build();
        try {
            var snapshot = adapter(channel).prepare(type, readFrozenContent(notification));
            if (snapshot == null) throw new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID, "模板快照缺失");
            String json = objectMapper.writeValueAsString(snapshot);
            if (json.length() > 32768) throw new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID, "模板快照超过限制");
            delivery.setTemplateSnapshotVersion(1);
            delivery.setTemplateSnapshot(json);
        } catch (BusinessException invalid) {
            delivery.setStatus("FAILED");
            delivery.setResultCategory("validation");
            delivery.setLastErrorMessage("Template snapshot could not be prepared");
            deliveryMapper.insert(delivery);
            return; // External validation failure must not roll back the IN_APP entry.
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Template snapshot serialization failed");
        }
        deliveryMapper.insert(delivery);
        jobService.create(new JobDefinition(
                "notification.wechat-deliver", 2, ObservabilityContext.ensureOperationId(),
                "delivery:" + delivery.getId(), "notification-delivery", String.valueOf(delivery.getId()),
                new NotificationDeliveryJobPayload(delivery.getId()), LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), 4));
    }

    @Override
    public void execute(long deliveryId) {
        execute(deliveryId, null);
    }

    @Override
    public void execute(long deliveryId, JobExecutionContext context) {
        NotificationDelivery delivery = deliveryMapper.selectDelivery(deliveryId);
        if (delivery == null || isTerminal(delivery.getStatus())) {
            return;
        }
        if ("PROCESSING".equals(delivery.getStatus())) {
            finishWithOwnership(context, "UNKNOWN", () -> deliveryMapper.markTerminal(deliveryId, "UNKNOWN", "remote", null,
                    "Previous provider attempt ended without a persisted result"));
            return;
        }
        NotificationChannelAdapter adapter = adapter(delivery.getChannel());
        if (adapter == null) {
            finishWithOwnership(context, "SKIPPED", () -> deliveryMapper.markTerminal(deliveryId, "SKIPPED", "validation",
                    "channel_unavailable", "Delivery channel has no implemented provider adapter"));
            return;
        }
        if (delivery.getTemplateSnapshotVersion() == null && delivery.getTemplateSnapshot() == null) {
            finishWithOwnership(context, "SKIPPED", () -> deliveryMapper.markTerminal(deliveryId, "SKIPPED", "validation",
                    "legacy_template_unfrozen", "Historical template cannot be reconstructed safely"));
            return;
        }
        com.fasterxml.jackson.databind.JsonNode templateSnapshot;
        try {
            if (!Integer.valueOf(1).equals(delivery.getTemplateSnapshotVersion()) || delivery.getTemplateSnapshot() == null
                    || delivery.getTemplateSnapshot().length() > 32768) throw new IllegalArgumentException();
            templateSnapshot = objectMapper.readTree(delivery.getTemplateSnapshot());
            adapter.validateSnapshot(templateSnapshot);
            if (templateSnapshot.path("schemaVersion").asInt() != 1 || !delivery.getChannel().equals(templateSnapshot.path("channel").asText())) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            finishWithOwnership(context, "FAILED", () -> deliveryMapper.markTerminal(deliveryId, "FAILED", "validation",
                    "invalid_template_snapshot", "Frozen template snapshot is invalid"));
            return;
        }
        Notification notification = notificationMapper.selectById(delivery.getNotificationId());
        if (notification == null) {
            finishWithOwnership(context, "FAILED", () -> deliveryMapper.markTerminal(deliveryId, "FAILED", "validation",
                    "notification_missing", "Notification no longer exists"));
            return;
        }
        if (notificationMapper.isExpiredInApp(notification.getId())) {
            finishWithOwnership(context, "SKIPPED", () -> deliveryMapper.markTerminal(deliveryId, "SKIPPED", "validation",
                    "notification_expired", "Notification retention window ended"));
            return;
        }
        NotifyType notifyType = NotifyType.fromCode(notification.getType());
        if (notifyType == null) {
            finishWithOwnership(context, "FAILED", () -> deliveryMapper.markTerminal(deliveryId, "FAILED", "validation",
                    "unsupported_type", "Notification type is unknown"));
            return;
        }
        if (!isNotifyEnabled(notification, notifyType, delivery.getChannel())) {
            finishWithOwnership(context, "SKIPPED", () -> deliveryMapper.markTerminal(deliveryId, "SKIPPED", "validation",
                    "channel_disabled", "User disabled this notification channel"));
            return;
        }
        if (!adapter.isFrozenAvailable(templateSnapshot, notifyType)) {
            finishWithOwnership(context, "SKIPPED", () -> deliveryMapper.markTerminal(deliveryId, "SKIPPED", "validation",
                    "template_unavailable", "WeChat channel, template or subscription capability is unavailable"));
            return;
        }

        Map<String, Object> frozen;
        try { frozen = readFrozenContent(notification); }
        catch (BusinessException invalid) {
            finishWithOwnership(context, "FAILED", () -> deliveryMapper.markTerminal(deliveryId, "FAILED", "validation",
                    "invalid_snapshot", safeMessage(invalid)));
            return;
        }
        if (!activityEligibility.isEligible(notification, frozen)) {
            skipBusinessEligibility(deliveryId, context);
            return;
        }

        java.util.concurrent.atomic.AtomicBoolean attempted = new java.util.concurrent.atomic.AtomicBoolean();

        try {
            Runnable beforeProviderCall = () -> {
                if (context != null) context.prepareBatch();
                if (notificationMapper.isExpiredInApp(notification.getId())
                        || !isNotifyEnabled(notification, notifyType, delivery.getChannel())
                        || !adapter.isFrozenAvailable(templateSnapshot, notifyType)
                        || !activityEligibility.isEligible(notification, frozen)) throw new EligibilityLostException();
                boolean claimed = context == null
                        ? deliveryMapper.startProviderAttempt(deliveryId, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))) == 1
                        : Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                            context.requireOwnership();
                            return deliveryMapper.startProviderAttempt(deliveryId, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))) == 1;
                        }));
                if (!claimed) throw new AttemptNotClaimedException();
                attempted.set(true);
            };
            NotificationSendResult result = adapter.deliver(notification.getUserId(), templateSnapshot,
                    notification.getId().toString(), beforeProviderCall);
            if (result == null) result = NotificationSendResult.unknown();
            final NotificationSendResult outcome = result;
            switch (outcome.outcome()) {
                case ACCEPTED -> finishWithOwnership(context, "DELIVERED", () -> deliveryMapper.markDelivered(
                        deliveryId, outcome.messageId(), LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))));
                case UNKNOWN -> finishWithOwnership(context, "UNKNOWN", () -> deliveryMapper.markTerminal(
                        deliveryId, "UNKNOWN", "remote", null, "Provider outcome is unknown"));
                case REJECTED_PERMANENT -> finishWithOwnership(context, "FAILED", () -> deliveryMapper.markTerminal(
                        deliveryId, "FAILED", "remote", outcome.providerCode(), "Provider rejected this delivery"));
                case REJECTED_RETRYABLE -> {
                    finishWithOwnership(context, "RETRY", () -> deliveryMapper.markRetryable(
                            deliveryId, "remote", outcome.providerCode(), "Provider explicitly rejected the attempt temporarily"));
                    throw new RetryableRemoteException("Provider explicitly rejected a safely retryable attempt", null);
                }
            }
        } catch (AttemptNotClaimedException notClaimed) {
            return;
        } catch (EligibilityLostException lost) {
            skipBusinessEligibility(deliveryId, context);
        } catch (BusinessException knownLocal) {
            if (knownLocal.getResultCode() == ResultCode.WX_OPENID_NOT_BOUND
                    || knownLocal.getResultCode() == ResultCode.WX_NOTICE_SUBSCRIBE_INVALID
                    || knownLocal.getResultCode() == ResultCode.WX_NOTICE_SUBSCRIBE_EXPIRED) {
                finishWithOwnership(context, "SKIPPED", () -> deliveryMapper.markTerminal(deliveryId, "SKIPPED", "validation",
                        String.valueOf(knownLocal.getCode()), safeMessage(knownLocal)));
            } else {
                finishWithOwnership(context, "FAILED", () -> deliveryMapper.markTerminal(deliveryId, "FAILED", "validation",
                        String.valueOf(knownLocal.getCode()), safeMessage(knownLocal)));
            }
        } catch (RetryableRemoteException retryable) {
            throw retryable;
        } catch (cn.jualn.miniapp.infrastructure.async.job.JobYieldException
                | cn.jualn.miniapp.infrastructure.async.job.JobOwnershipLostException executionStopped) {
            throw executionStopped;
        } catch (RuntimeException uncertainFailure) {
            // Once an attempt is claimed, an unexpected failure may follow an accepted remote write.
            // Never return it to PENDING and repeat the provider effect.
            String result = attempted.get() ? "UNKNOWN" : "FAILED";
            finishWithOwnership(context, result, () -> deliveryMapper.markTerminal(deliveryId, result, "internal", null,
                    uncertainFailure.getClass().getSimpleName()));
        }
    }

    private void skipBusinessEligibility(long deliveryId, JobExecutionContext context) {
        finishWithOwnership(context, "SKIPPED", () -> deliveryMapper.markTerminal(deliveryId, "SKIPPED", "validation",
                "business_ineligible", "Current notification sending eligibility was lost"));
    }

    private static final class AttemptNotClaimedException extends RuntimeException { }

    private static final class EligibilityLostException extends RuntimeException { }

    private void finishWithOwnership(JobExecutionContext context, String result, java.util.function.IntSupplier update) {
        if (context == null) {
            if (update.getAsInt() == 1) { meters.counter("jualn.notification.delivery.result", "result", result).increment(); }
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            context.requireOwnership();
            if (update.getAsInt() != 1) throw new IllegalStateException("delivery result state changed");
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { meters.counter("jualn.notification.delivery.result", "result", result).increment(); }
                    });
        });
    }

    @Override
    public Map<String, Object> readFrozenContent(Notification notification) {
        if (notification.getContentSchemaVersion() == null || notification.getContentPayload() == null) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("title", notification.getTitle());
            fallback.put("content", notification.getContent());
            fallback.put("targetId", notification.getTargetId());
            fallback.put("targetType", notification.getTargetType());
            fallback.put("notificationId", notification.getId().toString());
            return fallback;
        }
        try {
            Map<String, Object> content = objectMapper.readValue(notification.getContentPayload(), new TypeReference<>() { });
            content.put("notificationId", notification.getId().toString());
            return content;
        } catch (Exception invalidSnapshot) {
            throw new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID,
                    "通知冻结内容无法解析");
        }
    }

    private boolean isTerminal(String status) {
        return "DELIVERED".equals(status) || "FAILED".equals(status)
                || "UNKNOWN".equals(status) || "SKIPPED".equals(status);
    }

    private String safeMessage(Throwable error) {
        // Never persist provider errmsg, URI, response body or arbitrary exception input.
        return error.getClass().getSimpleName();
    }

    private NotificationChannelAdapter adapter(String channel) {
        return channels.find(channel);
    }

    private boolean isNotifyEnabled(Notification notification, NotifyType type, String channel) {
        if (notification == null || notification.getUserId() == null || type == null) return false;
        if ("CANONICAL".equals(notification.getInboxGeneration())) {
            NotificationCategory category = categoryOf(type);
            return category != null && Boolean.TRUE.equals(preferenceMapper.selectEnabled(
                    notification.getUserId(), category.name(), channel));
        }
        if (!WECHAT_CHANNEL.equals(channel)) return false; // Legacy settings never grant Mini Program preference.
        UserSettingBO setting = settingService.getSettingByUserId(notification.getUserId());
        if (setting == null || type == null) return false;
        if (type == NotifyType.POST_COMMENTED || type == NotifyType.COMMENT_REPLIED) { type = type.legacyRepresentation(); }
        return switch (type.getCode()) {
            case 1 -> Boolean.TRUE.equals(setting.getNotifyComment());
            case 2 -> Boolean.TRUE.equals(setting.getNotifyReply());
            case 3 -> Boolean.TRUE.equals(setting.getNotifyLike());
            case 4 -> Boolean.TRUE.equals(setting.getNotifyActivityRemind());
            case 5 -> Boolean.TRUE.equals(setting.getNotifyExamRemind());
            case 6 -> Boolean.TRUE.equals(setting.getNotifyAuditResult());
            case 7 -> Boolean.TRUE.equals(setting.getNotifySystem());
            default -> false;
        };
    }

    private NotificationCategory categoryOf(NotifyType type) {
        if (type == null) return null;
        return switch (type) {
            case ACTIVITY_START_REMINDER, ACTIVITY_REGISTRATION_START_REMINDER, ACTIVITY_REGISTRATION_DEADLINE_REMINDER,
                    ACTIVITY_CANCELLED, ACTIVITY_TIME_CHANGED, ACTIVITY_LOCATION_CHANGED,
                    ACTIVITY_ENDED_EARLY -> NotificationCategory.ACTIVITY;
            case PUBLIC_EVENT_START_REMINDER, PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER,
                    PUBLIC_EVENT_CANCELLED, PUBLIC_EVENT_TIME_CHANGED,
                    PUBLIC_EVENT_LOCATION_CHANGED -> NotificationCategory.PUBLIC_EVENT;
            default -> null;
        };
    }

}
