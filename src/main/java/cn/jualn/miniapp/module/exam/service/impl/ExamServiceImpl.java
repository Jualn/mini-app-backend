package cn.jualn.miniapp.module.exam.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.eventcontent.service.EventTimePolicy;
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
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.bo.ExamPageBO;
import cn.jualn.miniapp.module.exam.bo.ExamSimpleBO;
import cn.jualn.miniapp.module.exam.bo.ExamUpdateBO;
import cn.jualn.miniapp.module.exam.converter.ExamConverter;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.exam.vo.ExamDetailVO;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 旧用户写入口已停用；事项统一由独立管理端身份维护。 */
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

    @Override
    public List<ExamSimpleBO> getExamSimple() {
        return examInfoMapper.selectSimpleExams();
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
                keyword,
                command.getLastId(),
                pageSize + 1
        );
        boolean hasMore = examInfos.size() > pageSize;
        if (hasMore) {
            examInfos = examInfos.subList(0, pageSize);
        }

        List<ExamDetailBO> list = examConverter.toDetailList(examInfos);
        list.forEach(this::deriveEventState);

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
        int size = query.getPageSize() == null ? 20 : query.getPageSize();
        if (size < 1 || size > 100) throw new BusinessException(ResultCode.BAD_REQUEST);
        Long lastId = AdminIdCursorCodec.decode(query.getCursor(), "public-event-id-desc");
        List<AdminPublicEventListBO> rows = examInfoMapper.selectOperationsPage(query, lastId, size + 1);
        boolean more = rows.size() > size;
        List<AdminPublicEventListBO> items = more ? rows.subList(0, size) : rows;
        return AdminPublicEventPageBO.builder().items(items).hasMore(more).pageSize(size)
                .nextCursor(more ? AdminIdCursorCodec.encode("public-event-id-desc", items.get(items.size()-1).getId()) : null).build();
    }

    @Override
    public ExamDetailBO getAdminPublicEvent(Long id) { return loadPublicEventDetail(requireExam(id)); }

    private ExamDetailBO loadPublicEventDetail(ExamInfo exam) {
        ExamDetailBO detail = examConverter.toDetailBO(exam);
        detail.setAuthor(resolveAuthor(exam.getUserId()));
        detail.setAttachmentItems(mediaService.listAttachments(TargetType.EXAM, exam.getId()));
        detail.setTimelineItems(timelineService.listTimelinesByTarget(TargetType.EXAM, exam.getId()));
        detail.setSections(eventContentService.sections(TargetType.EXAM, exam.getId(), exam.getContent()));
        detail.setActions(eventContentService.actions(TargetType.EXAM, exam.getId()));
        deriveEventState(detail);
        return detail;
    }

    private void deriveEventState(ExamDetailBO detail) {
        int publication = detail.getPublishStatus() == null ? 0 : detail.getPublishStatus();
        LocalDateTime now = LocalDateTime.now();
        detail.setActivityPhase(EventTimePolicy.phase(publication, detail.getStartTime(), detail.getStartPrecision(),
                detail.getEndTime(), detail.getEndPrecision(), now));
        detail.setRegistrationStatus(EventTimePolicy.registration(publication, detail.getRegistrationMode(),
                detail.getRegistrationStart(), detail.getRegistrationStartPrecision(),
                detail.getRegistrationEnd(), detail.getRegistrationEndPrecision(), now));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExamDetailBO createAdminPublicEvent(AdminPublicEventSaveBO command) {
        validateOperationsSave(command);
        ExamInfo exam = examConverter.toOperationsEntity(command);
        normalizeSchedule(exam);
        exam.setUserId(command.getOperatorId()); // admin login id is the existing user_profile id.
        exam.setPublishStatus(0);
        exam.setStatus(0);
        exam.setAuditStatus(0);
        if (exam.getContent() == null) exam.setContent("");
        if (examInfoMapper.insert(exam) != 1) throw operationDenied("公共事项创建失败");
        saveOperationsChildren(exam, command);
        return loadPublicEventDetail(examInfoMapper.selectForUpdate(exam.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExamDetailBO updateAdminPublicEvent(AdminPublicEventSaveBO command) {
        validateOperationsSave(command);
        ExamInfo current = lockedEvent(command.getId());
        if (Integer.valueOf(3).equals(current.getPublishStatus())) throw operationDenied("已取消事项不可编辑");
        ExamInfo exam = examConverter.toOperationsEntity(command);
        exam.setId(current.getId());
        exam.setCoverAttachmentId(current.getCoverAttachmentId());
        exam.setContent(command.getContent() == null ? current.getContent() : command.getContent());
        exam.setPublishStatus(current.getPublishStatus());
        normalizeSchedule(exam);
        saveOperationsChildren(exam, command);
        if (Integer.valueOf(1).equals(exam.getPublishStatus())) validatePublish(exam);
        refreshEventReminder(exam);
        invalidateEventAfterCommit(exam.getId());
        return loadPublicEventDetail(examInfoMapper.selectForUpdate(exam.getId()));
    }

    private void saveOperationsChildren(ExamInfo exam, AdminPublicEventSaveBO command) {
        Long id = exam.getId();
        exam.setContent(eventContentService.saveSections(TargetType.EXAM, id, command.getSections(), command.getContent() == null ? exam.getContent() : command.getContent()));
        eventContentService.prepareActions(TargetType.EXAM, id, command.getActions());
        if (command.getAttachments() != null) mediaService.replaceAttachments(MediaAttachmentSaveBO.builder()
                .targetType(TargetType.EXAM).targetId(id).attachments(command.getAttachments()).build());
        if (command.getTimeline() != null) timelineService.replaceTimelines(TimelineSaveBO.builder()
                .targetType(TargetType.EXAM).targetId(id).timelines(command.getTimeline()).build());
        eventContentService.saveActions(TargetType.EXAM, id, command.getActions());
        Long cover = command.getCoverAttachmentId();
        if (Boolean.TRUE.equals(command.getClearCover()) && (cover != null || org.springframework.util.StringUtils.hasText(command.getCoverObjectKey())))
            throw operationDenied("清除封面与设置封面不能同时提交");
        List<cn.jualn.miniapp.module.media.bo.MediaAttachmentBO> attachments = mediaService.listAttachments(TargetType.EXAM, id);
        if (org.springframework.util.StringUtils.hasText(command.getCoverObjectKey())) {
            Long resolved = attachments.stream().filter(a -> command.getCoverObjectKey().equals(a.getObjectKey()))
                    .map(cn.jualn.miniapp.module.media.bo.MediaAttachmentBO::getId).findFirst()
                    .orElseThrow(() -> operationDenied("封面对象不属于当前事项"));
            if (cover != null && !cover.equals(resolved)) throw operationDenied("封面ID与对象键不一致");
            cover = resolved;
        }
        eventContentService.validateCover(TargetType.EXAM, id, cover);
        if (Boolean.TRUE.equals(command.getClearCover())) exam.setCoverAttachmentId(null);
        else if (cover != null) exam.setCoverAttachmentId(cover);
        else if (attachments.stream().noneMatch(a -> a.getId().equals(exam.getCoverAttachmentId())))
            exam.setCoverAttachmentId(null); // Deleted cover follows the FK SET NULL semantics.
        if (examInfoMapper.saveOperationsFields(exam) != 1) throw operationDenied("公共事项保存失败");
    }

    private void validateOperationsSave(AdminPublicEventSaveBO command) {
        if (command == null || command.getOperatorId() == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        if (command.getRegistrationMode() == null || !Set.of(0, 1, 3).contains(command.getRegistrationMode()))
            throw operationDenied("仅支持未确认、无需报名或外部报名");
        if (command.getParticipantMode() == null || command.getParticipantMode() < 0 || command.getParticipantMode() > 3)
            throw operationDenied("参与形式不合法");
        Integer scope = command.getAudienceScope();
        if (scope == null || scope < 0 || scope > 63 || ((scope & 1) != 0 && scope != 1))
            throw operationDenied("参与范围不合法");
        if ((command.getCapacity() == null) != (command.getCapacityUnit() == null)
                || (command.getCapacity() != null && (command.getCapacity() <= 0 || !Set.of(1, 2).contains(command.getCapacityUnit()))))
            throw operationDenied("官方容量与人/队单位必须同时填写");
    }

    private void validatePublish(ExamInfo exam) {
        if (exam.getTitle() == null || exam.getTitle().isBlank() || exam.getContent() == null || exam.getContent().isBlank()
                || exam.getRegistrationMode() == null || !Set.of(1, 3).contains(exam.getRegistrationMode()))
            throw operationDenied("发布前请填写正文并确认报名方式");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishAdminPublicEvent(Long id, Long operatorId) {
        requireOperator(operatorId);
        ExamInfo exam = lockedEvent(id);
        if (Integer.valueOf(1).equals(exam.getPublishStatus())) return;
        if (Integer.valueOf(3).equals(exam.getPublishStatus())) throw operationDenied("已取消事项不能重新发布");
        validatePublish(exam);
        if (examInfoMapper.publishDirectly(id) != 1) throw operationDenied("公共事项发布失败");
        exam.setPublishStatus(1);
        refreshEventReminder(exam);
        invalidateEventAfterCommit(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void takeDownAdminPublicEvent(Long id, Long operatorId, String reason) {
        requireReason(operatorId, reason);
        ExamInfo exam = lockedEvent(id);
        if (Integer.valueOf(2).equals(exam.getPublishStatus())) return;
        if (!Integer.valueOf(1).equals(exam.getPublishStatus()) || examInfoMapper.takeDownDirectly(id) != 1)
            throw operationDenied("只有已发布事项可以下架");
        notifyService.replaceEventReminder(TargetType.EXAM, id, exam.getTitle(), null);
        invalidateEventAfterCommit(id);
        log.info("[公共事项下架] id={}, operatorId={}, reason={}", id, operatorId, reason.trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAdminPublicEvent(Long id, Long operatorId, String reason) {
        requireReason(operatorId, reason);
        ExamInfo exam = lockedEvent(id);
        if (Integer.valueOf(3).equals(exam.getPublishStatus())) return;
        if (!Integer.valueOf(1).equals(exam.getPublishStatus()) || examInfoMapper.cancelDirectly(id, reason.trim()) != 1)
            throw operationDenied("只有已发布事项可以取消");
        notifyService.replaceEventReminder(TargetType.EXAM, id, exam.getTitle(), null);
        invalidateEventAfterCommit(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeAdminPublicEvent(Long id, Long operatorId, String reason) {
        requireReason(operatorId, reason);
        ExamInfo exam = lockedEvent(id);
        if (Integer.valueOf(1).equals(exam.getPublishStatus())) throw operationDenied("已发布事项请先下架");
        if (examInfoMapper.removeDirectly(id) != 1) throw operationDenied("公共事项删除失败");
        notifyService.replaceEventReminder(TargetType.EXAM, id, exam.getTitle(), null);
        invalidateEventAfterCommit(id);
        log.info("[公共事项删除] id={}, operatorId={}, reason={}", id, operatorId, reason.trim());
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
    private void requireReason(Long id, String reason) {
        requireOperator(id);
        if (reason == null || reason.isBlank() || reason.length() > 255) throw operationDenied("请填写255字内原因");
    }
    private BusinessException operationDenied(String message) { return new BusinessException(ResultCode.INVALID_OPERATION, message); }
    private void refreshEventReminder(ExamInfo exam) {
        LocalDateTime sendAt = Integer.valueOf(1).equals(exam.getPublishStatus())
                && Integer.valueOf(3).equals(exam.getRegistrationMode())
                && Integer.valueOf(2).equals(exam.getRegistrationStartPrecision()) && exam.getRegistrationStart() != null
                ? exam.getRegistrationStart().minusHours(1) : null;
        notifyService.replaceEventReminder(TargetType.EXAM, exam.getId(), exam.getTitle(), sendAt);
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

    private void normalizeSchedule(ExamInfo exam) {
        if (exam.getStartTime() == null && exam.getExamDate() != null) {
            exam.setStartTime(exam.getExamDate().atStartOfDay());
            exam.setStartPrecision(1);
        }
        if (exam.getEndTime() == null && exam.getExamDateEnd() != null) {
            exam.setEndTime(exam.getExamDateEnd().atStartOfDay());
            exam.setEndPrecision(1);
        }
        if (exam.getEventType() != null && (exam.getEventType() < 0 || exam.getEventType() > 3))
            throw new BusinessException(ResultCode.INVALID_OPERATION, "事项类型不合法");
        if (exam.getTimeDescription() != null && exam.getTimeDescription().length() > 255)
            throw new BusinessException(ResultCode.INVALID_OPERATION, "时间说明过长");
        exam.setStartPrecision(EventTimePolicy.precision(exam.getStartTime(), exam.getStartPrecision()));
        exam.setEndPrecision(EventTimePolicy.precision(exam.getEndTime(), exam.getEndPrecision()));
        exam.setRegistrationStartPrecision(EventTimePolicy.precision(exam.getRegistrationStart(), exam.getRegistrationStartPrecision()));
        exam.setRegistrationEndPrecision(EventTimePolicy.precision(exam.getRegistrationEnd(), exam.getRegistrationEndPrecision()));
        EventTimePolicy.range(exam.getStartTime(), exam.getEndTime(), exam.getEndPrecision());
        EventTimePolicy.range(exam.getRegistrationStart(), exam.getRegistrationEnd(), exam.getRegistrationEndPrecision());
        exam.setExamDate(exam.getStartTime() == null ? null : exam.getStartTime().toLocalDate());
        exam.setExamDateEnd(exam.getEndTime() == null ? null : exam.getEndTime().toLocalDate());
    }
}
