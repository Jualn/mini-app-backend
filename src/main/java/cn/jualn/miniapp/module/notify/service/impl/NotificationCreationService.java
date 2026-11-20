package cn.jualn.miniapp.module.notify.service.impl;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationSnapshotProjection;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.entity.NotificationPreference;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceOwnerMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.service.NotificationDeliveryService;
import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.module.user.service.UserNotificationIdentityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCreationService implements cn.jualn.miniapp.module.notify.service.NotificationAcceptance {
    private final NotificationMapper notificationMapper;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService inboxService;
    private final NotificationDeliveryService deliveryService;
    private final RedisService redisService;
    private final SubjectExistenceService targetValidator;
    private final ObjectMapper objectMapper;
    private final NotificationPreferenceMapper preferenceMapper;
    private final UserNotificationIdentityService userIdentities;
    private final List<cn.jualn.miniapp.module.notify.service.NotificationSource> sources;


    /** Stable internal entry. Invalid input and conflicting identities fail the caller transaction. */
    @Transactional(rollbackFor = Exception.class)
    public Result create(NotifyPayload payload) {
        if (payload == null || payload.getReceiverId() == null || payload.getReceiverId() <= 0
                || payload.getType() == null || payload.getSourceKey() == null
                || payload.getSourceKey().isBlank() || payload.getSourceKey().length() > 190
                || payload.getTitle() == null || payload.getTitle().isBlank() || payload.getTitle().length()>200
                || payload.getContent()!=null && payload.getContent().length()>512
                || payload.getAllowedChannels()!=null && payload.getAllowedChannels().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Notification identity, recipient, type and title are required");
        }
        PlanningSnapshot planning=planningSnapshot(List.of(payload.getReceiverId()));
        return processNotificationPayload(payload, planning, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void accept(NotifyPayload payload) {
        if (payload == null) return;
        processNotificationPayload(payload, planningSnapshot(java.util.Collections.singletonList(payload.getReceiverId())));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void acceptBatch(List<NotifyPayload> payloads) {
        PlanningSnapshot planning = planningSnapshot(payloads.stream().map(NotifyPayload::getReceiverId).toList());
        for (NotifyPayload payload : payloads) processNotificationPayload(payload, planning);
    }

    private void processNotificationPayload(NotifyPayload payload, PlanningSnapshot planning) {
        processNotificationPayload(payload, planning, true);
    }

    private Result processNotificationPayload(NotifyPayload payload, PlanningSnapshot planning, boolean strict) {
        if (payload == null || payload.getReceiverId() == null || payload.getType() == null) {
            log.warn("[Notify] payload 非法，缺少 receiverId 或 type");
            return new Result(Outcome.SKIPPED, null);
        }

        if (NotificationCategory.of(payload.getType()) == null) return new Result(Outcome.SKIPPED, null);
        String frozen = writeFrozenContent(payload);
        if(strict && frozen.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)
            throw new IllegalArgumentException("Notification snapshot exceeds 64 KiB");
        String source = payload.getSourceKey() == null ? null : digest(payload.getSourceKey());
        String identity = digest(payload.getReceiverId() + ":" + payload.getSenderId() + ":" + payload.getType()
                + ":" + payload.getTargetType() + ":" + payload.getTargetId());
        String content = strict ? digest(contentForIdentity(frozen) + ":" + (payload.getAllowedChannels()==null ? "default" : payload.getAllowedChannels().stream().map(Enum::name).sorted().toList())) : null;
        if (source != null) {
            notificationMapper.claimSource(source, identity, content, cn.jualn.miniapp.module.notify.service.NotificationObservation.now());
            var receipt = notificationMapper.lockSource(source);
            if (receipt != null) {
                if (!identity.equals(receipt.getIdentityDigest())
                        || strict && receipt.getContentDigest() != null && !content.equals(receipt.getContentDigest()))
                    throw new IllegalStateException("Notification source identity conflicts with its original fact");
                if (receipt.getNotificationId() != null && receipt.getNotificationId() != 0) {
                    afterCommit(()->meters.counter("jualn.notification.creation","result","dedupe_suppressed").increment());
                    return new Result(Outcome.DUPLICATE, receipt.getNotificationId());
                }
            }
            Notification previous = notificationMapper.selectBySourceForUpdate(payload.getSourceKey());
            if (previous != null) {
                if (!java.util.Objects.equals(previous.getUserId(), payload.getReceiverId())
                        || !java.util.Objects.equals(previous.getType(), payload.getType().getCode())
                        || !java.util.Objects.equals(previous.getSenderId(), payload.getSenderId())
                        || !java.util.Objects.equals(previous.getTargetId(), payload.getTargetId())
                        || !java.util.Objects.equals(previous.getTargetType(), payload.getTargetType() == null ? null : payload.getTargetType().getCode())
                        || strict && !java.util.Objects.equals(previous.getContentPayload(), frozen))
                    throw new IllegalStateException("Notification source conflicts with historical fact");
                notificationMapper.finishSource(source, previous.getId(), previous.getCreatedAt());
                return new Result(Outcome.DUPLICATE, previous.getId());
            }
        }

        if (java.util.Objects.equals(payload.getSenderId(), payload.getReceiverId())) {
            return new Result(Outcome.SKIPPED, null);
        }
        if (shouldValidateTarget(payload)
                && !targetValidator.exists(payload.getTargetType(), payload.getTargetId())) {
            log.debug("[Notify] target 不存在，跳过，targetType={}, targetId={}",
                    payload.getTargetType(), payload.getTargetId());
            return new Result(Outcome.SKIPPED, null);
        }

        NotificationCategory category = NotificationCategory.of(payload.getType());
        if (category == null) return new Result(Outcome.SKIPPED, null);
        Notification notification = Notification.builder()
                // notification.created_at is second precision; avoid MySQL rounding into the future.
                .createdAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .userId(payload.getReceiverId())
                .senderId(payload.getSenderId())
                .type(payload.getType().getCode())
                .title(payload.getTitle())
                .content(payload.getContent()==null ? "" : payload.getContent())
                .contentSchemaVersion(1)
                .contentPayload(frozen)
                .targetType(payload.getTargetType() != null ? payload.getTargetType().getCode() : null)
                .targetId(payload.getTargetId())
                .sourceKey(payload.getSourceKey())
                .inboxGeneration("V1")
                .isRead(0)
                .build();

        try {
            notificationMapper.insert(notification);
        } catch (DuplicateKeyException duplicate) {
            // Source ownership was serialized above. Any other uniqueness failure is not dedupe.
            throw duplicate;
        }
        if (source != null) notificationMapper.finishSource(source, notification.getId(), notification.getCreatedAt());
        afterCommit(() -> meters.counter("jualn.notification.creation", "result", "created").increment());
        Set<NotificationChannel> enabled = new HashSet<>(planning.enabledChannels().getOrDefault(
                new PreferenceKey(payload.getReceiverId(), category), Set.of(NotificationChannel.IN_APP)));
        if (payload.getAllowedChannels() != null) enabled.retainAll(payload.getAllowedChannels());
        enabled.remove(NotificationChannel.WECHAT_OFFICIAL_ACCOUNT); // First-release product boundary, not a configurable gate.
        if (!planning.miniProgramUsers().contains(payload.getReceiverId())) enabled.remove(NotificationChannel.WECHAT_MINI_PROGRAM);
        boolean visible = deliveryService.planCanonicalDeliveries(notification, payload.getType(), enabled);
        if (visible) afterCommit(() -> redisService.delete(RedisKeyConstant.userUnreadCount(payload.getReceiverId())));
        return new Result(Outcome.CREATED, notification.getId());
    }

    private String writeFrozenContent(NotifyPayload payload) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", payload.getTitle());
        content.put("content", payload.getContent());
        content.put("noticeTitle", payload.getType().getDesc());
        var category = NotificationCategory.of(payload.getType());
        String summary = category == NotificationCategory.POINTS ? "积分结果已更新，请进入小程序查看。"
                : category == NotificationCategory.AUDIT_RESULT ? "审核与处理结果已更新，请进入小程序查看。"
                : category == NotificationCategory.LEARNING_TASK ? "学习任务有新进展，请进入小程序查看。" : payload.getContent();
        content.put("noticeSummary", summary);
        content.put("eventTime", cn.jualn.miniapp.module.notify.service.NotificationObservation.now());
        if (payload.getRecipientPersonId() != null) {
            content.put("recipientPersonId", payload.getRecipientPersonId());
            content.put("recipientBindingId", payload.getRecipientBindingId());
        }
        if (payload.getBusinessTime() != null) content.put("businessTime", payload.getBusinessTime());
        if (payload.getReminderPlanId() != null) content.put("reminderPlanId", payload.getReminderPlanId());
        content.put("targetId", payload.getTargetId());
        content.put("targetType", payload.getTargetType() == null ? null : payload.getTargetType().name());
        for (var source : sources) content.putAll(source.content(payload));
        if (payload.getContentData() != null) {
            content.putAll(payload.getContentData().toMap());
        }
        var snapshot = payload.getSnapshot();
        if (snapshot != null) {
            content.put("presentation", snapshot.presentation());
            content.put("actor", snapshot.actor());
            content.put("subject", snapshot.subject());
            content.put("target", snapshot.target());
        } else {
            content.put("presentation", new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation(
                    payload.getTitle() == null || payload.getTitle().isBlank() ? "通知" : payload.getTitle(),
                    payload.getContent(), null, null, null,
                    payload.getType().isTimedReminder() ? payload.getTitle() : null, null));
            if (payload.getTargetId() != null && payload.getTargetType() != null) {
                String id = payload.getTargetId().toString();
                String subjectType = payload.getTargetType() == TargetType.EXAM ? "PUBLIC_EVENT" : payload.getTargetType().name();
                content.put("subject", new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject(subjectType, id));
                switch (payload.getTargetType()) {
                    case POST -> content.put("target", cn.jualn.miniapp.module.notify.service.inbox.NotificationSnapshotProjection.post(id, null));
                    case ACTIVITY -> content.put("target", new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("ACTIVITY_DETAIL", null, null, id, null));
                    case EXAM -> content.put("target", new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("PUBLIC_EVENT_DETAIL", null, null, null, id));
                    default -> { }
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException invalidSnapshot) {
            throw new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID, "通知冻结内容无法序列化");
        }
    }

    private PlanningSnapshot planningSnapshot(Collection<Long> rawUserIds) {
        List<Long> userIds = rawUserIds == null ? List.of() : rawUserIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (userIds.isEmpty()) return new PlanningSnapshot(Map.of(), Set.of());
        Set<Long> miniProgramUsers = userIdentities.miniProgramUsers(userIds);
        Map<PreferenceKey, Set<NotificationChannel>> enabled = new HashMap<>();
        for (Long owner : userIds) {
            for (NotificationCategory category : NotificationCategory.values()) {
                enabled.put(new PreferenceKey(owner, category), new HashSet<>(Set.of(NotificationChannel.IN_APP)));
            }
        }
        for (NotificationPreference preference : preferenceMapper.selectByUserIds(userIds)) {
            NotificationCategory category = NotificationCategory.valueOf(preference.getCategory());
            NotificationChannel channel = NotificationChannel.valueOf(preference.getChannel());
            Set<NotificationChannel> channels = enabled.computeIfAbsent(
                    new PreferenceKey(preference.getUserId(), category), ignored -> new HashSet<>());
            if (Boolean.TRUE.equals(preference.getEnabled())) channels.add(channel); else channels.remove(channel);
        }
        return new PlanningSnapshot(enabled, Set.copyOf(miniProgramUsers));
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("Notification transaction required");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    private boolean shouldValidateTarget(NotifyPayload payload) {
        return payload.getTargetType() != null && payload.getTargetId() != null;
    }
    private String contentForIdentity(String frozen) {
        try {var body=(com.fasterxml.jackson.databind.node.ObjectNode)objectMapper.readTree(frozen);body.remove("eventTime");return body.toString();}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Invalid notification snapshot");}
    }
    public static String identity(Notification row) {
        return digest(row.getUserId() + ":" + row.getSenderId() + ":" + NotifyType.fromCode(row.getType())
                + ":" + TargetType.fromCode(row.getTargetType()) + ":" + row.getTargetId());
    }
    public static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record PreferenceKey(Long userId, NotificationCategory category) { }
    private record PlanningSnapshot(Map<PreferenceKey, Set<NotificationChannel>> enabledChannels, Set<Long> miniProgramUsers) { }
}
