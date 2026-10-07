package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
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
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse;
import cn.jualn.miniapp.third.wx.notice.WxMpNoticeType;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientException;

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
    private final cn.jualn.miniapp.module.notify.service.NotificationInboxService inboxService;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final WxMpNoticeSendService sendService;
    private final SettingService settingService;
    private final NotificationPreferenceMapper preferenceMapper;
    private final TransactionTemplate transactionTemplate;

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
                    .deliveredAt(LocalDateTime.now())
                    .build());
            visible = true;
        }
        WxMpNoticeType noticeType = toMpNoticeType(type);
        if (enabledChannels.contains(NotificationChannel.WECHAT_OFFICIAL_ACCOUNT)
                && noticeType != null && sendService.isNotificationDeliveryAvailable(noticeType)
                && hasRequiredOfficialAccountPayload(notification, type)) {
            createWeChatDelivery(notification);
        }
        // Mini Program subscription messages remain unavailable until their own template and
        // one-time permission/consumption contract is implemented.
        return visible;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void planWeChatDelivery(Notification notification, NotifyType type) {
        WxMpNoticeType noticeType = toMpNoticeType(type);
        if (notification == null || notification.getId() == null || noticeType == null) {
            return;
        }
        if (!sendService.isNotificationDeliveryAvailable(noticeType)) {
            return;
        }
        createWeChatDelivery(notification);
    }

    private void createWeChatDelivery(Notification notification) {
        NotificationDelivery delivery = NotificationDelivery.builder()
                .notificationId(notification.getId())
                .channel(WECHAT_CHANNEL)
                .status("PENDING")
                .attemptCount(0)
                .build();
        deliveryMapper.insert(delivery);
        jobService.create(new JobDefinition(
                "notification.wechat-deliver", 2, ObservabilityContext.ensureOperationId(),
                "delivery:" + delivery.getId(), "notification-delivery", String.valueOf(delivery.getId()),
                new NotificationDeliveryJobPayload(delivery.getId()), LocalDateTime.now(), 4));
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
        Notification notification = notificationMapper.selectById(delivery.getNotificationId());
        if (notification == null) {
            finishWithOwnership(context, "FAILED", () -> deliveryMapper.markTerminal(deliveryId, "FAILED", "validation",
                    "notification_missing", "Notification no longer exists"));
            return;
        }
        NotifyType notifyType = NotifyType.fromCode(notification.getType());
        WxMpNoticeType noticeType = toMpNoticeType(notifyType);
        if (noticeType == null) {
            finishWithOwnership(context, "FAILED", () -> deliveryMapper.markTerminal(deliveryId, "FAILED", "validation",
                    "unsupported_type", "Notification type has no WeChat mapping"));
            return;
        }
        if (!isNotifyEnabled(notification, notifyType)) {
            finishWithOwnership(context, "SKIPPED", () -> deliveryMapper.markTerminal(deliveryId, "SKIPPED", "validation",
                    "channel_disabled", "User disabled this notification channel"));
            return;
        }
        if (!sendService.isNotificationDeliveryAvailable(noticeType)) {
            finishWithOwnership(context, "SKIPPED", () -> deliveryMapper.markTerminal(deliveryId, "SKIPPED", "validation",
                    "template_unavailable", "Official Account template is unavailable"));
            return;
        }

        if (context != null) context.prepareBatch();
        boolean claimed = context == null
                ? deliveryMapper.startProviderAttempt(deliveryId, LocalDateTime.now()) == 1
                : Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    context.requireOwnership();
                    return deliveryMapper.startProviderAttempt(deliveryId, LocalDateTime.now()) == 1;
                }));
        if (!claimed) return;

        try {
            MpSubscribeMessageResponse response = sendService.send(
                    notification.getUserId(), noticeType, readFrozenContent(notification), () -> { });
            if (response == null || response.getErrcode() == null) {
                throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat",
                        "Official Account response has no authoritative result");
            }
            if (response.getErrcode() != 0) {
                throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat",
                        String.valueOf(response.getErrcode()), "Official Account send was rejected");
            }
            String messageId = response == null || response.getMsgId() == null
                    ? null : String.valueOf(response.getMsgId());
            finishWithOwnership(context, "DELIVERED", () -> deliveryMapper.markDelivered(deliveryId, messageId, LocalDateTime.now()));
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
        } catch (ExternalServiceException knownProviderFailure) {
            String providerCode = knownProviderFailure.getProviderCode();
            if (providerCode == null) {
                finishWithOwnership(context, "UNKNOWN", () -> deliveryMapper.markTerminal(deliveryId, "UNKNOWN", "remote", null,
                        safeMessage(knownProviderFailure)));
            } else if ("-1".equals(providerCode)) {
                finishWithOwnership(context, "RETRY", () -> deliveryMapper.markRetryable(deliveryId, "remote", providerCode,
                        safeMessage(knownProviderFailure)));
                throw new RetryableRemoteException("WeChat explicitly returned a retryable failure",
                        knownProviderFailure);
            } else {
                finishWithOwnership(context, "FAILED", () -> deliveryMapper.markTerminal(deliveryId, "FAILED", "remote", providerCode,
                        safeMessage(knownProviderFailure)));
            }
        } catch (WebClientException unknown) {
            finishWithOwnership(context, "UNKNOWN", () -> deliveryMapper.markTerminal(deliveryId, "UNKNOWN", "remote", null,
                    unknown.getClass().getSimpleName()));
        } catch (RuntimeException uncertainFailure) {
            // Once an attempt is claimed, an unexpected failure may follow an accepted remote write.
            // Never return it to PENDING and repeat the provider effect.
            finishWithOwnership(context, "UNKNOWN", () -> deliveryMapper.markTerminal(deliveryId, "UNKNOWN", "internal", null,
                    uncertainFailure.getClass().getSimpleName()));
        }
    }

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
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private WxMpNoticeType toMpNoticeType(NotifyType type) {
        if (type == null) return null;
        if (type == NotifyType.POST_COMMENTED || type == NotifyType.COMMENT_REPLIED) { type = type.legacyRepresentation(); }
        return switch (type.getCode()) {
            case 1 -> WxMpNoticeType.COMMENT_REPLY;
            case 2 -> WxMpNoticeType.REPLY;
            case 3 -> WxMpNoticeType.LIKE;
            case 4 -> WxMpNoticeType.ACTIVITY_START;
            case 8 -> WxMpNoticeType.ACTIVITY_START;
            case 5 -> WxMpNoticeType.EXAM_REMIND;
            case 6 -> WxMpNoticeType.AUDIT_RESULT;
            case 7 -> WxMpNoticeType.SYSTEM_NOTICE;
            default -> null;
        };
    }

    private boolean isNotifyEnabled(Notification notification, NotifyType type) {
        if (notification == null || notification.getUserId() == null || type == null) return false;
        if ("CANONICAL".equals(notification.getInboxGeneration())) {
            NotificationCategory category = categoryOf(type);
            return category != null && Boolean.TRUE.equals(preferenceMapper.selectEnabled(
                    notification.getUserId(), category.name(), NotificationChannel.WECHAT_OFFICIAL_ACCOUNT.name()));
        }
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
            case ACTIVITY_START_REMINDER, ACTIVITY_REGISTRATION_DEADLINE_REMINDER,
                    ACTIVITY_CANCELLED, ACTIVITY_TIME_CHANGED, ACTIVITY_LOCATION_CHANGED,
                    ACTIVITY_ENDED_EARLY -> NotificationCategory.ACTIVITY;
            case PUBLIC_EVENT_START_REMINDER, PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER,
                    PUBLIC_EVENT_CANCELLED, PUBLIC_EVENT_TIME_CHANGED,
                    PUBLIC_EVENT_LOCATION_CHANGED -> NotificationCategory.PUBLIC_EVENT;
            default -> null;
        };
    }

    private boolean hasRequiredOfficialAccountPayload(Notification notification, NotifyType type) {
        if (type != NotifyType.ACTIVITY_START_REMINDER) return false;
        try {
            Map<String, Object> content = readFrozenContent(notification);
            return content.get("activityId") != null
                    && content.get("activityTitle") != null
                    && content.get("startTime") != null;
        } catch (BusinessException invalidSnapshot) {
            return false;
        }
    }
}
