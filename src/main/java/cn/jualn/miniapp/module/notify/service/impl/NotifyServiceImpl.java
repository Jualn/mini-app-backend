package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.notify.bo.NotificationBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPageBO;
import cn.jualn.miniapp.module.notify.converter.NotifyConverter;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.async.NotificationPlanJobPayload;
import cn.jualn.miniapp.module.notify.async.ActivityBusinessNotificationJobPayload;
import cn.jualn.miniapp.module.notify.async.BusinessNotificationJobPayload;
import cn.jualn.miniapp.module.notify.entity.NotificationPreference;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceOwnerMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.service.NotificationDeliveryService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.notify.reminder.ReminderSubjectStatus;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
import cn.jualn.miniapp.module.notify.reminder.ReminderReconcileService;
import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.wx.notice.data.ActivityRemindNoticeData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
public class NotifyServiceImpl implements NotifyService {

    private final NotificationMapper notificationMapper;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final cn.jualn.miniapp.module.notify.service.NotificationInboxService inboxService;
    private final NotifyPlanMapper notifyPlanMapper;
    private final ActivityEnrollmentService enrollmentService;
    private final ExamSubscriptionService examSubService;
    private final UserService userService;
    private final SettingService settingService;
    private final RedisService redisService;
    private final TargetValidator targetValidator;
    private final NotifyConverter notifyConverter;
    private final JobService jobService;
    private final TransactionTemplate transactionTemplate;
    private static final int BATCH_SIZE = 100;
    private final NotificationDeliveryService deliveryService;
    private final ObjectMapper objectMapper;
    private final List<ReminderSubjectStatus> reminderSubjectStatuses;
    private final ReminderReconcileService reminderReconcileService;
    private final NotificationPreferenceMapper preferenceMapper;
    private final NotificationPreferenceOwnerMapper preferenceOwnerMapper;
    private final UserProfileMapper userProfileMapper;

    @Override
    public PageResult<NotificationBO> pageCurrentUserNotifications(NotificationPageBO query) {
        Long userId = requireCurrentUserId();
        NotificationPageBO actualQuery = query == null ? new NotificationPageBO() : query;
        int pageSize = normalizePageSize(actualQuery.getPageSize());
        Integer type = actualQuery.getType() != null ? actualQuery.getType().getCode() : null;

        List<Notification> list = notificationMapper.selectLegacyInbox(userId, actualQuery.getLastId(), type,
                actualQuery.getIsRead(), pageSize);
        List<NotificationBO> resultList = notifyConverter.toBOList(list);

        PageResult<NotificationBO> result = PageResult.of(resultList, list.size() == pageSize);
        result.setNextCursor(resultList.isEmpty() ? null : resultList.get(resultList.size() - 1).getId());
        return result;
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Long countCurrentUnreadNotifications() {
        Long userId = requireCurrentUserId();
        String cacheKey = RedisKeyConstant.userUnreadCount(userId) + ":" + notificationMapper.selectUnreadProjectionVersion(userId);
        Long cached = redisService.getLong(cacheKey);
        if (cached != null) {
            return Math.max(cached, 0L);
        }

        Long selected;
        try {
            selected = notificationMapper.countCanonicalUnread(userId);
        } catch (RuntimeException failure) {
            meters.counter("jualn.notification.unread.rebuild", "result", "database_failure").increment();
            throw failure;
        }
        long dbCount = selected == null ? 0L : selected;
        boolean rebuilt = redisService.setCacheProjection(cacheKey, dbCount, java.time.Duration.ofSeconds(15));
        meters.counter("jualn.notification.unread.rebuild", "result", rebuilt ? "success" : "cache_failure").increment();
        return dbCount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsRead(Long notificationId) {
        Long userId = requireCurrentUserId();
        notificationMapper.ensureInboxCounter(userId);
        notificationMapper.lockInboxHead(userId);

        Integer state = notificationMapper.selectCanonicalReadState(notificationId, userId);
        if (state == null) {
            throw new BusinessException(ResultCode.NOTIFICATION_NOT_FOUND, "通知不存在");
        }
        if (state == 1) {
            return;
        }
        notificationMapper.markCanonicalRead(notificationId, userId);
        notificationMapper.advanceReadVersion(userId);
        afterCommit(() -> redisService.delete(RedisKeyConstant.userUnreadCount(userId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllAsRead() {
        Long userId = requireCurrentUserId();
        notificationMapper.ensureInboxCounter(userId);
        notificationMapper.lockInboxHead(userId);
        notificationMapper.markAllCanonicalRead(userId);
        notificationMapper.advanceReadVersion(userId);
        afterCommit(() -> redisService.delete(RedisKeyConstant.userUnreadCount(userId)));
    }

    // 写延迟队列
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueNotifyPlan(Long planId) {
        NotifyPlan plan = notifyPlanMapper.selectById(planId);
        if (plan == null) {
            log.warn("[NotificationPlanJob] 通知计划不存在，跳过创建任务，planId={}", planId);
            return;
        }
        if (plan.getStatus() == null || plan.getStatus() != 0) {
            log.debug("[NotificationPlanJob] 通知计划状态非待发送，跳过创建任务，planId={}, status={}", planId, plan.getStatus());
            return;
        }
        if (plan.getSendAt() == null) {
            log.warn("[NotificationPlanJob] 通知计划 sendAt 为空，跳过创建任务，planId={}", planId);
            return;
        }

        jobService.create(new JobDefinition("notification.plan.fanout", 1, ObservabilityContext.ensureOperationId(),
                planDedupeKey(planId), "notify-plan", String.valueOf(planId),
                new NotificationPlanJobPayload(planId), plan.getSendAt(), 6));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void replaceEventReminder(TargetType type, Long id, String title, java.time.LocalDateTime sendAt) {
        if ((type != TargetType.ACTIVITY && type != TargetType.EXAM) || id == null)
            throw new BusinessException(ResultCode.INVALID_OPERATION, "提醒来源不合法");
        int sourceType = type == TargetType.ACTIVITY ? 1 : 2;
        reminderReconcileService.reconcile(sourceType, id, 0L, List.of(), LocalDateTime.now());
        // A non-null legacy sendAt is intentionally ignored. Canonical owners evaluate their
        // subject-specific Timeline policy and call reconcileReminders with a real generation.
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void reconcileReminders(TargetType type, Long id, long generation,
                                   List<ReminderSpec> expected, LocalDateTime now) {
        if ((type != TargetType.ACTIVITY && type != TargetType.EXAM) || id == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "提醒来源不合法");
        }
        reminderReconcileService.reconcile(type == TargetType.ACTIVITY ? 1 : 2,
                id, generation, expected, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelActivityPlans(Long activityId) {
        if (activityId == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "activityId 不能为空");
        }
        List<Long> planIds = notifyPlanMapper.selectIdsBySource(1, activityId);
        notifyPlanMapper.cancelBySource(1, activityId);
        planIds.forEach(id -> jobService.cancelPending("notification.plan.fanout", planDedupeKey(id)));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enqueueActivityBusinessNotification(Long activityId, String eventKey, String title, String content) {
        if (activityId == null || eventKey == null || eventKey.isBlank()) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动通知事件身份不完整");
        }
        jobService.create(new JobDefinition(
                "activity.business-notification.fanout", 1, ObservabilityContext.ensureOperationId(),
                "activity:" + activityId + ":event:" + eventKey,
                "activity", String.valueOf(activityId),
                new ActivityBusinessNotificationJobPayload(activityId, eventKey, title, content),
                LocalDateTime.now(), 6));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enqueueBusinessNotification(TargetType targetType, Long targetId, String eventIdentity,
                                            NotifyType notificationType, String recipientScope,
                                            String title, String content) {
        enqueueBusinessNotification(targetType, targetId, eventIdentity, notificationType, recipientScope, title, content, null);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enqueueBusinessNotification(TargetType targetType, Long targetId, String eventIdentity,
            NotifyType notificationType, String recipientScope, String title, String content,
            cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot snapshot) {
        if (!Set.of(TargetType.ACTIVITY, TargetType.EXAM).contains(targetType) || targetId == null
                || eventIdentity == null || eventIdentity.isBlank() || categoryOf(notificationType) == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "业务通知事件身份不完整");
        }
        BusinessNotificationJobPayload payload = new BusinessNotificationJobPayload(
                targetType.getCode(), targetId, eventIdentity, notificationType.getCode(),
                recipientScope, title, content, snapshot);
        jobService.create(new JobDefinition("business-notification.fanout", 1,
                ObservabilityContext.ensureOperationId(), eventIdentity, targetType.name(),
                String.valueOf(targetId), payload, LocalDateTime.now(), 6));
    }

    @Override
    public void notifyActivitySubscribers(Long activityId, String eventKey, String title, String content) {
        if (activityId == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "activityId 不能为空");
        }
        long lastId = 0L;
        while (true) {
            List<Long> userIds = enrollmentService.listEnrolledUserIds(activityId, lastId, BATCH_SIZE);
            if (userIds.isEmpty()) {
                return;
            }
            for (Long userId : userIds) {
                NotifyPayload payload = NotifyPayload.builder()
                        .receiverId(userId)
                        .senderId(null)
                        .type(NotifyType.ACTIVITY_REMIND)
                        .title(title)
                        .content(content)
                        .targetType(TargetType.ACTIVITY)
                        .targetId(activityId)
                        .sourceKey("activity:" + activityId + ":event:" + eventKey + ":user:" + userId)
                        .build();
                transactionTemplate.executeWithoutResult(status -> processNotificationPayload(payload));
            }
            if (userIds.size() < BATCH_SIZE) {
                return;
            }
            lastId = userIds.get(userIds.size() - 1);
        }
    }

    // 延迟队列消费，发送消息队列
    @Override
    public void broadcastPlanFanOut(Long planId) {
        broadcastPlanFanOut(planId, null);
    }

    @Override
    public void broadcastPlanFanOut(Long planId, JobExecutionContext context) {
        NotifyPlan plan = notifyPlanMapper.selectById(planId);

        if (plan == null || (!Integer.valueOf(0).equals(plan.getStatus())
                && !Integer.valueOf(3).equals(plan.getStatus()))) {
            log.debug("[Broadcast] 计划已处理或不存在，planId={}", planId);
            return;
        }
        if (Integer.valueOf(0).equals(plan.getStatus())) {
            if (notifyPlanMapper.startFanOut(planId) != 1) return;
            plan = notifyPlanMapper.selectById(planId);
        }

        long lastId = 0L;
        int total = 0;

        while (true) {
            if (context != null) context.prepareBatch();
            plan = notifyPlanMapper.selectById(planId);
            if (plan == null || !Integer.valueOf(3).equals(plan.getStatus())) {
                return;
            }
            if (!isReminderSubjectCurrent(plan)) {
                transactionTemplate.executeWithoutResult(status -> {
                    if (context != null) context.requireOwnership();
                    notifyPlanMapper.cancelPlan(planId);
                });
                return;
            }
            List<Long> userIds = queryUserIds(plan, lastId);
            if (userIds.isEmpty()) {
                break;
            }

            NotifyPlan batchPlan = plan;
            List<NotifyPayload> payloads = userIds.stream().map(uid -> buildNotifyPayload(uid, batchPlan))
                    .filter(java.util.Objects::nonNull).toList();
            meters.counter("jualn.notification.recipients", "type", "reminder").increment(userIds.size());
            processBatch(payloads, context);

            total += userIds.size();

            if (userIds.size() < BATCH_SIZE) {
                break;
            }

            lastId = userIds.get(userIds.size() - 1);
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (context != null) context.requireOwnership();
            notifyPlanMapper.completeFanOut(planId);
        });

        // TODO 后续可把 status=1 语义改为 FANOUT_DONE，而不是“已发送”。
        // TODO 后续可加 CAS 抢占，避免多个消费者重复 fan-out。
        log.info("[Broadcast] fan-out 完成，planId={}，共 {} 人", planId, total);
    }

    /**
     * 处理个人通知的 payload，写站内通知表，并尝试推送服务号订阅通知。
     * 最终发布通知的接口，所有个人通知相关的业务场景都应该走这个接口。
     * 不管直接发送还是延迟发送都会汇总到这里，保证站内通知和服务号通知的一致性。
     *
     * @param payload 个人通知负载
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processNotificationPayload(NotifyPayload payload) {
        if (payload == null) {
            log.warn("[Notify] payload 为空");
            return;
        }
        PlanningSnapshot planning = categoryOf(payload.getType()) == null
                ? new PlanningSnapshot(Set.of(), Map.of(), Set.of())
                : planningSnapshot(List.of(payload.getReceiverId()));
        processNotificationPayload(payload, planning);
    }

    private void processNotificationPayload(NotifyPayload payload, PlanningSnapshot planning) {
        if (payload == null || payload.getReceiverId() == null || payload.getType() == null) {
            log.warn("[Notify] payload 非法，缺少 receiverId 或 type");
            return;
        }

        if (java.util.Objects.equals(payload.getSenderId(), payload.getReceiverId())) {
            return;
        }
        if (shouldValidateTarget(payload)
                && !targetValidator.exists(payload.getTargetType(), payload.getTargetId())) {
            log.debug("[Notify] target 不存在，跳过，targetType={}, targetId={}",
                    payload.getTargetType(), payload.getTargetId());
            return;
        }

        NotificationCategory category = categoryOf(payload.getType());
        boolean canonical = category != null && planning.canonicalOwners().contains(payload.getReceiverId());
        Notification notification = Notification.builder()
                .userId(payload.getReceiverId())
                .senderId(payload.getSenderId())
                .type(payload.getType().getCode())
                .title(payload.getTitle())
                .content(payload.getContent())
                .contentSchemaVersion(1)
                .contentPayload(writeFrozenContent(payload))
                .targetType(payload.getTargetType() != null ? payload.getTargetType().getCode() : null)
                .targetId(payload.getTargetId())
                .sourceKey(payload.getSourceKey())
                .inboxGeneration(canonical ? "CANONICAL" : "LEGACY")
                .isRead(0)
                .build();

        try {
            notificationMapper.insert(notification);
        } catch (DuplicateKeyException duplicate) {
            meters.counter("jualn.notification.creation", "result", "dedupe_suppressed").increment();
            if (payload.getSourceKey() != null && payload.getSourceKey().startsWith("reminder:")) {
                meters.counter("jualn.notification.reminder.dedupe").increment();
            }
            log.debug("通知已存在，跳过重复业务事实，sourceKey={}", payload.getSourceKey());
            return;
        }
        afterCommit(() -> meters.counter("jualn.notification.creation", "result", "created").increment());
        if (canonical) {
            Set<NotificationChannel> enabled = new HashSet<>(planning.enabledChannels().getOrDefault(
                    new PreferenceKey(payload.getReceiverId(), category), Set.of(NotificationChannel.IN_APP)));
            if (!planning.officialAccountUsers().contains(payload.getReceiverId())) {
                enabled.remove(NotificationChannel.WECHAT_OFFICIAL_ACCOUNT);
            }
            boolean visible = deliveryService.planCanonicalDeliveries(notification, payload.getType(), enabled);
            if (visible) afterCommit(() -> redisService.delete(RedisKeyConstant.userUnreadCount(payload.getReceiverId())));
            return;
        }
        inboxService.enter(notification);
        afterCommit(() -> redisService.delete(RedisKeyConstant.userUnreadCount(payload.getReceiverId())));

        if (!isNotifyEnabled(payload.getReceiverId(), payload.getType())) {
            log.debug("[Notify] 用户已关闭此类通知，跳过外部推送，userId={}, type={}",
                    payload.getReceiverId(), payload.getType());
            return;
        }

        deliveryService.planWeChatDelivery(notification, payload.getType());

        log.debug("[Notify] 个人通知处理完成，userId={}, type={}",
                payload.getReceiverId(), payload.getType());
    }

    private String planDedupeKey(Long planId) {
        return "plan:" + planId + ":fanout";
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 根据 NotifyPlan 的范围（订阅用户/全体）和来源（活动/考试）查询用户 ID 列表，支持分页查询。
     * 注意：这里不做复杂的权限校验，假设如果用户能参与活动/考试，就应该能收到相关通知。
     *
     * @param plan   通知计划
     * @param lastId 上次查询的最后一个用户 ID，分页使用，初始传 0
     * @return 用户 ID 列表，按 ID 升序排列，最多 BATCH_SIZE 条
     */
    private List<Long> queryUserIds(NotifyPlan plan, long lastId) {
        if (plan == null) return List.of();
        if ("SUBSCRIBERS_NOT_REGISTERED".equals(plan.getRecipientScope())) {
            return Integer.valueOf(1).equals(plan.getSourceType())
                    ? enrollmentService.listNotifyEnabledUnregisteredUserIds(plan.getSourceId(), lastId, BATCH_SIZE)
                    : List.of();
        }
        if (plan.getRecipientScope() != null && !"SUBSCRIBERS".equals(plan.getRecipientScope())) {
            return List.of();
        }
        return switch (plan.getScope()) {
            case 0 -> switch (plan.getSourceType()) {
                case 1 -> enrollmentService.listEnrolledUserIds(plan.getSourceId(), lastId, BATCH_SIZE);
                case 2 -> examSubService.listSubscriberUserIds(plan.getSourceId(), lastId, BATCH_SIZE);
                default -> List.of();
            };
            case 1 -> userService.listAllUserIds(lastId, BATCH_SIZE);
            default -> List.of();
        };
    }

    /**
     * 构建 NotifyPayload，传入最终发送通知执行的process
     *
     * @param uid    接收用户 ID
     * @param plan   通知计划
     * @return NotifyPayload
     */
    private NotifyPayload buildNotifyPayload(Long uid, NotifyPlan plan) {
        if (Integer.valueOf(8).equals(plan.getNotifyType()) && plan.getSubjectStartsAt() != null) {
            String anchor = plan.getSubjectStartsAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            if (plan.getSubjectStartsAt().getNano() == 0 && notificationMapper.selectHistoricalStartOccurrence(
                    uid, TargetType.ACTIVITY.getCode(), plan.getSourceId(), anchor) != null) {
                meters.counter("jualn.notification.reminder.dedupe").increment();
                return null;
            }
        }
        return NotifyPayload.builder()
                .receiverId(uid)
                .senderId(null)
                .type(NotifyType.fromCode(plan.getNotifyType()))
                .title(plan.getTitle())
                .content(plan.getContent())
                .targetType(sourceTypeToTargetType(plan.getSourceType()))
                .targetId(plan.getSourceId())
                .sourceKey(cn.jualn.miniapp.module.notify.service.ReminderOccurrence.sourceKey(plan, uid))
                .wxData(buildReminderNoticeData(plan))
                .build();
    }

    private cn.jualn.miniapp.module.wx.notice.data.NoticeData buildReminderNoticeData(NotifyPlan plan) {
        NotifyType type = NotifyType.fromCode(plan.getNotifyType());
        if (type != NotifyType.ACTIVITY_START_REMINDER || plan.getSourceId() == null
                || plan.getTitle() == null || plan.getSubjectStartsAt() == null) {
            return null;
        }
        return new ActivityRemindNoticeData(plan.getSourceId(), plan.getTitle(),
                plan.getSubjectStartsAt(), plan.getSubjectLocation());
    }

    private TargetType sourceTypeToTargetType(Integer sourceType) {
        if (sourceType == null) {
            return null;
        }

        return switch (sourceType) {
            case 1 -> TargetType.ACTIVITY;
            case 2 -> TargetType.EXAM;
            default -> null;
        };
    }

    private Long requireCurrentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录态已过期");
        }
        return userId;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 50);
    }

    private boolean isNotifyEnabled(Long userId, NotifyType type) {
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
        content.put("targetId", payload.getTargetId());
        content.put("targetType", payload.getTargetType() == null ? null : payload.getTargetType().name());
        if (payload.getWxData() != null) {
            content.putAll(payload.getWxData().toMap());
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
                    payload.getType().getCode() >= 8 && payload.getType().getCode() <= 11 ? payload.getTitle() : null, null));
            if (payload.getTargetId() != null && payload.getTargetType() != null) {
                String id = payload.getTargetId().toString();
                String subjectType = payload.getTargetType() == TargetType.EXAM ? "PUBLIC_EVENT" : payload.getTargetType().name();
                content.put("subject", new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject(subjectType, id));
                switch (payload.getTargetType()) {
                    case POST -> content.put("target", cn.jualn.miniapp.module.notify.service.NotificationSnapshotProjection.post(id, null));
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

    @Override
    public void broadcastBusinessNotification(BusinessNotificationJobPayload event, JobExecutionContext context) {
        if (event == null || context == null) throw new IllegalArgumentException("owned business event required");
        TargetType targetType = TargetType.fromCode(event.targetType());
        NotifyType notificationType = NotifyType.fromCode(event.notificationType());
        long upperBound = recipientUpperBound(targetType, event.targetId(), event.recipientScope());
        long lastId = 0L;
        while (lastId < upperBound) {
            context.prepareBatch();
            List<Long> recipients = recipientBatch(targetType, event.targetId(), event.recipientScope(),
                    lastId, upperBound);
            if (recipients.isEmpty()) return;
            List<NotifyPayload> payloads = recipients.stream().map(userId -> NotifyPayload.builder()
                    .receiverId(userId).senderId(null).type(notificationType)
                    .title(event.title()).content(event.content()).targetType(targetType).targetId(event.targetId())
                    .sourceKey(event.eventIdentity() + ":user:" + userId).snapshot(event.snapshot()).build()).toList();
            meters.counter("jualn.notification.recipients", "type", "business_change").increment(recipients.size());
            processBatch(payloads, context);
            lastId = recipients.get(recipients.size() - 1);
            if (recipients.size() < BATCH_SIZE) return;
        }
    }

    private void processBatch(List<NotifyPayload> payloads, JobExecutionContext context) {
        PlanningSnapshot snapshot = planningSnapshot(payloads.stream().map(NotifyPayload::getReceiverId).toList());
        for (NotifyPayload payload : payloads) {
            transactionTemplate.executeWithoutResult(status -> {
                if (context != null) context.requireOwnership();
                processNotificationPayload(payload, snapshot);
            });
        }
    }

    private PlanningSnapshot planningSnapshot(Collection<Long> rawUserIds) {
        List<Long> userIds = rawUserIds == null ? List.of() : rawUserIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (userIds.isEmpty()) return new PlanningSnapshot(Set.of(), Map.of(), Set.of());
        Set<Long> owners = new HashSet<>(preferenceOwnerMapper.selectCanonicalUserIds(userIds));
        List<Long> officialIds = userProfileMapper.selectOfficialAccountUserIds(userIds);
        Set<Long> officialAccountUsers = officialIds == null ? new HashSet<>() : new HashSet<>(officialIds);
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

    private long recipientUpperBound(TargetType targetType, Long targetId, String scope) {
        if (targetType == TargetType.ACTIVITY && "SUBSCRIBERS_OR_REGISTERED_USERS".equals(scope)) {
            return enrollmentService.subscriberOrRegisteredUpperBound(targetId);
        }
        if (targetType == TargetType.EXAM && "SUBSCRIBERS".equals(scope)) {
            return examSubService.subscriberUpperBound(targetId);
        }
        throw new IllegalArgumentException("Unsupported business notification recipient scope");
    }

    private List<Long> recipientBatch(TargetType targetType, Long targetId, String scope,
                                      long lastId, long upperBound) {
        if (targetType == TargetType.ACTIVITY && "SUBSCRIBERS_OR_REGISTERED_USERS".equals(scope)) {
            return enrollmentService.listSubscriberOrRegisteredUserIds(targetId, lastId, upperBound, BATCH_SIZE);
        }
        if (targetType == TargetType.EXAM && "SUBSCRIBERS".equals(scope)) {
            return examSubService.listSubscriberUserIds(targetId, lastId, upperBound, BATCH_SIZE);
        }
        return List.of();
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

    private record PreferenceKey(Long userId, NotificationCategory category) { }
    private record PlanningSnapshot(Set<Long> canonicalOwners,
                                    Map<PreferenceKey, Set<NotificationChannel>> enabledChannels,
                                    Set<Long> officialAccountUsers) { }

    private boolean isReminderSubjectCurrent(NotifyPlan plan) {
        if (Integer.valueOf(3).equals(plan.getSourceType())) {
            return true; // legacy broadcast compatibility; no new producer uses ReminderPlan for broadcasts
        }
        if (plan.getSourceId() == null) return false;
        return reminderSubjectStatuses.stream()
                .filter(status -> status.sourceType() == plan.getSourceType())
                .findFirst()
                .map(status -> status.isCurrentAndEligible(plan.getSourceId(), plan.getGeneration(),
                        plan.getTimelineId(), LocalDateTime.now()))
                .orElse(false);
    }

    private boolean shouldValidateTarget(NotifyPayload payload) {
        return payload.getTargetType() != null && payload.getTargetId() != null;
    }

}
