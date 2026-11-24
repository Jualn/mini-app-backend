package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventDetailBO;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.jualn.miniapp.common.web.StrongEtag;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 公共事项发布、下架和生命周期迁移；包内编辑协作参与原事务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicEventLifecycleService {
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    private static final cn.jualn.miniapp.module.publicevent.service.PublicEventReminderPolicy REMINDER_POLICY =
            new cn.jualn.miniapp.module.publicevent.service.PublicEventReminderPolicy();
    private final PublicEventMapper examInfoMapper;
    private final TimelineService timelineService;
    private final RedisService redisService;
    private final NotifyService notifyService;
    private final PublicEventQueries queries;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation> safetyReviews;

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PublicEventDetailBO transitionAdminPublicEvent(Long id, Long operatorId, String ifMatch, String action) {
        String permission=switch(action){case "publish"->"public-event:publish";case "unpublish"->"public-event:unpublish";case "cancel","end"->"public-event:lifecycle";default->throw new BusinessException(ResultCode.FORBIDDEN);};
        authorization.require(operatorId,"public-event:edit","PUBLIC_EVENT",permission);

        requireOperator(operatorId);
        PublicEvent current = lockedEvent(id);
        long version = current.getContractVersion() == null ? 1L : current.getContractVersion();
        StrongEtag.require(ifMatch, "public-event", id, version);
        int publication = current.getPublishStatus() == null ? 0 : current.getPublishStatus();
        int lifecycle = current.getLifecycleStatus() == null ? 0 : current.getLifecycleStatus();
        int nextPublication = publication; int nextLifecycle = lifecycle;
        boolean publishing = false;
        switch (action) {
            case "publish" -> {
                if (publication == 1) return queries.loadPublicEventDetail(current);
                validatePublish(current);
                nextPublication = 1;
                publishing = true;
            }
            case "unpublish" -> {
                if (publication == 2) return queries.loadPublicEventDetail(current);
                if (publication != 1)
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                            "state-conflict", "Only published content can be unpublished");
                nextPublication = 2;
            }
            case "cancel" -> {
                if (lifecycle == 2) return queries.loadPublicEventDetail(current);
                if (publication != 1 || lifecycle != 0)
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                            "state-conflict", "Only a published active public event can be cancelled");
                nextPublication = 1;
                nextLifecycle = 2;
            }
            case "end" -> {
                if (lifecycle == 1) return queries.loadPublicEventDetail(current);
                if (publication != 1 || lifecycle != 0)
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                            "state-conflict", "Only a published active public event can be ended");
                nextLifecycle = 1;
            }
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "Unknown lifecycle action");
        }
        if(examInfoMapper.transitionContract(id,version,nextPublication,nextLifecycle,publishing)!=1)
            throw cn.jualn.miniapp.common.exception.ContractProblemException.preconditionFailed();
        notifyService.replaceEventReminder(TargetType.EXAM,id,current.getTitle(),null);
        if("unpublish".equals(action)) safetyReviews.getObject().invalidateSubject(3,id);
        PublicEvent changed=examInfoMapper.selectForUpdate(id);
        if(publishing&&nextLifecycle==0)refreshEventReminder(changed);
        if ("cancel".equals(action)) {
            notifyService.enqueueBusinessNotification(TargetType.EXAM, id,
                    "public-event:" + id + ":cancel:v" + changed.getContractVersion(),
                    cn.jualn.miniapp.module.notify.enums.NotifyType.PUBLIC_EVENT_CANCELLED, "SUBSCRIBERS",
                    "公共事项已取消", "公共事项「" + changed.getTitle() + "」已取消",
                    subjectSnapshot(changed, "公共事项已取消", "公共事项「" + changed.getTitle() + "」已取消"));
        }
        invalidateEventAfterCommit(id);
        managementAudit.operation(java.util.UUID.randomUUID().toString(),operatorId,switch(action) {case "publish"->"publishPublicEvent";case "unpublish"->"unpublishPublicEvent";case "cancel"->"cancelPublicEvent";default->"endPublicEvent";},"PUBLIC_EVENT",String.valueOf(id),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return queries.loadPublicEventDetail(changed);
    }

    void validatePublish(PublicEvent exam) {
        List<cn.jualn.miniapp.common.exception.ContractProblemException.Violation> errors = new ArrayList<>();
        if (exam.getTitle() == null || exam.getTitle().isBlank()) errors.add(publishError("/title", "标题不能为空"));
        if (exam.getSummary() == null || exam.getSummary().isBlank()) errors.add(publishError("/summary", "摘要不能为空"));
        if (exam.getEventType() == null) errors.add(publishError("/type", "类型不能为空"));
        if (exam.getSourceName() == null || exam.getSourceName().isBlank()) errors.add(publishError("/sourceName", "来源名称不能为空"));
        if ((exam.getSourceUrl() == null || exam.getSourceUrl().isBlank())
                && (exam.getOfficialUrl() == null || exam.getOfficialUrl().isBlank()))
            errors.add(publishError("/sourceUrl", "sourceUrl或officialUrl至少提供一个"));
        if (!errors.isEmpty()) throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                "publish-validation-failed", "PublicEvent未满足发布完整性", errors);
    }

    void refreshEventReminder(PublicEvent exam) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        List<cn.jualn.miniapp.module.timeline.bo.TimelineFactBO> timeline =
                timelineService.listTimelinesByTarget(TargetType.EXAM, exam.getId());
        notifyService.reconcileReminders(TargetType.EXAM, exam.getId(),
                exam.getContractVersion() == null ? 0L : exam.getContractVersion(),
                REMINDER_POLICY.evaluate(exam, timeline, now), now);
    }

    void enqueuePublicEventActionableChanges(PublicEvent saved,
            List<cn.jualn.miniapp.module.timeline.bo.TimelineFactBO> previousTimeline,
            List<cn.jualn.miniapp.module.timeline.bo.TimelineFactBO> currentTimeline) {
        long version = saved.getContractVersion() == null ? 1L : saved.getContractVersion();
        java.util.Set<String> semantics = java.util.Set.of("PUBLIC_EVENT_START", "REGISTRATION_END");
        if (cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.exactTimeChanged(
                previousTimeline, currentTimeline, semantics)) {
            notifyService.enqueueBusinessNotification(TargetType.EXAM, saved.getId(),
                    "public-event:" + saved.getId() + ":time:v" + version,
                    cn.jualn.miniapp.module.notify.enums.NotifyType.PUBLIC_EVENT_TIME_CHANGED, "SUBSCRIBERS",
                    "公共事项时间已变更", "公共事项「" + saved.getTitle() + "」的行动时间已变更",
                    changeSnapshot(saved, "公共事项时间已变更", cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector
                            .timeChanges(previousTimeline, currentTimeline, semantics)));
        }
        if (cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.effectiveLocationChanged(
                previousTimeline, currentTimeline, semantics)) {
            notifyService.enqueueBusinessNotification(TargetType.EXAM, saved.getId(),
                    "public-event:" + saved.getId() + ":location:v" + version,
                    cn.jualn.miniapp.module.notify.enums.NotifyType.PUBLIC_EVENT_LOCATION_CHANGED, "SUBSCRIBERS",
                    "公共事项地点已变更", "公共事项「" + saved.getTitle() + "」的行动地点已变更",
                    changeSnapshot(saved, "公共事项地点已变更", cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector
                            .locationChanges(previousTimeline, currentTimeline, semantics)));
        }
    }

    void invalidateEventAfterCommit(Long id) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try {
                            redisService.delete(RedisKeyConstant.examDetail(id));
                            redisService.delete(RedisKeyConstant.targetExists(TargetType.EXAM.getKey(), id));
                        } catch (RuntimeException e) { log.error("公共事项缓存失效失败，id={}", id, e); }
                    }
                });
    }

    private PublicEvent lockedEvent(Long id) {
        if (id == null) throw new BusinessException(ResultCode.BAD_REQUEST);
        PublicEvent exam = examInfoMapper.selectForUpdate(id);
        if (exam == null) throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        return exam;
    }

    private void requireOperator(Long id) {
        if (id == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
    }

    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot subjectSnapshot(PublicEvent event, String title, String body) {
        String id = event.getId().toString();
        return new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot(
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation(title, body, null, null, null, event.getTitle(), null),
                null, new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject("PUBLIC_EVENT", id),
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("PUBLIC_EVENT_DETAIL", null, null, null, id));
    }

    private cn.jualn.miniapp.common.exception.ContractProblemException.Violation publishError(
            String pointer, String detail) {
        return new cn.jualn.miniapp.common.exception.ContractProblemException.Violation(
                "body", pointer, "REQUIRED", detail);
    }

    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot changeSnapshot(PublicEvent event, String title,
            List<cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.Change> changes) {
        String id = event.getId().toString();
        return new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot(
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation(title, null, event.getTitle(), null,
                        changes.stream().map(change -> new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Change(
                                change.label(), change.before(), change.after())).toList(), event.getTitle(), null), null,
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject("PUBLIC_EVENT", id),
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("PUBLIC_EVENT_DETAIL", null, null, null, id));
    }
}
