package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext;
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
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.notify.reminder.ReminderSubjectStatus;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
import cn.jualn.miniapp.module.notify.reminder.ReminderReconcileService;
import cn.jualn.miniapp.module.user.service.UserService;
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
    private final NotifyPlanMapper notifyPlanMapper;
    private final UserService userService;
    private final List<cn.jualn.miniapp.module.notify.service.NotificationSource> sources;
    private final RedisService redisService;
    private final NotifyConverter notifyConverter;
    private final JobService jobService;
    private final TransactionTemplate transactionTemplate;
    private static final int BATCH_SIZE = 100;
    private final cn.jualn.miniapp.module.notify.service.NotificationAcceptance creationService;
    private final List<ReminderSubjectStatus> reminderSubjectStatuses;
    private final ReminderReconcileService reminderReconcileService;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void catchUpReminder(cn.jualn.miniapp.module.notify.async.ReminderCatchUpPayload payload, JobExecutionContext context) {
        var rule = payload.rule();
        var now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        if (rule == null || rule.sendAt().isAfter(now) || rule.subjectStartsAt() == null || !rule.subjectStartsAt().isAfter(now)) return;
        var intent = new cn.jualn.miniapp.module.notify.reminder.ReminderExecution(payload.activityId(), null,
                rule.timelineId(), rule.notificationType().getCode(), rule.ruleKey(), rule.subjectStartsAt(), rule.sendAt());
        var owner = reminderSubjectStatuses.stream().filter(status -> status.sourceType() == 1).findFirst();
        if (owner.isEmpty() || !owner.get().lockCurrentAndEligible(intent, now)) return;
        if (context != null) context.requireOwnership();
        if (!source(1).recipients(payload.activityId(), rule.recipientScope(), payload.userId() - 1, payload.userId(), 1).contains(payload.userId())) return;
        var plan = NotifyPlan.builder().sourceType(1).sourceId(payload.activityId()).timelineId(rule.timelineId())
                .ruleKey(rule.ruleKey()).generation(Math.addExact(notifyPlanMapper.maxGeneration(1, payload.activityId(), rule.timelineId(), rule.ruleKey()), 1))
                .recipientScope(rule.recipientScope()).notifyType(rule.notificationType().getCode())
                .title(rule.title()).content(rule.content()).subjectStartsAt(rule.subjectStartsAt()).subjectLocation(rule.subjectLocation())
                .scope(0).scene(rule.ruleKey()).sendAt(rule.sendAt()).status(1)
                .recipientUpperUserId(payload.userId()).completedAt(now).build();
        notifyPlanMapper.insert(plan);
        var notifications = buildNotifyPayloads(List.of(payload.userId()), plan);
        if (!notifications.isEmpty()) creationService.acceptBatch(notifications);
    }

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
        Long count = notificationMapper.countCanonicalUnread(userId);
        return count == null ? 0L : count;

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
        reminderReconcileService.reconcile(sourceType, id, 0L, List.of(), LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
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
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), 6));
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
        if (sources.stream().noneMatch(owner->owner.targetType()==targetType) || targetId == null
                || eventIdentity == null || eventIdentity.isBlank() || categoryOf(notificationType) == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "业务通知事件身份不完整");
        }
        BusinessNotificationJobPayload payload = new BusinessNotificationJobPayload(
                targetType.getCode(), targetId, eventIdentity, notificationType.getCode(),
                recipientScope, title, content, snapshot, cn.jualn.miniapp.module.notify.service.NotificationObservation.now());
        jobService.create(new JobDefinition("business-notification.fanout", 1,
                ObservabilityContext.ensureOperationId(), eventIdentity, targetType.name(),
                String.valueOf(targetId), payload, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), 6));
    }

    @Override
    public void notifyActivitySubscribers(Long activityId, String eventKey, String title, String content) {
        notifyActivitySubscribers(activityId,eventKey,title,content,null);
    }

    @Override
    public void notifyActivitySubscribers(Long activityId,String eventKey,String title,String content,JobExecutionContext context) {
        if (activityId == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "activityId 不能为空");
        }
        long lastId = 0L;
        while (true) {
            if(context!=null) context.prepareBatch();
            List<Long> userIds = source(1).recipients(activityId, "SUBSCRIBERS", lastId, Long.MAX_VALUE, BATCH_SIZE);
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
                transactionTemplate.executeWithoutResult(status -> {
                    if(context!=null) context.requireOwnership();
                    creationService.accept(payload);
                });
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
            int started = context == null ? notifyPlanMapper.startFanOut(planId)
                    : transactionTemplate.execute(tx -> { context.requireOwnership(); return notifyPlanMapper.startFanOut(planId); });
            if (started != 1) return;
            plan = notifyPlanMapper.selectById(planId);
        }

        long lastId = 0L;
        int total = 0;
        if (plan.getRecipientUpperUserId() == null) {
            long bound = Integer.valueOf(3).equals(plan.getSourceType()) ? Long.MAX_VALUE
                    : source(plan.getSourceType()).upperBound(plan.getSourceId(), plan.getRecipientScope() == null ? "SUBSCRIBERS" : plan.getRecipientScope());
            if (context == null) notifyPlanMapper.freezeRecipientUpperBound(planId, bound);
            else transactionTemplate.executeWithoutResult(tx -> {
                context.requireOwnership(); notifyPlanMapper.freezeRecipientUpperBound(planId, bound);
            });
            plan = notifyPlanMapper.selectById(planId);
            if (plan == null || plan.getRecipientUpperUserId() == null) return;
        }
        long upperBound = plan.getRecipientUpperUserId();

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
            List<Long> userIds = queryUserIds(plan, lastId, upperBound);
            if (userIds.isEmpty()) {
                break;
            }

            NotifyPlan batchPlan = plan;
            long batchLastId = lastId;
            Boolean batchValid = transactionTemplate.execute(tx -> {
                var intent = reminderIntent(batchPlan);
                var owner = reminderSubjectStatuses.stream().filter(status -> status.sourceType() == batchPlan.getSourceType()).findFirst();
                if (!Integer.valueOf(3).equals(batchPlan.getSourceType())
                        && (owner.isEmpty() || !owner.get().lockCurrentAndEligible(intent,
                            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))) return false;
                if (context != null) context.requireOwnership();
                NotifyPlan currentPlan = notifyPlanMapper.selectById(planId);
                if (currentPlan == null || !Integer.valueOf(3).equals(currentPlan.getStatus())
                        || !java.util.Objects.equals(reminderIntent(currentPlan), intent)) return false;
                Set<Long> currentRecipients = new HashSet<>(queryUserIds(currentPlan, batchLastId,
                        userIds.get(userIds.size() - 1)));
                List<NotifyPayload> payloads = buildNotifyPayloads(userIds.stream().filter(currentRecipients::contains).toList(), currentPlan);
                creationService.acceptBatch(payloads);
                return true;
            });
            if (!Boolean.TRUE.equals(batchValid)) {
                transactionTemplate.executeWithoutResult(tx -> {
                    if (context != null) context.requireOwnership();
                    notifyPlanMapper.cancelPlan(planId);
                });
                return;
            }
            meters.counter("jualn.notification.recipients", "type", "reminder").increment(userIds.size());

            total += userIds.size();

            if (userIds.size() < BATCH_SIZE) {
                break;
            }

            lastId = userIds.get(userIds.size() - 1);
            if (lastId >= upperBound) break;
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (context != null) context.requireOwnership();
            notifyPlanMapper.completeFanOut(planId);
        });

        // status=1 records completed fan-out, independent of channel delivery outcomes.
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
        creationService.accept(payload);
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
    private List<Long> queryUserIds(NotifyPlan plan, long lastId, long upperBound) {
        if (plan == null) return List.of();
        if (Integer.valueOf(1).equals(plan.getScope()))
            return userService.listAllUserIds(lastId, BATCH_SIZE).stream().filter(id->id<=upperBound).toList();
        return source(plan.getSourceType()).recipients(plan.getSourceId(),plan.getRecipientScope(),lastId,upperBound,BATCH_SIZE);
    }

    private List<NotifyPayload> buildNotifyPayloads(List<Long> users, NotifyPlan plan) {
        if (Integer.valueOf(3).equals(plan.getSourceType())) return users.stream().map(user -> NotifyPayload.builder()
            .receiverId(user).type(NotifyType.fromCode(plan.getNotifyType())).title(plan.getTitle()).content(plan.getContent())
            .sourceKey("plan:"+plan.getId()+":user:"+user).build()).toList();
        // Preserve the existing historical start-occurrence barrier while reading new business facts once per batch.
        List<Long> eligible = users;
        if (Integer.valueOf(1).equals(plan.getSourceType()) && Integer.valueOf(8).equals(plan.getNotifyType())
                && plan.getSubjectStartsAt() != null && plan.getSubjectStartsAt().getNano() == 0)
            eligible = users.stream().filter(user -> notificationMapper.selectHistoricalStartOccurrence(user, TargetType.ACTIVITY.getCode(),
                    plan.getSourceId(), plan.getSubjectStartsAt().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))) == null).toList();
        return source(plan.getSourceType()).reminders(eligible,
                cn.jualn.miniapp.module.notify.service.ReminderPlanSnapshot.of(plan));
    }

    private cn.jualn.miniapp.module.notify.service.NotificationSource source(int type) {
        return sources.stream().filter(owner->owner.sourceType()==type).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Notification source owner is unavailable"));
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
                    .sourceKey(event.eventIdentity() + ":user:" + userId).snapshot(event.snapshot()).occurredAt(event.occurredAt()).build()).toList();
            meters.counter("jualn.notification.recipients", "type", "business_change").increment(recipients.size());
            {
                long batchLast = lastId;
                Boolean valid = transactionTemplate.execute(tx -> {
                    var owner = reminderSubjectStatuses.stream().filter(status -> status.sourceType() == source(targetType).sourceType()).findFirst();
                    if (owner.isEmpty() || !owner.get().lockBusinessEventEligible(event.targetId(), event.notificationType())) return false;
                    context.requireOwnership();
                    Set<Long> current = new HashSet<>(recipientBatch(targetType, event.targetId(), event.recipientScope(),
                            batchLast, recipients.get(recipients.size() - 1)));
                    var qualified = payloads.stream().filter(payload -> current.contains(payload.getReceiverId())).toList();
                    creationService.acceptBatch(qualified);
                    return true;
                });
                if (!Boolean.TRUE.equals(valid)) return;
            }
            lastId = recipients.get(recipients.size() - 1);
            if (recipients.size() < BATCH_SIZE) return;
        }
    }

    private cn.jualn.miniapp.module.notify.service.NotificationSource source(TargetType type) {
        return sources.stream().filter(owner->owner.targetType()==type).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Notification source owner is unavailable"));
    }
    private long recipientUpperBound(TargetType type, Long id, String scope) { return source(type).upperBound(id,scope); }
    private List<Long> recipientBatch(TargetType type,Long id,String scope,long after,long upper) {
        return source(type).recipients(id,scope,after,upper,BATCH_SIZE);
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

    private boolean isReminderSubjectCurrent(NotifyPlan plan) {
        if (Integer.valueOf(3).equals(plan.getSourceType())) {
            return true; // legacy broadcast compatibility; no new producer uses ReminderPlan for broadcasts
        }
        if (plan.getSourceId() == null) return false;
        return reminderSubjectStatuses.stream()
                .filter(status -> status.sourceType() == plan.getSourceType())
                .findFirst()
                .map(status -> status.isCurrentAndEligible(reminderIntent(plan),
                        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))
                .orElse(false);
    }

    private cn.jualn.miniapp.module.notify.reminder.ReminderExecution reminderIntent(NotifyPlan plan) {
        return new cn.jualn.miniapp.module.notify.reminder.ReminderExecution(
                plan.getSourceId() == null ? 0L : plan.getSourceId(), plan.getGeneration(), plan.getTimelineId(),
                plan.getNotifyType(), plan.getRuleKey(), plan.getSubjectStartsAt(), plan.getSendAt());
    }

    private boolean shouldValidateTarget(NotifyPayload payload) {
        return payload.getTargetType() != null && payload.getTargetId() != null;
    }

}
