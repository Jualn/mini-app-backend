package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.eventcontent.service.EventTimePolicy;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import cn.jualn.miniapp.common.web.StrongEtag;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 管理草稿和完整替换保存；已发布编辑在同一事务内复用生命周期规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityDraftService {
    private final CurrentAuthorization authorization;
    private final EventContentService eventContentService;
    private final ActivityMapper activityMapper;
    private final ActivityPublicHistory publicHistory;
    private final MediaService mediaService;
    private final TimelineService timelineService;
    private final RedisService redisService;
    private final ActivityRegistrationService activityRegistrationService;
    private final ActivityLifecycleService lifecycle;
    private final ActivityAdminQueries adminQueries;
    private final AdminAuditJournal managementAudit;

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Long createAdminActivity(AdminActivitySaveBO command) {
        authorization.require(command.getOperatorId(), "activity:edit", "ACTIVITY", "activity:edit");

        validateAdminSave(command);
        Activity activity = toAdminEntity(command);
        activity.setUserId(command.getOperatorId());
        activity.setPublishStatus(0);
        activity.setCommentCount(0);
        activity.setLikeCount(0);
        activity.setViewCount(0);
        if (activityMapper.insert(activity) != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动草稿创建失败");
        }
        saveAdminChildren(activity.getId(), command);
        log.info("[管理端活动] 管理员 {} 创建草稿 {}", command.getOperatorId(), activity.getId());
        managementAudit.operation(java.util.UUID.randomUUID().toString(), command.getOperatorId(), "createActivity", "ACTIVITY", String.valueOf(activity.getId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return activity.getId();
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void updateAdminActivity(AdminActivitySaveBO command) {
        authorization.require(command.getOperatorId(), "activity:edit", "ACTIVITY", "activity:edit");

        validateAdminSave(command);
        if (command.getId() == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动ID不能为空");
        }
        Activity current = activityMapper.selectForUpdate(command.getId());
        assertAdminEditable(current);
        if (lifecycle.publication(current) == 1) {
            authorization.require(command.getOperatorId(), "activity:edit", "ACTIVITY", "activity:publish");
            lifecycle.requireExplicitParticipantMode(command.isCanonicalFullReplacement() || command.getParticipantMode() != null
                    ? command.getParticipantMode() : current.getParticipantMode());
        }
        var originalTimeline = timelineService.listTimelinesByTarget(TargetType.ACTIVITY, current.getId());
        String originalStage = ActivityStagePolicy.state(0, originalTimeline, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        if (current.getFormVersion() != null && command.getFormSchema() != null && !command.getFormSchema().isNull()) {
            var frozen = current.getFrozenFormFields() != null ? ActivityFormPolicy.parse(current.getFrozenFormFields())
                    : current.getFormSchema() == null ? null : ActivityFormPolicy.parse(current.getFormSchema()).path("fields");
            if (frozen == null || !frozen.equals(command.getFormSchema().path("fields")))
                throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("form-locked", "首次发布后的表单字段不可改变");
        }
        boolean notifyActionableChange = lifecycle.publication(current) == 1
                && Integer.valueOf(0).equals(current.getLifecycleStatus());
        String previousLocation = current.getLocation();
        List<cn.jualn.miniapp.module.timeline.bo.TimelineFactBO> previousTimeline = notifyActionableChange
                ? timelineService.listTimelinesByTarget(TargetType.ACTIVITY, current.getId()) : List.of();
        String nextSchema = command.isCanonicalFullReplacement()
                ? (command.getFormSchema() == null ? null : command.getFormSchema().toString())
                : command.getFormSchema() == null ? current.getFormSchema()
                : command.getFormSchema().isNull() ? null : command.getFormSchema().toString();
        Integer nextCapacity = command.isCanonicalFullReplacement() ? command.getCapacity()
                : command.getCapacity() == null ? current.getCapacity() : command.getCapacity();
        activityRegistrationService.validateFormChange(current.getId(), current.getFormSchema(), nextSchema, nextCapacity,
                current.getRegistrationMode(), command.getRegistrationMode() == null ? current.getRegistrationMode() : command.getRegistrationMode(),
                command.getParticipantMode() == null ? current.getParticipantMode() : command.getParticipantMode());
        if (current.getCapacityUnit() != null && current.getCapacityUnit() == 2 && command.getCapacityUnit() == null)
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "此活动按队限制容量，请使用新版编辑器");

        int updated = activityMapper.update(null,
                new LambdaUpdateWrapper<Activity>()
                        .set(Activity::getFormSchema, nextSchema)
                        .set(Activity::getTitle, command.getTitle())
                        .set(Activity::getLocation, command.getLocation())
                        .set(Activity::getCategory, command.getCategory() == null ? null : command.getCategory().getCode())
                        .set(Activity::getOrganizer, command.getOrganizer())
                        .set(Activity::getAudienceDepartmentIds, command.getAudienceDepartmentIds())
                        .set(Activity::getContactsJson, command.getContactsJson())
                        .set(Activity::getPublishStatus, lifecycle.publication(current))
                        .setSql("contract_version = contract_version + 1")
                        .eq(Activity::getId, command.getId())
                        .eq(Activity::getPublishStatus, lifecycle.publication(current))
                        .isNull(Activity::getDeletedAt));
        if (updated != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动草稿更新失败");
        }
        saveAdminChildren(command.getId(), command);
        Activity saved = activityMapper.selectForUpdate(command.getId());
        if ("ENDED".equals(originalStage) && !"ENDED".equals(ActivityStagePolicy.state(0,
                timelineService.listTimelinesByTarget(TargetType.ACTIVITY, saved.getId()), LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))))
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("state-conflict", "已自然结束的活动不能通过编辑主时间重开");
        if (lifecycle.publication(saved) == 1) {
            lifecycle.validateAdminSubmission(saved);
            lifecycle.initializeFormVersionIfAbsent(saved);
        }
        publicHistory.remember(saved);
        lifecycle.refreshActivityReminder(saved);
        if (notifyActionableChange) {
            lifecycle.enqueueActivityActionableChanges(saved, previousLocation, previousTimeline,
                    timelineService.listTimelinesByTarget(TargetType.ACTIVITY, saved.getId()));
        }
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(command.getId())));
        managementAudit.operation(java.util.UUID.randomUUID().toString(), command.getOperatorId(), "replaceActivity", "ACTIVITY", String.valueOf(command.getId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        log.info("[管理端活动] 管理员 {} 更新草稿 {}", command.getOperatorId(), command.getId());
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AdminActivityDetailBO replaceAdminActivity(AdminActivitySaveBO command, String ifMatch) {
        if (command == null || command.getId() == null) throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID);
        Activity current = activityMapper.selectForUpdate(command.getId());
        if (current == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        StrongEtag.require(ifMatch, "activity", command.getId(), current.getContractVersion() == null ? 1L : current.getContractVersion());
        updateAdminActivity(command);
        return adminQueries.getAdminActivityDetail(command.getId());
    }

    private Activity toAdminEntity(AdminActivitySaveBO command) {
        return Activity.builder().allowRegistrationAfterStart(Boolean.TRUE.equals(command.getAllowRegistrationAfterStart()))
                .formSchema(command.getFormSchema() == null || command.getFormSchema().isNull() ? null : command.getFormSchema().toString())
                .title(command.getTitle())
                .summary(command.getSummary()).audienceSummary(command.getAudienceSummary())
                .location(command.getLocation())
                .category(command.getCategory() == null ? null : command.getCategory().getCode())
                .organizer(command.getOrganizer())
                .audienceDepartmentIds(command.getAudienceDepartmentIds()).contactsJson(command.getContactsJson())
                .registrationMode(command.getRegistrationMode()).participantMode(command.getParticipantMode())
                .capacity(command.getCapacity()).capacityUnit(command.getCapacityUnit())
                .coverAttachmentId(command.getCoverAttachmentId())
                .build();
    }

    private void saveAdminChildren(Long activityId, AdminActivitySaveBO command) {
        eventContentService.saveSections(TargetType.ACTIVITY, activityId, command.getSections());
        eventContentService.prepareActions(TargetType.ACTIVITY, activityId, command.getActions());

        timelineService.replaceTimelines(TimelineSaveBO.builder()
                .targetType(TargetType.ACTIVITY)
                .targetId(activityId)
                .timelines(command.getTimelineItems())
                .build());
        mediaService.replaceAttachmentLinks(TargetType.ACTIVITY, activityId, command.getAttachmentLinks());
        eventContentService.saveActions(TargetType.ACTIVITY, activityId, command.getActions());
        Long coverId = command.getCoverAttachmentId();
        eventContentService.validateCover(TargetType.ACTIVITY, activityId, coverId);
        Integer capacity = command.getCapacity();
        Integer unit = command.getCapacityUnit();
        if ((capacity == null) != (unit == null))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量与单位必须同时填写");

        int rows = activityMapper.update(null, new LambdaUpdateWrapper<Activity>()
                .set(command.isCanonicalFullReplacement() || command.getAllowRegistrationAfterStart() != null, Activity::getAllowRegistrationAfterStart, Boolean.TRUE.equals(command.getAllowRegistrationAfterStart()))
                .set(command.isCanonicalFullReplacement() || command.getSummary() != null, Activity::getSummary, command.getSummary())
                .set(command.isCanonicalFullReplacement() || command.getAudienceSummary() != null, Activity::getAudienceSummary, command.getAudienceSummary())
                .set(command.isCanonicalFullReplacement() || command.getRegistrationMode() != null, Activity::getRegistrationMode, command.getRegistrationMode())
                .set(command.isCanonicalFullReplacement() || command.getParticipantMode() != null, Activity::getParticipantMode, command.getParticipantMode())
                .set(Activity::getCapacity, capacity).set(Activity::getCapacityUnit, unit)
                .set(Activity::getCoverAttachmentId, coverId)
                .eq(Activity::getId, activityId));
        if (rows != 1) throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动子资源保存失败");
    }

    private void validateAdminSave(AdminActivitySaveBO command) {
        if (command == null || command.getOperatorId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (command.getRegistrationMode() != null && !java.util.Set.of(0, 1, 2, 3, 4).contains(command.getRegistrationMode()))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "报名方式不合法");
        if (command.getFormSchema() != null && !command.getFormSchema().isNull())
            cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.schema(command.getFormSchema());
        if (command.getParticipantMode() != null && !Set.of(1, 2).contains(command.getParticipantMode()))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "参与形式不合法");
        if (command.getCapacity() != null && command.getCapacity() <= 0)
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量必须大于0");
        if (command.getCapacityUnit() != null && command.getCapacityUnit() != 1 && command.getCapacityUnit() != 2)
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量单位不合法");
        if ((command.getCapacity() == null) != (command.getCapacityUnit() == null))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量与单位必须同时填写");
        if (command.getParticipantMode() != null && command.getCapacityUnit() != null
                && !command.getParticipantMode().equals(command.getCapacityUnit()))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "参与形式与容量单位不一致");
        if (Integer.valueOf(2).equals(command.getParticipantMode()) && command.getRegistrationMode() != null
                && Set.of(2, 4).contains(command.getRegistrationMode()))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "平台表单仅支持个人报名");
        for (var item : safeList(command.getTimelineItems())) {
            EventTimePolicy.range(item.getStartTime(), item.getEndTime(), item.getEndPrecision());
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

    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
