package cn.jualn.miniapp.module.exam.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.ExamStatus;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.entity.ExamSubscription;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamSubscriptionMapper;
import cn.jualn.miniapp.module.exam.bo.ExamCreateBO;
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
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
    private final NotifyPlanMapper notifyPlanMapper;
    private final NotifyService notifyService;

    /**
     * 创建考试信息。
     *
     * @param command 创建业务对象
     * @return 新创建的考试信息ID
     * @throws BusinessException 用户未登录时抛出 {@link ResultCode#UNAUTHORIZED}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createExam(ExamCreateBO command) {
        Long userId = requireUserId();
        userService.assertContentCreationAllowed(userId);

        ExamInfo examInfo = examConverter.toEntity(command);
        examInfo.setUserId(userId);

        examInfoMapper.insert(examInfo);

        if (!CollectionUtils.isEmpty(command.getAttachmentItems())) {
            mediaService.replaceAttachments(
                    MediaAttachmentSaveBO.builder()
                            .targetId(examInfo.getId())
                            .targetType(TargetType.EXAM)
                            .attachments(command.getAttachmentItems())
                            .build()
            );
        }

        if (!CollectionUtils.isEmpty(command.getTimelineItems())) {
            timelineService.replaceTimelines(
                    TimelineSaveBO.builder()
                            .targetType(TargetType.EXAM)
                            .targetId(examInfo.getId())
                            .timelines(command.getTimelineItems())
                            .build()
            );
        }

        // 创建考试报名提醒的延迟通知计划
        createExamRemindPlan(examInfo);

        log.info("[考试] 用户 {} 创建考试信息 {}", userId, examInfo.getId());
        return examInfo.getId();
    }

    private void createExamRemindPlan(ExamInfo exam) {
        try {
            if (exam.getRegistrationStart() == null) return;

            // 报名开始前1小时发送提醒
            LocalDateTime remindAt = exam.getRegistrationStart().minusHours(1);
            if (remindAt.isBefore(LocalDateTime.now())) return;

            NotifyPlan plan = NotifyPlan.builder()
                    .sourceType(2) // 2=考试
                    .sourceId(exam.getId())
                    .notifyType(NotifyType.EXAM_REMIND.getCode())
                    .title("考试报名即将开始")
                    .content("你关注的考试「" + exam.getTitle() + "」即将开始报名")
                    .scope(0)
                    .scene("报名开始前1小时")
                    .sendAt(remindAt)
                    .status(0)
                    .build();
            notifyPlanMapper.insert(plan);
            notifyService.enqueueNotifyPlan(plan.getId());
            log.info("[Exam] 考试提醒计划已创建，examId={}, planId={}, remindAt={}",
                    exam.getId(), plan.getId(), remindAt);
        } catch (Exception e) {
            log.warn("[Exam] 创建考试提醒计划失败，examId={}", exam.getId(), e);
        }
    }

    /**
     * 更新考试信息。
     *
     * @param command 更新业务对象
     * @throws BusinessException 用户未登录、考试不存在或无权限时抛出异常
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateExam(ExamUpdateBO command) {
        Long userId = requireUserId();
        userService.assertContentCreationAllowed(userId);
        ExamInfo examInfo = requireExam(command.getId());
        assertOwner(userId, "无权编辑此考试信息");

        examConverter.updateEntityFromUpdateBO(examInfo, command);

        examInfoMapper.updateById(examInfo);

        if (command.getAttachmentItems() != null) {
            mediaService.replaceAttachments(
                    MediaAttachmentSaveBO.builder()
                            .targetType(TargetType.EXAM)
                            .targetId(examInfo.getId())
                            .attachments(command.getAttachmentItems())
                            .build()
            );
        }

        if (command.getTimelineItems() != null) {
            timelineService.replaceTimelines(
                    TimelineSaveBO.builder()
                            .targetType(TargetType.EXAM)
                            .targetId(examInfo.getId())
                            .timelines(command.getTimelineItems())
                            .build()
            );
        }

        log.info("[考试] 用户 {} 更新考试信息 {}", userId, examInfo.getId());
    }

    /**
     * 删除考试信息（软删除）。
     *
     * @param id 考试ID
     * @throws BusinessException 用户未登录、考试不存在或无权限时抛出异常
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeExam(Long id) {
        Long userId = requireUserId();
        String cacheKey = RedisKeyConstant.examDetail(id);

        assertOwner(userId, "无权删除此考试信息");

        examInfoMapper.update(
                new LambdaUpdateWrapper<ExamInfo>()
                        .set(ExamInfo::getStatus, ExamStatus.DELETED.getCode())
                        .set(ExamInfo::getDeletedAt, LocalDateTime.now())
                        .eq(ExamInfo::getId, id)
        );

        // 作废该考试的所有通知计划
        notifyPlanMapper.cancelBySource(2, id);

        redisService.delete(cacheKey);
        log.info("[考试] 用户 {} 删除考试信息 {}", userId, id);
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
        String cacheKey = RedisKeyConstant.examDetail(id);
        ExamDetailBO cached = redisService.get(cacheKey, ExamDetailBO.class);
        ExamDetailBO detailBO;

        if (cached != null) {
            detailBO =cached;
        } else {
            ExamInfo examInfo = requireVisibleExam(id);

            detailBO = examConverter.toDetailBO(examInfo);

            detailBO.setAuthor(resolveAuthor(examInfo.getUserId()));
            detailBO.setAttachmentItems(
                    mediaService.listAttachments(TargetType.EXAM, id));
            detailBO.setTimelineItems(
                    timelineService.listTimelinesByTarget(TargetType.EXAM, id));

        }

        ExamDetailVO vo = examConverter.toDetailVO(detailBO);

        enrichUserState(vo, id);
        return detailBO;
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

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
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
        Long currentUserId = UserContext.getUserId();
        boolean isOwner = currentUserId != null && Objects.equals(currentUserId, examInfo.getUserId());
        if (!isOwner && !Objects.equals(examInfo.getStatus(), ExamStatus.PUBLISHED.getCode())) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }
        return examInfo;
    }

    private void assertOwner(Long userId, String message) {
        UserAuthBO authBO = userService.getUserAuthInfo(userId);
        if (authBO == null || authBO.getRole() == UserRole.USER) {
            throw new BusinessException(ResultCode.ROLE_NOT_ENOUGH, message);
        }
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
}
