package cn.jualn.miniapp.module.exam.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.ExamStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.entity.ExamSubscription;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamSubscriptionMapper;
import cn.jualn.miniapp.module.exam.bo.ExamCreateBO;
import cn.jualn.miniapp.module.exam.bo.AdminPublicEventSaveBO;
import cn.jualn.miniapp.module.exam.bo.AdminPublicEventQueryBO;
import cn.jualn.miniapp.module.exam.bo.AdminPublicEventPageBO;
import cn.jualn.miniapp.module.exam.bo.AdminPublicEventListBO;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.bo.ExamPageBO;
import cn.jualn.miniapp.module.exam.bo.ExamUpdateBO;
import cn.jualn.miniapp.module.exam.converter.ExamConverter;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.exam.service.PublicEventCursorCodec;
import cn.jualn.miniapp.module.exam.vo.ExamDetailVO;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import cn.jualn.miniapp.module.timeline.service.CardTimelinePolicy;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.jualn.miniapp.common.web.StrongEtag;

import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 考试信息业务实现类。
 *
 * <p>负责考试信息的创建、更新、分页查询、详情查询以及计数更新。</p>
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ExamServiceImpl implements ExamService {
    private final cn.jualn.miniapp.module.eventcontent.service.EventContentService eventContentService;

    private static final Integer SUBSCRIBE_STATUS_ACTIVE = 1;
    private static final cn.jualn.miniapp.module.exam.service.PublicEventReminderPolicy REMINDER_POLICY =
            new cn.jualn.miniapp.module.exam.service.PublicEventReminderPolicy();

    private final ExamInfoMapper examInfoMapper;
    private final ExamSubscriptionMapper examSubscriptionMapper;
    private final ExamConverter examConverter;
    private final MediaService mediaService;
    private final TimelineService timelineService;
    private final UserService userService;
    private final RedisService redisService;
    private final InteractService interactService;
    private final ExamSubscriptionService examSubscriptionService;
    private final NotifyService notifyService;
    private final cn.jualn.miniapp.module.eventcontent.service.EventContactCodec eventContactCodec;

    /** 旧用户写入口已停用；事项统一由独立管理端身份维护。 */
    @Override
    public ExamDetailBO getPublicEventResource(Long id) {
        ExamDetailBO detail = getExamDetail(id);
        detail.setContacts(eventContactCodec.read(detail.getContactsJson(), null, null, null));
        return detail;
    }

    @Override
    public boolean isPubliclyVisible(Long id) {
        return id != null && examInfoMapper.existsPublicById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createExam(ExamCreateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "公共事项由运维管理，请使用管理端接口");
    }


    /** 旧用户写入口已停用；事项统一由独立管理端身份维护。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateExam(ExamUpdateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "公共事项由运维管理，请使用管理端接口");
    }

    /** 旧用户写入口已停用；事项统一由独立管理端身份维护。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeExam(Long id) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "公共事项由运维管理，请使用管理端接口");
    }

    /**
     * 获取考试详情。
     *
     * @param id 考试ID
     * @return 考试详情
     * @throws BusinessException 考试不存在或无权限时抛出异常
     */
    @Override
    public ExamDetailBO getExamDetail(Long id) {
        ExamInfo exam = requireVisibleExam(id);
        ExamDetailBO detail = loadPublicEventDetail(exam);
        Long userId = UserContext.getUserId();
        detail.setLiked(userId != null && interactService.isLiked(TargetType.EXAM, id));
        detail.setSubscribed(userId != null && examSubscriptionService.isSubscribed(id));
        return detail;
    }

    /**
     * 考试分页列表（游标分页）。
     *
     * @param command 查询业务对象
     * @return 分页结果
     */
    @Override
    public PageResult<ExamDetailBO> pageExam(ExamPageBO command) {
        int pageSize = command.getPageSize() == null ? 20 : command.getPageSize();
        Integer status = command.getStatus();
        if (status == null || !Objects.equals(status, ExamStatus.PUBLISHED.getCode())) {
            status = ExamStatus.PUBLISHED.getCode();
        }

        String keyword = command.getKeyword();
        if (keyword != null && keyword.isBlank()) {
            keyword = null;
        }

        // 多取一条用于判断是否还有下一页。
        List<ExamInfo> examInfos = examInfoMapper.selectPageExams(
                status,
                command.getCategory(),
                command.getEventType(),
                command.getLifecycleStatus(),
                keyword,
                command.getLastId(),
                pageSize + 1
        );
        boolean hasMore = examInfos.size() > pageSize;
        if (hasMore) {
            examInfos = examInfos.subList(0, pageSize);
        }

        List<ExamDetailBO> list = examConverter.toDetailList(examInfos);
        Map<Long, List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO>> timelines =
                timelineService.listTimelinesByTargets(TargetType.EXAM, list.stream().map(ExamDetailBO::getId).toList());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        for (ExamDetailBO item : list) {
            item.setCardTimeline(CardTimelinePolicy.select(
                    timelines.getOrDefault(item.getId(), List.of()), now));
        }
        return PageResult.of(list, hasMore, list.isEmpty() ? null : list.get(list.size() - 1).getId());
    }

    /**
     * 增加考试评论数。
     *
     * @param examId 考试信息ID
     */
    @Override
    public void increaseCommentCount(Long examId) {
        examInfoMapper.increaseCommentCount(examId);
    }

    /**
     * 减少考试评论数（最低为0）。
     *
     * @param examId 考试信息ID
     */
    @Override
    public void decreaseCommentCount(Long examId) {
        examInfoMapper.decreaseCommentCount(examId);
    }



    private ExamInfo requireExam(Long examId) {
        ExamInfo examInfo = examInfoMapper.selectByIdNotDeleted(examId);
        if (examInfo == null) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }
        return examInfo;
    }

    private ExamInfo requireVisibleExam(Long examId) {
        ExamInfo examInfo = requireExam(examId);
        if (!Integer.valueOf(1).equals(examInfo.getPublishStatus())) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }
        return examInfo;
    }



    private UserSimpleBO resolveAuthor(Long userId) {
        return userService.getSimpleInfo(userId);
    }

    private void enrichUserState(ExamDetailVO detailVO, Long examId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            detailVO.setSubscribed(Boolean.FALSE);
            detailVO.setLiked(Boolean.FALSE);
            return;
        }
        detailVO.setLiked(interactService.isLiked(TargetType.EXAM, examId));
        detailVO.setSubscribed(examSubscriptionService.isSubscribed(examId));
    }

    private void enrichUserState(List<ExamDetailBO> detailBOList) {
        if (detailBOList == null) {
            return;
        }
        Long userId = UserContext.getUserId();
        if (CollectionUtils.isEmpty(detailBOList)) {
            return;
        }
        if (userId == null) {
            for (ExamDetailBO detailBO : detailBOList) {
//                detailBO.setSubscribed(Boolean.FALSE);
            }
            return;
        }

        Set<Long> examIds = detailBOList.stream()
                .map(ExamDetailBO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (examIds.isEmpty()) {
            return;
        }

        // 一次查询批量获取订阅记录，避免N+1查询。
        List<ExamSubscription> subscriptions = examSubscriptionMapper.selectActiveByUserAndExamIds(
                userId, new ArrayList<>(examIds), SUBSCRIBE_STATUS_ACTIVE);
        Map<Long, ExamSubscription> subscriptionMap = subscriptions.stream()
                .collect(Collectors.toMap(ExamSubscription::getExamInfoId, Function.identity(), (left, right) -> left));
        for (ExamDetailBO detailBO : detailBOList) {
            ExamSubscription subscription = subscriptionMap.get(detailBO.getId());
//            detailBO.setSubscribed(subscription != null);
//            detailBO.setNotifyEnable(subscription == null ? null : subscription.getNotifyEnable());
        }
    }
    @Override
    public AdminPublicEventPageBO pageAdminPublicEvents(AdminPublicEventQueryBO query) {
        int page = query.getPage() == null ? 1 : query.getPage();
        int size = query.getPageSize() == null ? 20 : query.getPageSize();
        if (page < 1 || size < 1 || size > 100 || !"-updatedAt".equals(query.getSort()))
            throw new BusinessException(ResultCode.BAD_REQUEST);
        long offset = Math.multiplyExact((long) page - 1, size);
        List<AdminPublicEventListBO> items = examInfoMapper.selectOperationsPage(query, offset, size);
        return AdminPublicEventPageBO.builder().items(items).page(page).pageSize(size)
                .totalItems(examInfoMapper.countOperationsPage(query)).build();
    }

    @Override
    public ExamDetailBO getAdminPublicEvent(Long id) { return loadPublicEventDetail(requireExam(id)); }

    private ExamDetailBO loadPublicEventDetail(ExamInfo exam) {
        ExamDetailBO detail = examConverter.toDetailBO(exam);
        detail.setAttachmentItems(mediaService.listAttachments(TargetType.EXAM, exam.getId()));
        detail.setTimelineItems(timelineService.listTimelinesByTarget(TargetType.EXAM, exam.getId()));
        detail.setCardTimeline(CardTimelinePolicy.select(detail.getTimelineItems(),
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))));
        detail.setSections(eventContentService.sections(TargetType.EXAM, exam.getId()));
        detail.setActions(eventContentService.actions(TargetType.EXAM, exam.getId()));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExamDetailBO createAdminPublicEvent(AdminPublicEventSaveBO command) {
        validateOperationsSave(command);
        ExamInfo exam = examConverter.toOperationsEntity(command);
        exam.setUserId(command.getOperatorId());
        exam.setPublishStatus(0);
        exam.setLifecycleStatus(0);
        if (examInfoMapper.insert(exam) != 1) throw operationDenied("公共事项创建失败");
        saveOperationsChildren(exam, command);
        return loadPublicEventDetail(examInfoMapper.selectForUpdate(exam.getId()));
    }

    @Override
    public cn.jualn.miniapp.module.exam.bo.PublicEventResourcePageBO pagePublicEventResources(ExamPageBO query) {
        query.setLastId(PublicEventCursorCodec.decode(query));
        PageResult<ExamDetailBO> page = pageExam(query);
        String nextCursor = Boolean.TRUE.equals(page.getHasMore())
                ? PublicEventCursorCodec.encode(query, page.getNextCursor()) : null;
        return new cn.jualn.miniapp.module.exam.bo.PublicEventResourcePageBO(page.getList(), nextCursor);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExamDetailBO replaceAdminPublicEvent(AdminPublicEventSaveBO command, String ifMatch) {
        validateOperationsSave(command);
        ExamInfo current = lockedEvent(command.getId());
        boolean notifyActionableChange = Integer.valueOf(1).equals(current.getPublishStatus())
                && Integer.valueOf(0).equals(current.getLifecycleStatus());
        List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> previousTimeline = notifyActionableChange
                ? timelineService.listTimelinesByTarget(TargetType.EXAM, current.getId()) : List.of();
        long version = current.getContractVersion() == null ? 1L : current.getContractVersion();
        StrongEtag.require(ifMatch, "public-event", current.getId(), version);
        ExamInfo exam = examConverter.toOperationsEntity(command);
        exam.setId(current.getId());
        exam.setPublishStatus(current.getPublishStatus());
        exam.setLifecycleStatus(current.getLifecycleStatus());
        saveOperationsChildren(exam, command);
        if (Integer.valueOf(1).equals(exam.getPublishStatus())) validatePublish(exam);
        ExamInfo saved = examInfoMapper.selectForUpdate(exam.getId());
        refreshEventReminder(saved);
        if (notifyActionableChange) {
            enqueuePublicEventActionableChanges(saved, previousTimeline,
                    timelineService.listTimelinesByTarget(TargetType.EXAM, saved.getId()));
        }
        invalidateEventAfterCommit(exam.getId());
        return loadPublicEventDetail(saved);
    }

    private void saveOperationsChildren(ExamInfo exam, AdminPublicEventSaveBO command) {
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

    private void validatePublish(ExamInfo exam) {
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

    private cn.jualn.miniapp.common.exception.ContractProblemException.Violation publishError(
            String pointer, String detail) {
        return new cn.jualn.miniapp.common.exception.ContractProblemException.Violation(
                "body", pointer, "REQUIRED", detail);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExamDetailBO transitionAdminPublicEvent(Long id, Long operatorId, String ifMatch, String action) {
        requireOperator(operatorId);
        ExamInfo current = lockedEvent(id);
        long version = current.getContractVersion() == null ? 1L : current.getContractVersion();
        StrongEtag.require(ifMatch, "public-event", id, version);
        int publication = current.getPublishStatus() == null ? 0 : current.getPublishStatus();
        int lifecycle = current.getLifecycleStatus() == null ? 0 : current.getLifecycleStatus();
        int nextPublication = publication; int nextLifecycle = lifecycle;
        boolean publishing = false;
        switch (action) {
            case "publish" -> {
                if (publication == 1) return loadPublicEventDetail(current);
                validatePublish(current);
                nextPublication = 1;
                publishing = true;
            }
            case "unpublish" -> {
                if (publication == 2) return loadPublicEventDetail(current);
                if (publication != 1)
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                            "state-conflict", "Only published content can be unpublished");
                nextPublication = 2;
            }
            case "cancel" -> {
                if (lifecycle == 2) return loadPublicEventDetail(current);
                if (publication != 1 || lifecycle != 0)
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                            "state-conflict", "Only a published active public event can be cancelled");
                nextPublication = 1;
                nextLifecycle = 2;
            }
            case "end" -> {
                if (lifecycle == 1) return loadPublicEventDetail(current);
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
        ExamInfo changed=examInfoMapper.selectForUpdate(id);
        if(publishing&&nextLifecycle==0)refreshEventReminder(changed);
        if ("cancel".equals(action)) {
            notifyService.enqueueBusinessNotification(TargetType.EXAM, id,
                    "public-event:" + id + ":cancel:v" + changed.getContractVersion(),
                    cn.jualn.miniapp.common.enums.NotifyType.PUBLIC_EVENT_CANCELLED, "SUBSCRIBERS",
                    "公共事项已取消", "公共事项「" + changed.getTitle() + "」已取消",
                    subjectSnapshot(changed, "公共事项已取消", "公共事项「" + changed.getTitle() + "」已取消"));
        }
        invalidateEventAfterCommit(id);
        return loadPublicEventDetail(changed);
    }

    private ExamInfo lockedEvent(Long id) {
        if (id == null) throw new BusinessException(ResultCode.BAD_REQUEST);
        ExamInfo exam = examInfoMapper.selectForUpdate(id);
        if (exam == null) throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        return exam;
    }
    private void requireOperator(Long id) {
        if (id == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
    }
    private BusinessException operationDenied(String message) { return new BusinessException(ResultCode.INVALID_OPERATION, message); }
    private void refreshEventReminder(ExamInfo exam) {
        LocalDateTime now = LocalDateTime.now();
        List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> timeline =
                timelineService.listTimelinesByTarget(TargetType.EXAM, exam.getId());
        notifyService.reconcileReminders(TargetType.EXAM, exam.getId(),
                exam.getContractVersion() == null ? 0L : exam.getContractVersion(),
                REMINDER_POLICY.evaluate(exam, timeline, now), now);
    }
    private void enqueuePublicEventActionableChanges(ExamInfo saved,
            List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> previousTimeline,
            List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> currentTimeline) {
        long version = saved.getContractVersion() == null ? 1L : saved.getContractVersion();
        java.util.Set<String> semantics = java.util.Set.of("PUBLIC_EVENT_START", "REGISTRATION_END");
        if (cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.exactTimeChanged(
                previousTimeline, currentTimeline, semantics)) {
            notifyService.enqueueBusinessNotification(TargetType.EXAM, saved.getId(),
                    "public-event:" + saved.getId() + ":time:v" + version,
                    cn.jualn.miniapp.common.enums.NotifyType.PUBLIC_EVENT_TIME_CHANGED, "SUBSCRIBERS",
                    "公共事项时间已变更", "公共事项「" + saved.getTitle() + "」的行动时间已变更",
                    changeSnapshot(saved, "公共事项时间已变更", cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector
                            .timeChanges(previousTimeline, currentTimeline, semantics)));
        }
        if (cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.effectiveLocationChanged(
                previousTimeline, currentTimeline, semantics)) {
            notifyService.enqueueBusinessNotification(TargetType.EXAM, saved.getId(),
                    "public-event:" + saved.getId() + ":location:v" + version,
                    cn.jualn.miniapp.common.enums.NotifyType.PUBLIC_EVENT_LOCATION_CHANGED, "SUBSCRIBERS",
                    "公共事项地点已变更", "公共事项「" + saved.getTitle() + "」的行动地点已变更",
                    changeSnapshot(saved, "公共事项地点已变更", cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector
                            .locationChanges(previousTimeline, currentTimeline, semantics)));
        }
    }

    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot subjectSnapshot(ExamInfo event, String title, String body) {
        String id = event.getId().toString();
        return new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot(
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation(title, body, null, null, null, event.getTitle(), null),
                null, new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject("PUBLIC_EVENT", id),
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("PUBLIC_EVENT_DETAIL", null, null, null, id));
    }

    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot changeSnapshot(ExamInfo event, String title,
            List<cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.Change> changes) {
        String id = event.getId().toString();
        return new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot(
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation(title, null, event.getTitle(), null,
                        changes.stream().map(change -> new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Change(
                                change.label(), change.before(), change.after())).toList(), event.getTitle(), null), null,
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject("PUBLIC_EVENT", id),
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("PUBLIC_EVENT_DETAIL", null, null, null, id));
    }
    private void invalidateEventAfterCommit(Long id) {
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

}
