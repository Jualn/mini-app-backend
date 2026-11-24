package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.bo.AdminPublicEventSaveBO;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventDetailBO;
import cn.jualn.miniapp.module.publicevent.converter.PublicEventConverter;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.jualn.miniapp.common.web.StrongEtag;

import java.time.LocalDateTime;
import java.util.List;

/** 公共事项管理草稿和条件替换保存。 */
@Service
@RequiredArgsConstructor
public class PublicEventDraftService {
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    private final cn.jualn.miniapp.module.eventcontent.service.EventContentService eventContentService;
    private final PublicEventMapper examInfoMapper;
    private final PublicEventConverter examConverter;
    private final MediaService mediaService;
    private final TimelineService timelineService;
    private final PublicEventLifecycleService lifecycle;
    private final PublicEventQueries queries;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PublicEventDetailBO createAdminPublicEvent(AdminPublicEventSaveBO command) {
        authorization.require(command.getOperatorId(),"public-event:edit","PUBLIC_EVENT","public-event:edit");

        validateOperationsSave(command);
        PublicEvent exam = examConverter.toOperationsEntity(command);
        exam.setUserId(command.getOperatorId());
        exam.setPublishStatus(0);
        exam.setLifecycleStatus(0);
        if (examInfoMapper.insert(exam) != 1) throw operationDenied("公共事项创建失败");
        saveOperationsChildren(exam, command);
        managementAudit.operation(java.util.UUID.randomUUID().toString(),command.getOperatorId(),"createPublicEvent","PUBLIC_EVENT",String.valueOf(exam.getId()),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return queries.loadPublicEventDetail(examInfoMapper.selectForUpdate(exam.getId()));
    }

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PublicEventDetailBO replaceAdminPublicEvent(AdminPublicEventSaveBO command, String ifMatch) {
        authorization.require(command.getOperatorId(),"public-event:edit","PUBLIC_EVENT","public-event:edit");

        validateOperationsSave(command);
        PublicEvent current = lockedEvent(command.getId());
        if(Integer.valueOf(1).equals(current.getPublishStatus())) authorization.require(command.getOperatorId(),"public-event:edit","PUBLIC_EVENT","public-event:publish");
        boolean notifyActionableChange = Integer.valueOf(1).equals(current.getPublishStatus())
                && Integer.valueOf(0).equals(current.getLifecycleStatus());
        List<cn.jualn.miniapp.module.timeline.bo.TimelineFactBO> previousTimeline = notifyActionableChange
                ? timelineService.listTimelinesByTarget(TargetType.EXAM, current.getId()) : List.of();
        long version = current.getContractVersion() == null ? 1L : current.getContractVersion();
        StrongEtag.require(ifMatch, "public-event", current.getId(), version);
        PublicEvent exam = examConverter.toOperationsEntity(command);
        exam.setId(current.getId());
        exam.setPublishStatus(current.getPublishStatus());
        exam.setLifecycleStatus(current.getLifecycleStatus());
        saveOperationsChildren(exam, command);
        if (Integer.valueOf(1).equals(exam.getPublishStatus())) lifecycle.validatePublish(exam);
        PublicEvent saved = examInfoMapper.selectForUpdate(exam.getId());
        lifecycle.refreshEventReminder(saved);
        if (notifyActionableChange) {
            lifecycle.enqueuePublicEventActionableChanges(saved, previousTimeline,
                    timelineService.listTimelinesByTarget(TargetType.EXAM, saved.getId()));
        }
        lifecycle.invalidateEventAfterCommit(exam.getId());
        managementAudit.operation(java.util.UUID.randomUUID().toString(),command.getOperatorId(),"replacePublicEvent","PUBLIC_EVENT",String.valueOf(saved.getId()),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return queries.loadPublicEventDetail(saved);
    }

    private void saveOperationsChildren(PublicEvent exam, AdminPublicEventSaveBO command) {
        Long id = exam.getId();
        eventContentService.saveSections(TargetType.EXAM, id, command.getSections());
        eventContentService.prepareActions(TargetType.EXAM, id, command.getActions());
        timelineService.replaceTimelines(TimelineSaveBO.builder()
                .targetType(TargetType.EXAM).targetId(id).timelines(command.getTimeline()).build());
        mediaService.replaceAttachmentLinks(TargetType.EXAM, id, command.getAttachmentLinks());
        eventContentService.saveActions(TargetType.EXAM, id, command.getActions());
        eventContentService.validateCover(TargetType.EXAM, id, command.getCoverAttachmentId());
        exam.setCoverAttachmentId(command.getCoverAttachmentId());
        if (examInfoMapper.saveOperationsFields(exam) != 1) throw operationDenied("公共事项保存失败");
    }

    private void validateOperationsSave(AdminPublicEventSaveBO command) {
        if (command == null || command.getOperatorId() == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        if (!command.isCanonicalFullReplacement() || command.getTitle() == null
                || command.getTimeline() == null || command.getSections() == null || command.getActions() == null
                || command.getAttachmentLinks() == null || command.getContactsJson() == null)
            throw new BusinessException(ResultCode.BAD_REQUEST, "PublicEventDraft不完整");
    }

    private BusinessException operationDenied(String message) { return new BusinessException(ResultCode.INVALID_OPERATION, message); }

    private PublicEvent lockedEvent(Long id) {
        if (id == null) throw new BusinessException(ResultCode.BAD_REQUEST);
        PublicEvent exam = examInfoMapper.selectForUpdate(id);
        if (exam == null) throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        return exam;
    }
}
