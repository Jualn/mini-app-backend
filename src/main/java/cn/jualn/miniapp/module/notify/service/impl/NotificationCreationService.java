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
    private final SettingService settingService;
    private final RedisService redisService;
    private final SubjectExistenceService targetValidator;
    private final ObjectMapper objectMapper;
    private final NotificationPreferenceMapper preferenceMapper;
    private final NotificationPreferenceOwnerMapper preferenceOwnerMapper;
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
        if(categoryOf(payload.getType())!=null && !planning.canonicalOwners().contains(payload.getReceiverId()))
            throw new IllegalStateException("Notification preference ownership is not ready");
        return processNotificationPayload(payload, planning, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void acceptLegacy(NotifyPayload payload) {
        if (payload == null) return;
        processNotificationPayload(payload, planningSnapshot(java.util.Collections.singletonList(payload.getReceiverId())));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void acceptBatch(List<NotifyPayload> payloads) {
        PlanningSnapshot planning = planningSnapshot(payloads.stream().map(NotifyPayload::getReceiverId).toList());
        for (NotifyPayload payload : payloads) processNotificationPayload(payload, planning);
    }

    private void processNotificationPayload(NotifyPayload payload, PlanningSnapshot planning) {
        processNotificationPayload(payload, planning, false);
    }

    private Result processNotificationPayload(NotifyPayload payload, PlanningSnapshot planning, boolean strict) {
        if (payload == null || payload.getReceiverId() == null || payload.getType() == null) {
            log.warn("[Notify] payload 非法，缺少 receiverId 或 type");
            return new Result(Outcome.SKIPPED, null);
        }

        String frozen = writeFrozenContent(payload);
        if(strict && frozen.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)
            throw new IllegalArgumentException("Notification snapshot exceeds 64 KiB");
        String source = payload.getSourceKey() == null ? null : digest(payload.getSourceKey());
        String identity = digest(payload.getReceiverId() + ":" + payload.getSenderId() + ":" + payload.getType()
                + ":" + payload.getTargetType() + ":" + payload.getTargetId());
        String content = strict ? digest(frozen + ":" + payload.isInAppOnly() + ":" + (payload.getAllowedChannels()==null ? "default" : payload.getAllowedChannels().stream().map(Enum::name).sorted().toList())) : null;
        if (source != null) {
            notificationMapper.claimSource(source, identity, content, cn.jualn.miniapp.module.notify.service.NotificationObservation.now());
            var receipt = notificationMapper.lockSource(source);
            if (receipt != null) {
                if (!identity.equals(receipt.getIdentityDigest())
                        || strict && receipt.getContentDigest() != null && !content.equals(receipt.getContentDigest()))
                    throw new IllegalStateException("Notification source identity conflicts with its original fact");
                if (receipt.getNotificationId() != null && receipt.getNotificationId() != 0) {
                    if(strict && receipt.getContentDigest()==null) {
                        Notification original=notificationMapper.selectBySourceForUpdate(payload.getSourceKey());
                        if(original==null || !java.util.Objects.equals(original.getContentPayload(),frozen))
                            throw new IllegalStateException("Historical content cannot be proven equivalent");
                    }
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

        NotificationCategory category = categoryOf(payload.getType());
        boolean canonical = strict || category != null && planning.canonicalOwners().contains(payload.getReceiverId());
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
                .inboxGeneration(canonical ? "CANONICAL" : "LEGACY")
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
        if (canonical) {
            Set<NotificationChannel> enabled = new HashSet<>(planning.enabledChannels().getOrDefault(
                    new PreferenceKey(payload.getReceiverId(), category), Set.of(NotificationChannel.IN_APP)));
            if (payload.isInAppOnly()) enabled.retainAll(Set.of(NotificationChannel.IN_APP));
            if (payload.getAllowedChannels()!=null) enabled.retainAll(payload.getAllowedChannels());
            if (!planning.officialAccountUsers().contains(payload.getReceiverId())) {
                enabled.remove(NotificationChannel.WECHAT_OFFICIAL_ACCOUNT);
            }
            boolean visible = deliveryService.planCanonicalDeliveries(notification, payload.getType(), enabled);
            if (visible) afterCommit(() -> redisService.delete(RedisKeyConstant.userUnreadCount(payload.getReceiverId())));
            return new Result(Outcome.CREATED, notification.getId());
        }
        inboxService.enter(notification);
        afterCommit(() -> redisService.delete(RedisKeyConstant.userUnreadCount(payload.getReceiverId())));

        if (payload.isInAppOnly()) return new Result(Outcome.CREATED, notification.getId());

        if (!isNotifyEnabled(payload.getReceiverId(), payload.getType())) {
            log.debug("[Notify] 用户已关闭此类通知，跳过外部推送，userId={}, type={}",
                    payload.getReceiverId(), payload.getType());
            return new Result(Outcome.CREATED, notification.getId());
        }

        deliveryService.planWeChatDelivery(notification, payload.getType(), payload.getAllowedChannels());

        log.debug("[Notify] 个人通知处理完成，userId={}, type={}",
                payload.getReceiverId(), payload.getType());
        return new Result(Outcome.CREATED, notification.getId());
    }

    private boolean isNotifyEnabled(Long userId, NotifyType type) {
        if(type==NotifyType.POST_CONTENT_HANDLED || type==NotifyType.POST_REPORT_RESOLVED) type=NotifyType.SYSTEM;
        if (type == NotifyType.POST_COMMENTED || type == NotifyType.COMMENT_REPLIED) {
            type = type.legacyRepresentation();
        }
        if (type == null || type.getCode() > 7) return false;
        UserSettingBO setting = settingService.getSettingByUserId(userId);

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

    private String writeFrozenContent(NotifyPayload payload) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", payload.getTitle());
        content.put("content", payload.getContent());
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
        if (userIds.isEmpty()) return new PlanningSnapshot(Set.of(), Map.of(), Set.of());
        Set<Long> owners = new HashSet<>(preferenceOwnerMapper.selectCanonicalUserIds(userIds));
        Set<Long> officialAccountUsers = userIdentities.officialAccountUsers(userIds);
        Map<PreferenceKey, Set<NotificationChannel>> enabled = new HashMap<>();
        for (Long owner : owners) {
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
        return new PlanningSnapshot(Set.copyOf(owners), enabled, Set.copyOf(officialAccountUsers));
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
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("Notification transaction required");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    private boolean shouldValidateTarget(NotifyPayload payload) {
        return payload.getTargetType() != null && payload.getTargetId() != null;
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
    private record PlanningSnapshot(Set<Long> canonicalOwners,
        Map<PreferenceKey, Set<NotificationChannel>> enabledChannels, Set<Long> officialAccountUsers) { }
}
