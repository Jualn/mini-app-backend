package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import cn.jualn.miniapp.common.web.StrongEtag;

import java.time.LocalDateTime;
import java.util.*;

/** 发布、下架及生命周期状态所有者；包内编辑协作沿用调用方持有的行锁和事务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityLifecycleService {
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    private final EventContentService eventContentService;
    private static final ActivityReminderPolicy REMINDER_POLICY = new ActivityReminderPolicy();
    private final ActivityMapper activityMapper;
    private final ActivityPublicHistory publicHistory;
    private final TimelineService timelineService;
    private final RedisService redisService;
    private final NotifyService notifyService;
    private final ActivityParticipationPolicy participationPolicy;
    private final ActivityAdminQueries adminQueries;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation> safetyReviews;

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void publishAdminActivity(Long activityId, Long operatorId) {
        authorization.require(operatorId,"activity:edit","ACTIVITY","activity:publish");

        validateAdminAction(activityId, operatorId, null, false);
        Activity current = activityMapper.selectForUpdate(activityId);
        assertAdminEditable(current);
        requireExplicitParticipantMode(current.getParticipantMode());
        if (publication(current) == 1) return;
        validateAdminSubmission(current);
        if (activityMapper.publishDirectly(activityId, initialFormVersionFor(current)) != 1)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动发布失败");
        publicHistory.remember(activityMapper.selectForUpdate(activityId));
        refreshActivityReminder(activityMapper.selectForUpdate(activityId));
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
    }

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void takeDownAdminActivity(Long activityId, Long operatorId, String reason) {
        authorization.require(operatorId,"activity:edit","ACTIVITY","activity:unpublish");

        validateAdminAction(activityId, operatorId, reason, true);
        Activity current = activityMapper.selectForUpdate(activityId);
        if (current == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        if (publication(current) == 2) return;
        publicHistory.remember(current);
        if (publication(current) != 1 || activityMapper.takeDownDirectly(activityId) != 1)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "只有已发布活动可以下架");
        notifyService.cancelActivityPlans(activityId);
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
        safetyReviews.getObject().invalidateSubject(2,activityId);
        log.info("[活动下架] id={}, operatorId={}", activityId, operatorId);
    }

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AdminActivityDetailBO transitionAdminActivity(Long activityId, Long operatorId, String ifMatch, String action) {
        String permission=switch(action){case "publish"->"activity:publish";case "unpublish"->"activity:unpublish";case "cancel","end"->"activity:lifecycle";default->throw new BusinessException(ResultCode.FORBIDDEN);};
        authorization.require(operatorId,"activity:edit","ACTIVITY",permission);

        validateAdminAction(activityId, operatorId, null, false);
        Activity current = activityMapper.selectForUpdate(activityId);
        if (current == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        long version = current.getContractVersion() == null ? 1L : current.getContractVersion();
        managementAudit.confirmed(operatorId,switch(action) {case "publish"->"publishActivity";case "unpublish"->"unpublishActivity";case "cancel"->"cancelActivity";default->"endActivity";},"ACTIVITY",activityId.toString());
        StrongEtag.require(ifMatch, "activity", activityId, version);
        int publication = publication(current);
        int lifecycle = current.getLifecycleStatus() == null ? 0 : current.getLifecycleStatus();
        int nextPublication = publication;
        int nextLifecycle = lifecycle;
        boolean publishing = false;
        switch (action) {
            case "publish" -> {
                requireExplicitParticipantMode(current.getParticipantMode());
                if (publication == 1) return adminQueries.getAdminActivityDetail(activityId);
                validateAdminSubmission(current);
                nextPublication = 1;
                publishing = true;
            }
            case "unpublish" -> {
                if (publication == 2) return adminQueries.getAdminActivityDetail(activityId);
                if (publication != 1)
                    throw ContractProblemException.conflict("state-conflict", "Only published content can be unpublished");
                nextPublication = 2;
            }
            case "cancel" -> {
                if (lifecycle == 2) return adminQueries.getAdminActivityDetail(activityId);
                if (publication != 1 || lifecycle != 0)
                    throw ContractProblemException.conflict("state-conflict", "Only a published active activity can be cancelled");
                nextPublication = 1;
                nextLifecycle = 2;
            }
            case "end" -> {
                if (lifecycle == 1) return adminQueries.getAdminActivityDetail(activityId);
                if (publication != 1 || lifecycle != 0)
                    throw ContractProblemException.conflict("state-conflict", "Only a published active activity can be ended");
                nextLifecycle = 1;
            }
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "Unknown lifecycle action");
        }
        String formVersion = publishing ? initialFormVersionFor(current) : null;
        if ("unpublish".equals(action)) publicHistory.remember(current);
        int updated = activityMapper.transitionContract(activityId, version, nextPublication, nextLifecycle,
                publishing, formVersion);
        if (updated != 1) {
            throw ContractProblemException.preconditionFailed(
                    "activity-transition update-count=" + updated + ", id=" + activityId + ", version=" + version
                            + ", action=" + action);
        }
        notifyService.cancelActivityPlans(activityId);
        if("unpublish".equals(action)) safetyReviews.getObject().invalidateSubject(2,activityId);
        Activity changed = activityMapper.selectForUpdate(activityId);
        if (publishing) publicHistory.remember(changed);
        if (publishing && nextLifecycle == 0) refreshActivityReminder(changed);
        if ("cancel".equals(action)) {
            notifyService.enqueueBusinessNotification(TargetType.ACTIVITY, activityId,
                    "activity:" + activityId + ":cancel:v" + changed.getContractVersion(),
                    NotifyType.ACTIVITY_CANCELLED, "SUBSCRIBERS_OR_REGISTERED_USERS", "活动已取消",
                    "活动「" + changed.getTitle() + "」已取消",
                    subjectSnapshot(changed, "活动已取消", "活动「" + changed.getTitle() + "」已取消"));
        } else if ("end".equals(action)) {
            notifyService.enqueueBusinessNotification(TargetType.ACTIVITY, activityId,
                    "activity:" + activityId + ":end:v" + changed.getContractVersion(),
                    NotifyType.ACTIVITY_ENDED_EARLY, "SUBSCRIBERS_OR_REGISTERED_USERS", "活动已提前结束",
                    "活动「" + changed.getTitle() + "」已提前结束",
                    subjectSnapshot(changed, "活动已提前结束", "活动「" + changed.getTitle() + "」已提前结束"));
        }
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
        managementAudit.operation(java.util.UUID.randomUUID().toString(),operatorId,switch(action) {case "publish"->"publishActivity";case "unpublish"->"unpublishActivity";case "cancel"->"cancelActivity";default->"endActivity";},"ACTIVITY",String.valueOf(activityId),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return adminQueries.getAdminActivityDetail(changed.getId());
    }

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void cancelAdminActivity(Long activityId, Long operatorId, String reason) {
        authorization.require(operatorId,"activity:edit","ACTIVITY","activity:lifecycle");

        validateAdminAction(activityId, operatorId, reason, true);
        Activity current = activityMapper.selectForUpdate(activityId);
        if (current == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        if (Integer.valueOf(2).equals(current.getLifecycleStatus())) return;
        if (activityMapper.cancelAdminActivity(activityId, reason.trim()) != 1) {
            throwAdminActivityConflict(activityId, "只有尚未开始的报名中活动可以取消");
        }
        notifyService.cancelActivityPlans(activityId);
        String noticeContent = "活动「" + current.getTitle() + "」已取消：" + reason.trim();
        notifyService.enqueueBusinessNotification(TargetType.ACTIVITY, activityId,
                "activity:" + activityId + ":cancel:v" + nextVersion(current), NotifyType.ACTIVITY_CANCELLED,
                "SUBSCRIBERS_OR_REGISTERED_USERS", "活动已取消", noticeContent,
                subjectSnapshot(current, "活动已取消", noticeContent));
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
        log.info("[管理端活动] 管理员 {} 取消活动 {}", operatorId, activityId);
    }

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void endAdminActivityEarly(Long activityId, Long operatorId, String reason) {
        authorization.require(operatorId,"activity:edit","ACTIVITY","activity:lifecycle");

        validateAdminAction(activityId, operatorId, reason, true);
        Activity current = activityMapper.selectForUpdate(activityId);
        if (current == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        if (activityMapper.endAdminActivityEarly(activityId) != 1) {
            throwAdminActivityConflict(activityId, "只有进行中的活动可以提前结束");
        }
        notifyService.cancelActivityPlans(activityId);
        String noticeContent = "活动「" + current.getTitle() + "」已提前结束：" + reason.trim();
        notifyService.enqueueBusinessNotification(TargetType.ACTIVITY, activityId,
                "activity:" + activityId + ":end:v" + nextVersion(current), NotifyType.ACTIVITY_ENDED_EARLY,
                "SUBSCRIBERS_OR_REGISTERED_USERS", "活动已提前结束", noticeContent,
                subjectSnapshot(current, "活动已提前结束", noticeContent));
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
        log.info("[管理端活动] 管理员 {} 提前结束活动 {}", operatorId, activityId);
    }

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AdminActivityDetailBO recycle(Long id, Long operator, String ifMatch, boolean restore) {
        authorization.require(operator, "activity:edit", "ACTIVITY", restore ? "activity:edit" : "activity:unpublish");
        Activity current = activityMapper.lockRegistrationActivity(id);
        if (current == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        managementAudit.confirmed(operator, restore ? "restoreActivity" : "deleteActivity", "ACTIVITY", id.toString());
        StrongEtag.require(ifMatch, "activity", id, current.getContractVersion());
        if (restore == (current.getDeletedAt() == null)) return adminQueries.readAfterWrite(id);
        if (!restore) {
            if (Integer.valueOf(1).equals(current.getPublishStatus()))
                throw ContractProblemException.conflict("state-conflict", "Unpublish the activity before deletion");
            if (activityMapper.hasRegistrationHistory(id))
                throw ContractProblemException.conflict("activity-has-registration-history", "Registration history prevents deletion");
        }
        if (activityMapper.recycle(id, operator, restore) != 1) throw ContractProblemException.preconditionFailed();
        notifyService.cancelActivityPlans(id);
        afterCommit(() -> {
            redisService.delete(RedisKeyConstant.activityDetail(id));
            redisService.delete(RedisKeyConstant.targetExists(TargetType.ACTIVITY.getKey(), id));
        });
        managementAudit.operation(java.util.UUID.randomUUID().toString(), operator, restore ? "restoreActivity" : "deleteActivity",
                "ACTIVITY", id.toString(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return adminQueries.readAfterWrite(id);
    }

    void validateAdminSubmission(Activity activity) {
        requireExplicitParticipantMode(activity.getParticipantMode());
        var missing = new ArrayList<ContractProblemException.Violation>();
        if (!StringUtils.hasText(activity.getTitle())) missing.add(publicationViolation("/title","发布需要标题"));
        if (!StringUtils.hasText(activity.getSummary())) missing.add(publicationViolation("/summary","发布需要摘要"));
        if (!StringUtils.hasText(activity.getOrganizer())) missing.add(publicationViolation("/organizer","发布需要主办方"));
        if (!StringUtils.hasText(activity.getAudienceSummary())) missing.add(publicationViolation("/audienceSummary","发布需要参与范围说明"));
        if (activity.getCategory() == null) missing.add(publicationViolation("/category","发布需要分类"));
        if (activity.getAudienceDepartmentIds() == null) missing.add(publicationViolation("/audienceScope","发布需要参与范围"));
        if (!missing.isEmpty()) throw ContractProblemException.conflict("publish-validation-failed","发布内容不完整",missing);
        List<TimelineFactBO> timeline =
                timelineService.listTimelinesByTarget(TargetType.ACTIVITY, activity.getId());
        if (activity.getRegistrationMode() == null || !Set.of(1, 2, 3, 4).contains(activity.getRegistrationMode()))
            throw publicationInvalid("/registrationMode", "发布前请确认报名方式");
        if ((activity.getCapacity() == null) != (activity.getCapacityUnit() == null)
                || (activity.getCapacity() != null && activity.getCapacity() <= 0)
                || (activity.getCapacityUnit() != null && !Set.of(1, 2).contains(activity.getCapacityUnit())))
            throw publicationInvalid("/capacity", "capacity 与 capacityUnit 必须成对且合法");
        if (activity.getCapacityUnit() != null
                && ((Integer.valueOf(1).equals(activity.getParticipantMode()) && !Integer.valueOf(1).equals(activity.getCapacityUnit()))
                || (Integer.valueOf(2).equals(activity.getParticipantMode()) && !Integer.valueOf(2).equals(activity.getCapacityUnit()))))
            throw publicationInvalid("/capacityUnit", "参与形式与容量单位不一致");
        if (activity.getRegistrationMode() == 1
                && activity.getFormSchema() != null)
            throw publicationInvalid("/registrationForm", "无需报名时不能配置平台表单");
        if (Set.of(3, 4).contains(activity.getRegistrationMode())
                && eventContentService.actions(TargetType.ACTIVITY, activity.getId()).isEmpty())
            throw publicationInvalid("/actions", "外部报名或组合报名须提供至少一个合法参与入口");
        if (activity.getRegistrationMode() == 3 && activity.getFormSchema() != null)
            throw publicationInvalid("/registrationForm", "纯外部报名不能配置平台表单");
        if (Set.of(2, 4).contains(activity.getRegistrationMode())) {
            if (!Integer.valueOf(1).equals(activity.getParticipantMode()))
                throw publicationInvalid("/participantMode", "平台表单仅支持个人报名");
            if (activity.getFormSchema() == null) throw publicationInvalid("/registrationForm", "平台报名需要表单");
            try { ActivityFormPolicy.schema(ActivityFormPolicy.parse(activity.getFormSchema())); }
            catch (BusinessException invalid) { throw publicationInvalid("/registrationForm", "报名表单不合法"); }
            if (!participationPolicy.window(timeline).platformValid())
                throw publicationInvalid("/timeline", "须有唯一EXACT_POINT报名截止且至多一个有效报名开始，开始须早于截止");
            if (activity.getCapacity() != null) {
                var counts = activityMapper.statistics(activity.getId());
                if (counts != null && counts.submittedCount() > activity.getCapacity())
                    throw publicationInvalid("/capacity", "名额不能小于当前有效报名人数");
            }
        }
    }

    private static ContractProblemException.Violation publicationViolation(String pointer, String detail) {
        return new ContractProblemException.Violation("body",pointer,"invalid",detail);
    }

    private static ContractProblemException publicationInvalid(String pointer, String detail) {
        return ContractProblemException.conflict("publish-validation-failed",detail,List.of(publicationViolation(pointer,detail)));
    }

    void requireExplicitParticipantMode(Integer mode) {
        if (mode == null || !Set.of(1, 2).contains(mode)) {
            throw ContractProblemException.conflict(
                    "publish-validation-failed", "发布前请明确参与形式",
                    List.of(new ContractProblemException.Violation(
                            "body", "/participantMode", "required", "发布必须明确个人或团队参与形式")));
        }
    }

    void initializeFormVersionIfAbsent(Activity activity) {
        String formVersion = initialFormVersionFor(activity);
        if (formVersion == null || activity.getFormVersion() != null) return;
        if (activityMapper.initializeFormVersionIfAbsent(activity.getId(), formVersion) != 1)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动表单版本初始化失败");
        activity.setFormVersion(formVersion);
    }

    void refreshActivityReminder(Activity activity) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        List<TimelineFactBO> timeline =
                timelineService.listTimelinesByTarget(TargetType.ACTIVITY, activity.getId());
        notifyService.reconcileReminders(TargetType.ACTIVITY, activity.getId(),
                activity.getContractVersion() == null ? 0L : activity.getContractVersion(),
                REMINDER_POLICY.evaluate(activity, timeline, now), now);
    }

    void enqueueActivityActionableChanges(Activity saved, String previousLocation,
            List<TimelineFactBO> previousTimeline,
            List<TimelineFactBO> currentTimeline) {
        long version = saved.getContractVersion() == null ? 1L : saved.getContractVersion();
        var timeChanges = ActionableTimelineChangeDetector
                .activityTimeChanges(previousTimeline, currentTimeline);
        if (!timeChanges.isEmpty()) {
            notifyService.enqueueBusinessNotification(TargetType.ACTIVITY, saved.getId(),
                    "activity:" + saved.getId() + ":time:v" + version, NotifyType.ACTIVITY_TIME_CHANGED,
                    "SUBSCRIBERS_OR_REGISTERED_USERS", "活动时间已变更",
                    "活动「" + saved.getTitle() + "」的行动时间已变更",
                    changeSnapshot(saved, "活动时间已变更", timeChanges));
        }
        boolean locationChanged = ActionableTimelineChangeDetector
                .effectiveValueChanged(previousLocation, saved.getLocation())
                || ActionableTimelineChangeDetector
                .effectiveLocationChanged(previousTimeline, currentTimeline, Set.of("ACTIVITY_START"));
        if (locationChanged) {
            notifyService.enqueueBusinessNotification(TargetType.ACTIVITY, saved.getId(),
                    "activity:" + saved.getId() + ":location:v" + version,
                    NotifyType.ACTIVITY_LOCATION_CHANGED, "SUBSCRIBERS_OR_REGISTERED_USERS", "活动地点已变更",
                    "活动「" + saved.getTitle() + "」的行动地点已变更",
                    changeSnapshot(saved, "活动地点已变更", activityLocationChanges(previousLocation, saved.getLocation(), previousTimeline, currentTimeline)));
        }
    }

    int publication(Activity activity) {
        return activity.getPublishStatus() == null ? 0 : activity.getPublishStatus();
    }

    private void validateAdminAction(Long activityId, Long operatorId, String reason, boolean reasonRequired) {
        if (activityId == null || operatorId == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动管理操作参数不完整");
        }
        if (reasonRequired && !StringUtils.hasText(reason)) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "操作原因不能为空");
        }
    }

    private void assertAdminEditable(Activity activity) {
        if (activity == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        if (activity.getDeletedAt() != null)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "已删除活动不可编辑");
    }

    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.error("活动提交后副作用失败", e);
                    }
                }
            });
        } else {
            task.run();
        }
    }

    private String initialFormVersionFor(Activity activity) {
        return activity != null && participationPolicy.platform(activity.getRegistrationMode())
                ? "activity-" + activity.getId() + "-form-v1" : null;
    }

    private NotificationCenterBO.Snapshot subjectSnapshot(Activity activity, String title, String body) {
        String id = activity.getId().toString();
        return new NotificationCenterBO.Snapshot(
                new NotificationCenterBO.Presentation(title, body, null, null, null, activity.getTitle(), null),
                null, new NotificationCenterBO.Subject("ACTIVITY", id),
                new NotificationCenterBO.Target("ACTIVITY_DETAIL", null, null, id, null));
    }

    private void throwAdminActivityConflict(Long activityId, String message) {
        if (activityMapper.selectAdminActivityById(activityId) == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        throw new BusinessException(ResultCode.DATA_CONFLICT, message);
    }

    private long nextVersion(Activity activity) {
        return (activity.getContractVersion() == null ? 1L : activity.getContractVersion()) + 1L;
    }

    private List<ActionableTimelineChangeDetector.Change> activityLocationChanges(
            String before, String after, List<TimelineFactBO> previous,
            List<TimelineFactBO> current) {
        var changes = new ArrayList<ActionableTimelineChangeDetector.Change>();
        var oldMain = uniqueMain(previous);
        var newMain = uniqueMain(current);
        addLocationChange(changes, "活动节点地点", oldMain == null ? null : oldMain.getLocation(), newMain == null ? null : newMain.getLocation());
        addLocationChange(changes, "活动地点", before, after);
        return List.copyOf(changes);
    }

    private TimelineFactBO uniqueMain(List<TimelineFactBO> timeline) {
        var nodes = timeline.stream().filter(node -> "ACTIVITY_START".equals(node.getNodeType())).toList();
        return nodes.size() == 1 ? nodes.get(0) : null;
    }

    private void addLocationChange(List<ActionableTimelineChangeDetector.Change> changes, String label, String before, String after) {
        if (!ActionableTimelineChangeDetector.activityLocationChanged(before, after)) return;
        String oldValue = ActionableTimelineChangeDetector.locationValue(before);
        String newValue = ActionableTimelineChangeDetector.locationValue(after);
        changes.add(new ActionableTimelineChangeDetector.Change(label, oldValue == null ? "原待定" : oldValue,
                newValue == null ? "已撤回" : newValue));
    }

    private NotificationCenterBO.Snapshot changeSnapshot(Activity activity, String title,
            List<ActionableTimelineChangeDetector.Change> changes) {
        String id = activity.getId().toString();
        return new NotificationCenterBO.Snapshot(
                new NotificationCenterBO.Presentation(title, null, activity.getTitle(), null,
                        changes.stream().map(change -> new NotificationCenterBO.Change(
                                change.label(), change.before(), change.after())).toList(), activity.getTitle(), null), null,
                new NotificationCenterBO.Subject("ACTIVITY", id),
                new NotificationCenterBO.Target("ACTIVITY_DETAIL", null, null, id, null));
    }
}
