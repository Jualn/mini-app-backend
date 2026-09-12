package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.eventcontent.service.EventTimePolicy;
import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.mapper.AdminActivityListRow;
import cn.jualn.miniapp.module.activity.mapper.AdminActivitySummaryRow;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailVO;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 活动业务实现类。
 *
 * <p>该类实现活动的生命周期管理、数据缓存和性能优化。主要特性：</p>
 * <ul>
 *   <li><b>缓存策略</b>：详情页面缓存3分钟，列表页面缓存30秒，不存在记录缓存1分钟</li>
 *   <li><b>查询优化</b>：使用专用mapper方法合并条件查询，避免N+1问题</li>
 *   <li><b>权限验证</b>：创建时自动绑定当前用户，修改删除时一次查询验证权限</li>
 *   <li><b>事务管理</b>：写操作使用@Transactional保证一致性，事务提交后清空相关缓存</li>
 *   <li><b>计数操作</b>：使用数据库原子操作确保计数准确性，避免并发问题</li>
 * </ul>
 *
 * <p><b>用户状态（liked/enrolled）说明</b>：这些字段不被缓存，每次查询时由
 * enrichUserState()根据当前登录用户实时计算，防止用户间数据交叉污染。</p>
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {
    private final cn.jualn.miniapp.module.eventcontent.service.EventContentService eventContentService;

    private static final ObjectMapper CONTACT_MAPPER = new ObjectMapper();

    private final ActivityMapper activityMapper;
    private final ActivityConverter activityConverter;
    private final ActivityEnrollmentService activityEnrollmentService;
    private final MediaService mediaService;
    private final UserService userService;
    private final TimelineService timelineService;
    private final RedisService redisService;
    private final InteractService interactService;
    private final NotifyService notifyService;
    private final cn.jualn.miniapp.module.activity.service.ActivityRegistrationService activityRegistrationService;
    private final cn.jualn.miniapp.module.activity.service.ActivityFormAvailability activityFormAvailability;

    /** 旧用户写入口已停用；事项统一由独立管理端身份维护。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createActivity(ActivityCreateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动由运维管理，请使用管理端接口");
    }

    /** 旧用户写入口已停用；事项统一由独立管理端身份维护。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateActivity(ActivityUpdateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动由运维管理，请使用管理端接口");
    }

    /** 旧用户写入口已停用；事项统一由独立管理端身份维护。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeActivity(Long id) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动由运维管理，请使用管理端接口");
    }

    /**
     * 获取活动详情。
     *
     * <p>该方法返回活动的详细信息，包括作者和媒体附件。使用三级缓存策略提高性能：</p>
     * <ol>
     *   <li><b>一级缓存检查</b>：查询Redis中是否存在活动详情（3分钟TTL）</li>
     *   <li><b>缺失标记</b>：如果活动不存在，Redis缓存特殊值(__NULL__)，1分钟过期</li>
     *   <li><b>数据库查询</b>：缓存未命中时从数据库查询，并缓存结果</li>
     *   <li><b>用户状态丰富</b>：最后调用{@code enrichUserState}根据当前登录用户计算
     *      liked和enrolled字段（这些字段不被缓存以避免用户间污染）</li>
     * </ol>
     *
     * <p><b>缓存构成</b>：缓存的BO对象包含基础字段、作者信息和媒体列表，但不包含用户相关状态。</p>
     *
     * @param id 包含活动ID的参数对象
     * @return 活动详情业务对象，包含完整的作者信息和媒体列表
     * @throws BusinessException 当活动不存在时抛出 {@link ResultCode#NOT_FOUND}
     * @see #getVisibleActivityOrThrow(Long)
     * @see #enrichUserState(ActivityDetailVO, Long)
     * @see ActivityConverter#toDetailBO(Activity)
     */
    @Override
    public ActivityDetailVO getActivityDetail(Long id) {
        Activity visibleActivity = getVisibleActivityOrThrow(id);
        String cacheKey = RedisKeyConstant.activityDetail(id);

        // 1. 查缓存
        ActivityDetailBO cached = redisService.get(cacheKey, ActivityDetailBO.class);

        ActivityDetailBO detailBO;

        if (cached != null) {
            detailBO = cached;
        } else {
            // 2. 查数据库
            Activity activity = visibleActivity;

            detailBO = activityConverter.toDetailBO(activity);

            // TODO:这三个看变化频率，经常变化就不会放缓存中，就要跟用户状态一起获取
            detailBO.setAuthor(resolveAuthor(activity.getUserId()));
            detailBO.setAttachmentItems(loadMediaList(id));
            detailBO.setTimelineItems(
                    timelineService.listTimelinesByTarget(TargetType.ACTIVITY, id)
            );

            // 3. 写缓存
            redisService.set(cacheKey, detailBO, RedisKeyConstant.ACTIVITY_DETAIL_TTL);
        }

        detailBO.setPublishStatus(visibleActivity.getPublishStatus());
        detailBO.setStatus(ActivityStatus.fromCode(visibleActivity.getStatus()));
        detailBO.setSections(eventContentService.sections(TargetType.ACTIVITY, id, detailBO.getContent()));
        detailBO.setActions(eventContentService.actions(TargetType.ACTIVITY, id));
        detailBO.setActivityPhase(EventTimePolicy.phase(detailBO.getPublishStatus() == null ? 0 : detailBO.getPublishStatus(), detailBO.getStartTime(), detailBO.getStartPrecision(), detailBO.getEndTime(), detailBO.getEndPrecision(), LocalDateTime.now()));
        detailBO.setRegistrationStatus(activityFormAvailability.registrationStatus(detailBO.getPublishStatus() == null ? 0 : detailBO.getPublishStatus(), detailBO.getRegistrationMode(), detailBO.getRegistrationStart(), detailBO.getRegistrationStartPrecision(), detailBO.getRegistrationEnd(), detailBO.getRegistrationEndPrecision(), LocalDateTime.now()));
        // 4. 转 VO
        ActivityDetailVO vo = activityConverter.toDetailVO(detailBO);

        // 5. 补用户态字段（关键：最后做）
        enrichUserState(vo, id);
        return vo;
    }

    /**
     * 分页查询活动列表。
     *
     * <p>该方法支持多条件过滤和游标分页。特点：</p>
     * <ul>
     *   <li><b>游标分页</b>：使用lastId实现游标分页，避免offset分页中跳过问题</li>
     *   <li><b>缓存策略</b>：基于状态、分类、关键词、游标的组合缓存，30秒过期</li>
     *   <li><b>查询优化</b>：使用本地Map缓存用户信息，避免N+1查询</li>
     *   <li><b>用户状态</b>：最后通过enrichPageUserState()计算每个活动的用户相关状态</li>
     * </ul>
     *
     * <p><b>查询条件</b>：</p>
     * <ul>
     *   <li>status：必选，默认值由command.getStatus()决定</li>
     *   <li>category：可选，用于分类过滤</li>
     *   <li>keyword：可选，搜索标题、内容、组织者字段</li>
     * </ul>
     *
     * <p><b>性能优化说明</b>：</p>
     * <ul>
     *   <li>分页查询返回pageSize+1条记录，通过是否存在第pageSize+1条来判断是否有下一页</li>
     *   <li>使用userCache（本地HashMap）缓存同一页中重复的用户信息查询</li>
     *   <li>用户状态（liked/enrolled）不被缓存，每次查询时实时计算</li>
     * </ul>
     *
     * @param command 分页查询参数，包含lastId、pageSize、status、category、keyword
     * @return 分页结果，包含活动列表、是否有下一页标志、下次查询的lastId
     * @see ActivityConverter#toListBOList(List)
     */
    @Override
    public PageResult<ActivityListBO> pageActivityList(ActivityPageBO command) {
        int pageSize = command.getPageSize();
        Integer status = command.getStatus();
        if (status != null && !java.util.Set.of(2,3,4).contains(status)) status = null;

        // 复杂动态SQL下沉到Mapper XML，Service层只做业务编排
        List<Activity> activities = activityMapper.selectPageActivities(
                status,
                command.getCategory(),
                command.getKeyword(),
                command.getLastId(),
                pageSize + 1
        );
        boolean hasMore = activities.size() > pageSize;
        if (hasMore) {
            activities = activities.subList(0, pageSize);
        }

        List<ActivityListBO> list = activityConverter.toListBOList(activities);
        LocalDateTime now = LocalDateTime.now();
        for (ActivityListBO item : list) {
            int publication = item.getPublishStatus() == null ? 0 : item.getPublishStatus();
            item.setActivityPhase(EventTimePolicy.phase(publication, item.getStartTime(), item.getStartPrecision(), item.getEndTime(), item.getEndPrecision(), now));
            item.setRegistrationStatus(activityFormAvailability.registrationStatus(publication, item.getRegistrationMode(), item.getRegistrationStart(), item.getRegistrationStartPrecision(), item.getRegistrationEnd(), item.getRegistrationEndPrecision(), now));
        }

        return PageResult.of(list, hasMore, list.isEmpty() ? null : list.get(list.size() - 1).getId());
    }

    @Override
    public PageResult<ActivityListBO> searchActivities(String keyword, Long lastId, Integer pageSize) {
        ActivityPageBO query = new ActivityPageBO();
        query.setKeyword(keyword);
        query.setLastId(lastId);
        query.setPageSize(pageSize);
        return pageActivityList(query);
    }

    @Override
    public AdminActivityPageBO pageAdminActivities(AdminActivityQueryBO query) {
        int pageSize = query.getPageSize() == null ? 20 : query.getPageSize();
        String sort = query.getSort() == null ? "latest" : query.getSort();
        Long lastId = AdminIdCursorCodec.decode(query.getCursor(), sort);
        Long keywordId = parseId(query.getKeyword());
        List<AdminActivityListRow> rows = activityMapper.selectAdminActivityPage(
                query.getStatus(),
                query.getCategory(),
                query.getAudienceMask(),
                query.getKeyword(),
                keywordId,
                sort,
                lastId,
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<AdminActivityListBO> items = rows.stream().map(this::toAdminListBO).toList();
        AdminActivitySummaryRow summaryRow = activityMapper.selectAdminActivitySummary();
        AdminActivitySummaryBO summary = AdminActivitySummaryBO.builder()
                .enrolling(summaryRow == null ? 0L : summaryRow.getEnrolling())
                .ongoing(summaryRow == null ? 0L : summaryRow.getOngoing())
                .reviewing(summaryRow == null ? 0L : summaryRow.getReviewing())
                .startingSoon(summaryRow == null ? 0L : summaryRow.getStartingSoon())
                .build();

        return AdminActivityPageBO.builder()
                .items(items)
                .summary(summary)
                .hasMore(hasMore)
                .nextCursor(items.isEmpty()
                        ? null
                        : AdminIdCursorCodec.encode(sort, items.get(items.size() - 1).getId()))
                .pageSize(pageSize)
                .build();
    }

    @Override
    public AdminActivityDetailBO getAdminActivityDetail(Long id) {
        Activity activity = activityMapper.selectAdminActivityById(id);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        Contact contact = parseContact(activity.getContactInfo());
        return AdminActivityDetailBO.builder()
                .formSchema(cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.parse(activity.getFormSchema())).registrationLimit(activity.getRegistrationLimit())
                .cancelledAt(activity.getCancelledAt()).cancelReason(activity.getCancelReason())
                .publishStatus(activity.getPublishStatus())
                .activityPhase(EventTimePolicy.phase(publication(activity), activity.getStartTime(), activity.getStartPrecision(), activity.getEndTime(), activity.getEndPrecision(), LocalDateTime.now()))
                .registrationStatus(activityFormAvailability.registrationStatus(publication(activity), activity.getRegistrationMode(), activity.getRegistrationStart(), activity.getRegistrationStartPrecision(), activity.getRegistrationEnd(), activity.getRegistrationEndPrecision(), LocalDateTime.now()))
                .startPrecision(activity.getStartPrecision()).endPrecision(activity.getEndPrecision()).timeDescription(activity.getTimeDescription())
                .registrationStart(activity.getRegistrationStart()).registrationEnd(activity.getRegistrationEnd())
                .registrationStartPrecision(activity.getRegistrationStartPrecision()).registrationEndPrecision(activity.getRegistrationEndPrecision())
                .summary(activity.getSummary()).audienceSummary(activity.getAudienceSummary())
                .registrationMode(activity.getRegistrationMode()).participantMode(activity.getParticipantMode())
                .capacityUnit(activity.getCapacityUnit()).coverAttachmentId(activity.getCoverAttachmentId())
                .sections(eventContentService.sections(TargetType.ACTIVITY, id, activity.getContent()))
                .actions(eventContentService.actions(TargetType.ACTIVITY, id))
                .id(activity.getId())
                .userId(activity.getUserId())
                .title(activity.getTitle())
                .content(activity.getContent())
                .location(activity.getLocation())
                .category(ActivityCategory.fromCode(activity.getCategory()))
                .status(ActivityStatus.fromCode(activity.getStatus()))
                .auditStatus(activity.getAuditStatus())
                .rejectReason(activity.getRejectReason())
                .organizer(activity.getOrganizer())
                .audienceScope(activity.getAudienceScope())
                .contactName(contact.name())
                .contactPhone(contact.phone())
                .joinMethod(activity.getJoinMethod())
                .qrcodeUrl(activity.getQrcodeUrl())
                .startTime(activity.getStartTime())
                .endTime(activity.getEndTime())
                .enrollDeadline(activity.getEnrollDeadline())
                .capacity(activity.getMaxParticipants())
                .officialCapacity(activity.getCapacity() == null ? activity.getMaxParticipants() : activity.getCapacity())
                .pinned(activity.getIsPinned())
                .commentCount(activity.getCommentCount())
                .likeCount(activity.getLikeCount())
                .viewCount(activity.getViewCount())
                .subscriberCount(activityEnrollmentService.countActiveSubscribers(id))
                .notifyEnabledSubscriberCount(activityEnrollmentService.countNotifyEnabledSubscribers(id))
                .publishedAt(activity.getPublishedAt())
                .createdAt(activity.getCreatedAt())
                .updatedAt(activity.getUpdatedAt())
                .author(userService.getSimpleInfo(activity.getUserId()))
                .attachments(mediaService.listAttachments(TargetType.ACTIVITY, id))
                .timeline(timelineService.listTimelinesByTarget(TargetType.ACTIVITY, id))
                .build();
    }

    @Override
    public AdminActivityDetailBO getAdminActivityDraft(Long id) {
        AdminActivityDetailBO detail = getAdminActivityDetail(id);
        if (Integer.valueOf(3).equals(detail.getPublishStatus()))
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "已取消活动不可编辑");
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAdminActivity(AdminActivitySaveBO command) {
        validateAdminSave(command);
        Activity activity = toAdminEntity(command);
        normalizeSchedule(activity);
        activity.setUserId(command.getOperatorId());
        activity.setStatus(ActivityStatus.DRAFT.getCode());
        activity.setPublishStatus(0);
        activity.setAuditStatus(0);
        activity.setIsPinned(Boolean.FALSE);
        activity.setCommentCount(0);
        activity.setLikeCount(0);
        activity.setViewCount(0);
        if (activityMapper.insert(activity) != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动草稿创建失败");
        }
        saveAdminChildren(activity.getId(), command, false, activity.getContent());
        log.info("[管理端活动] 管理员 {} 创建草稿 {}", command.getOperatorId(), activity.getId());
        return activity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAdminActivity(AdminActivitySaveBO command) {
        validateAdminSave(command);
        if (command.getId() == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动ID不能为空");
        }
        Activity current = activityMapper.selectForUpdate(command.getId());
        assertAdminEditable(current);
        String nextSchema = command.getFormSchema() == null ? current.getFormSchema()
                : command.getFormSchema().isNull() ? null : command.getFormSchema().toString();
        Integer nextLimit = Boolean.TRUE.equals(command.getClearRegistrationLimit()) ? null
                : command.getRegistrationLimit() == null ? current.getRegistrationLimit() : command.getRegistrationLimit();
        activityRegistrationService.validateFormChange(current.getId(), current.getFormSchema(), nextSchema, nextLimit,
                current.getRegistrationMode(), command.getRegistrationMode() == null ? current.getRegistrationMode() : command.getRegistrationMode(),
                command.getParticipantMode() == null ? current.getParticipantMode() : command.getParticipantMode());
        if (current.getCapacityUnit() != null && current.getCapacityUnit() == 2 && command.getCapacityUnit() == null)
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "此活动按队限制容量，请使用新版编辑器");

        int updated = activityMapper.update(null,
                new LambdaUpdateWrapper<Activity>()
                        .set(Activity::getFormSchema, nextSchema)
                        .set(Activity::getRegistrationLimit, nextLimit)
                        .set(Activity::getTitle, command.getTitle())
                        .set(command.getContent() != null, Activity::getContent, command.getContent())
                        .set(Activity::getLocation, command.getLocation())
                        .set(Activity::getCategory, command.getCategory().getCode())
                        .set(Activity::getOrganizer, command.getOrganizer())
                        .set(Activity::getAudienceScope, command.getAudienceScope())
                        .set(Activity::getContactInfo, serializeContact(command.getContactName(), command.getContactPhone()))
                        .set(Activity::getJoinMethod, command.getJoinMethod())
                        .set(Activity::getQrcodeUrl, command.getQrcodeUrl())
                        .set(Activity::getStartTime, command.getStartTime())
                        .set(Activity::getEndTime, command.getEndTime())
                        .set(Activity::getEnrollDeadline, command.getEnrollDeadline())
                        .set(Activity::getMaxParticipants, command.getMaxParticipants())
                        .set(Activity::getStatus, publication(current) == 1 ? 2 : 0)
                        .set(Activity::getPublishStatus, publication(current))
                        .set(Activity::getAuditStatus, 0)
                        .set(Activity::getRejectReason, null)
                        .eq(Activity::getId, command.getId())
                        .eq(Activity::getPublishStatus, publication(current))
                        .isNull(Activity::getDeletedAt));
        if (updated != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动草稿更新失败");
        }
        saveAdminChildren(command.getId(), command, true, current.getContent());
        Activity saved = activityMapper.selectForUpdate(command.getId());
        if (publication(saved) == 1) validateAdminSubmission(saved);
        refreshActivityReminder(saved);
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(command.getId())));
        log.info("[管理端活动] 管理员 {} 更新草稿 {}", command.getOperatorId(), command.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitAdminActivityReview(Long activityId, Long operatorId) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动暂不接入审核，请使用直接发布接口");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAdminActivityPinned(Long activityId, Long operatorId, boolean pinned) {
        validateAdminAction(activityId, operatorId, null, false);
        Activity current = activityMapper.selectForUpdate(activityId);
        if (current == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        if (current.getStatus() == null
                || (current.getStatus() != ActivityStatus.SIGNUP.getCode()
                && current.getStatus() != ActivityStatus.ONGOING.getCode())) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED,
                    "只有报名中或进行中的活动可以调整置顶状态");
        }
        if (Boolean.valueOf(pinned).equals(current.getIsPinned())) {
            return;
        }
        if (activityMapper.updateAdminPinned(activityId, pinned) != 1) {
            throwAdminActivityConflict(activityId, "活动状态已变化，请刷新后重试");
        }
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
        log.info("[管理端活动] 管理员 {} {}活动 {}", operatorId, pinned ? "置顶" : "取消置顶", activityId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAdminActivity(Long activityId, Long operatorId, String reason) {
        validateAdminAction(activityId, operatorId, reason, true);
        Activity current = activityMapper.selectForUpdate(activityId);
        if (current == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        if (publication(current) == 3) return;
        if (activityMapper.cancelAdminActivity(activityId, reason.trim()) != 1) {
            throwAdminActivityConflict(activityId, "只有尚未开始的报名中活动可以取消");
        }
        notifyService.cancelActivityPlans(activityId);
        String noticeContent = "活动「" + current.getTitle() + "」已取消：" + reason.trim();
        afterCommit(() -> {
            redisService.delete(RedisKeyConstant.activityDetail(activityId));
            notifyActivityStatusChange(activityId, "活动已取消", noticeContent);
        });
        log.info("[管理端活动] 管理员 {} 取消活动 {}, reason={}", operatorId, activityId, reason.trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void endAdminActivityEarly(Long activityId, Long operatorId, String reason) {
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
        afterCommit(() -> {
            redisService.delete(RedisKeyConstant.activityDetail(activityId));
            notifyActivityStatusChange(activityId, "活动已提前结束", noticeContent);
        });
        log.info("[管理端活动] 管理员 {} 提前结束活动 {}, reason={}", operatorId, activityId, reason.trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeAdminActivity(Long id, Long operatorId, String reason) {
        if (operatorId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "删除原因不能为空");
        }
        Activity current = activityMapper.selectForUpdate(id);
        assertAdminEditable(current);
        if (publication(current) == 1)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "已发布活动请先下架");
        int updated = activityMapper.update(null,
                new LambdaUpdateWrapper<Activity>()
                        .set(Activity::getStatus, ActivityStatus.DELETED.getCode())
                        .set(Activity::getPublishStatus, 2)
                        .set(Activity::getIsPinned, Boolean.FALSE)
                        .set(Activity::getDeletedAt, LocalDateTime.now())
                        .eq(Activity::getId, id)
                        .eq(Activity::getPublishStatus, publication(current))
                        .isNull(Activity::getDeletedAt));
        if (updated != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动草稿删除失败");
        }
        afterCommit(() -> {
            redisService.delete(RedisKeyConstant.activityDetail(id));
            redisService.delete(RedisKeyConstant.targetExists(TargetType.ACTIVITY.getKey(), id));
        });
        // 当前版本尚无通用管理员审计表，先保留结构化日志；后续接入审计模型时由本用例统一写入。
        log.info("[管理端活动] 管理员 {} 删除草稿 {}, reason={}", operatorId, id, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveActivityReview(Long activityId, Long operatorId, String remark) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动暂不接入审核，请使用直接发布接口");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectActivityReview(Long activityId, Long operatorId, String reason) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动暂不接入审核，请使用直接发布接口");
    }


    private void throwActivityReviewConflict(Long activityId, String message) {
        if (activityMapper.selectAdminActivityById(activityId) == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        throw new BusinessException(ResultCode.DATA_CONFLICT, message);
    }

    private void validateAdminAction(Long activityId, Long operatorId, String reason, boolean reasonRequired) {
        if (activityId == null || operatorId == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动管理操作参数不完整");
        }
        if (reasonRequired && !StringUtils.hasText(reason)) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "操作原因不能为空");
        }
    }

    private void validateAdminSubmission(Activity activity) {
        if (!StringUtils.hasText(activity.getTitle())
                || !StringUtils.hasText(activity.getContent())
                || !StringUtils.hasText(activity.getOrganizer())
                || activity.getCategory() == null
                || activity.getAudienceScope() == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动必填信息未填写完整");
        }
        normalizeSchedule(activity);
        if (activity.getRegistrationMode() == null || !Set.of(1, 2, 3, 4).contains(activity.getRegistrationMode()))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "发布前请确认报名方式");
        if (Set.of(2, 4).contains(activity.getRegistrationMode())) {
            activityFormAvailability.requireEnabled();
            cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.schema(
                    cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.parse(activity.getFormSchema()));
            cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.require(Integer.valueOf(1).equals(activity.getParticipantMode())
                    && activity.getRegistrationEnd() != null && Integer.valueOf(2).equals(activity.getRegistrationEndPrecision())
                    && (activity.getRegistrationStart() == null || (activity.getRegistrationStartPrecision() != null && activity.getRegistrationStartPrecision() > 0))
                    && (activity.getRegistrationStart() == null || activity.getRegistrationStart().isBefore(activity.getRegistrationEnd())),
                    "平台表单仅支持个人报名，须明确报名窗口及精确截止时刻");
            if (activity.getRegistrationMode() == 4)
                cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.require(eventContentService.actions(TargetType.ACTIVITY, activity.getId()).stream()
                        .anyMatch(a -> Boolean.TRUE.equals(a.getIsRequired())), "组合报名须提供必做外部步骤");
        }
    }



    private void notifyActivityStatusChange(Long activityId, String title, String content) {
        try {
            notifyService.notifyActivitySubscribers(activityId, title, content);
        } catch (Exception exception) {
            // 当前通知基础设施没有业务 outbox；状态提交成功后通知失败只能记录，避免对外误报状态回滚。
            log.error("[管理端活动] 活动状态通知投递失败，activityId={}", activityId, exception);
        }
    }

    private void throwAdminActivityConflict(Long activityId, String message) {
        if (activityMapper.selectAdminActivityById(activityId) == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        throw new BusinessException(ResultCode.DATA_CONFLICT, message);
    }

    private AdminActivityListBO toAdminListBO(AdminActivityListRow row) {
        return AdminActivityListBO.builder()
                .id(row.getId())
                .title(row.getTitle())
                .summary(row.getSummary())
                .category(ActivityCategory.fromCode(row.getCategory()))
                .status(ActivityStatus.fromCode(row.getStatus()))
                .publishStatus(row.getPublishStatus())
                .auditStatus(row.getAuditStatus())
                .organizer(row.getOrganizer())
                .location(row.getLocation())
                .audienceScope(row.getAudienceScope())
                .pinned(row.getPinned())
                .subscriberCount(row.getSubscriberCount())
                .capacity(row.getCapacity())
                .startTime(row.getStartTime())
                .endTime(row.getEndTime())
                .enrollDeadline(row.getEnrollDeadline())
                .viewCount(row.getViewCount())
                .updatedAt(row.getUpdatedAt())
                .build();
    }

    private Activity toAdminEntity(AdminActivitySaveBO command) {
        return Activity.builder()
                .formSchema(command.getFormSchema() == null || command.getFormSchema().isNull() ? null : command.getFormSchema().toString())
                .registrationLimit(command.getRegistrationLimit())
                .startPrecision(command.getStartPrecision()).endPrecision(command.getEndPrecision())
                .timeDescription(command.getTimeDescription())
                .registrationStart(command.getRegistrationStart()).registrationEnd(command.getRegistrationEnd())
                .registrationStartPrecision(command.getRegistrationStartPrecision()).registrationEndPrecision(command.getRegistrationEndPrecision())
                .title(command.getTitle())
                .content(command.getContent() == null ? "" : command.getContent())
                .location(command.getLocation())
                .category(command.getCategory().getCode())
                .organizer(command.getOrganizer())
                .audienceScope(command.getAudienceScope())
                .contactInfo(serializeContact(command.getContactName(), command.getContactPhone()))
                .joinMethod(command.getJoinMethod())
                .qrcodeUrl(command.getQrcodeUrl())
                .startTime(command.getStartTime())
                .endTime(command.getEndTime())
                .enrollDeadline(command.getEnrollDeadline())
                .maxParticipants(command.getMaxParticipants())
                .build();
    }

    private void saveAdminChildren(Long activityId, AdminActivitySaveBO command, boolean replaceEmpty, String legacyContent) {
        Activity schedule = toAdminEntity(command);
        normalizeSchedule(schedule);
        String projection = eventContentService.saveSections(TargetType.ACTIVITY, activityId, command.getSections(), command.getContent() == null ? legacyContent : command.getContent());
        eventContentService.prepareActions(TargetType.ACTIVITY, activityId, command.getActions());

        if (replaceEmpty || !CollectionUtils.isEmpty(command.getTimelineItems())) {
            timelineService.replaceTimelines(TimelineSaveBO.builder()
                    .targetType(TargetType.ACTIVITY)
                    .targetId(activityId)
                    .timelines(command.getTimelineItems())
                    .build());
        }
        if (replaceEmpty || !CollectionUtils.isEmpty(command.getAttachmentItems())) {
            mediaService.replaceAttachments(MediaAttachmentSaveBO.builder()
                    .targetType(TargetType.ACTIVITY)
                    .targetId(activityId)
                    .attachments(command.getAttachmentItems())
                    .build());
        }
        eventContentService.saveActions(TargetType.ACTIVITY, activityId, command.getActions());
        Long coverId = command.getCoverAttachmentId();
        if (Boolean.TRUE.equals(command.getClearCover()) && (coverId != null || StringUtils.hasText(command.getCoverObjectKey())))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "清除封面与设置封面不能同时提交");
        if (StringUtils.hasText(command.getCoverObjectKey())) {
            Long resolved = mediaService.listAttachments(TargetType.ACTIVITY, activityId).stream()
                    .filter(a -> command.getCoverObjectKey().equals(a.getObjectKey()))
                    .map(MediaAttachmentBO::getId).findFirst()
                    .orElseThrow(() -> new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "封面对象不属于当前活动"));
            if (coverId != null && !coverId.equals(resolved))
                throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "封面ID与对象键不一致");
            coverId = resolved;
        }
        eventContentService.validateCover(TargetType.ACTIVITY, activityId, coverId);
        Integer capacity = command.getCapacity() == null ? command.getMaxParticipants() : command.getCapacity();
        Integer unit = command.getCapacityUnit() == null ? (capacity == null ? null : 1) : command.getCapacityUnit();
        if ((capacity == null) != (unit == null))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量与单位必须同时填写");

        int rows = activityMapper.update(null, new LambdaUpdateWrapper<Activity>()
                .set(Activity::getContent, projection)
                .set(Activity::getStartPrecision, schedule.getStartPrecision()).set(Activity::getEndPrecision, schedule.getEndPrecision())
                .set(Activity::getTimeDescription, schedule.getTimeDescription())
                .set(Activity::getRegistrationStart, schedule.getRegistrationStart()).set(Activity::getRegistrationEnd, schedule.getRegistrationEnd())
                .set(Activity::getRegistrationStartPrecision, schedule.getRegistrationStartPrecision()).set(Activity::getRegistrationEndPrecision, schedule.getRegistrationEndPrecision())
                .set(Activity::getEnrollDeadline, schedule.getRegistrationEnd())
                .set(command.getSummary() != null, Activity::getSummary, command.getSummary())
                .set(command.getAudienceSummary() != null, Activity::getAudienceSummary, command.getAudienceSummary())
                .set(command.getRegistrationMode() != null, Activity::getRegistrationMode, command.getRegistrationMode())
                .set(command.getParticipantMode() != null, Activity::getParticipantMode, command.getParticipantMode())
                .set(Activity::getCapacity, capacity).set(Activity::getCapacityUnit, unit)
                .set(Activity::getMaxParticipants, Integer.valueOf(1).equals(unit) ? capacity : null)
                .set(coverId != null || Boolean.TRUE.equals(command.getClearCover()), Activity::getCoverAttachmentId, coverId)
                .eq(Activity::getId, activityId));
        if (rows != 1) throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动子资源保存失败");
    }

    private void validateAdminSave(AdminActivitySaveBO command) {
        if (command == null || command.getOperatorId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (command.getRegistrationMode() != null && !java.util.Set.of(0,1,2,3,4).contains(command.getRegistrationMode()))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "报名方式不合法");
        if (command.getFormSchema() != null && !command.getFormSchema().isNull())
            cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.schema(command.getFormSchema());
        cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.require(command.getRegistrationLimit() == null || command.getRegistrationLimit() > 0, "平台收表限额必须大于0");
        cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.require(!Boolean.TRUE.equals(command.getClearRegistrationLimit()) || command.getRegistrationLimit() == null, "清除限额与设置限额不能同时提交");
        if (command.getParticipantMode() != null && (command.getParticipantMode() < 0 || command.getParticipantMode() > 3))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "参与形式不合法");
        if (command.getCapacity() != null && command.getCapacity() <= 0) throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量必须大于0");
        if (command.getCapacityUnit() != null && command.getCapacityUnit() != 1 && command.getCapacityUnit() != 2)
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量单位不合法");
        if (command.getCategory() == null || command.getAudienceScope() == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID);
        }
        if ((command.getAudienceScope() & 1) != 0 && command.getAudienceScope() != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "全院与具体学科部不能同时选择");
        }
        EventTimePolicy.range(command.getStartTime(), command.getEndTime(), command.getEndPrecision());
        EventTimePolicy.range(command.getRegistrationStart(), command.getRegistrationEnd(), command.getRegistrationEndPrecision());
        boolean hasContactName = command.getContactName() != null;
        boolean hasContactPhone = command.getContactPhone() != null;
        if (hasContactName != hasContactPhone) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "联系人姓名和电话必须同时填写");
        }
        for (var item : safeList(command.getTimelineItems())) {
            EventTimePolicy.range(item.getStartTime(), item.getEndTime(), item.getEndPrecision());
        }
    }

    private void assertAdminEditable(Activity activity) {
        if (activity == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        if (publication(activity) == 3 || activity.getDeletedAt() != null)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "已取消或删除的活动不可编辑");
    }

    private Long parseId(String keyword) {
        if (keyword == null) {
            return null;
        }
        try {
            return Long.valueOf(keyword);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String serializeContact(String name, String phone) {
        if (name == null && phone == null) {
            return null;
        }
        try {
            return CONTACT_MAPPER.writeValueAsString(List.of(new Contact(name, phone)));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "联系人信息格式错误");
        }
    }

    private Contact parseContact(String contactInfo) {
        if (contactInfo == null || contactInfo.isBlank()) {
            return new Contact(null, null);
        }
        try {
            JsonNode root = CONTACT_MAPPER.readTree(contactInfo);
            if (!root.isArray() || root.isEmpty()) {
                return new Contact(null, null);
            }
            JsonNode first = root.get(0);
            return new Contact(textOrNull(first.get("name")), textOrNull(first.get("phone")));
        } catch (JsonProcessingException e) {
            log.warn("[管理端活动] 无法解析历史联系人信息");
            return new Contact(null, null);
        }
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private record Contact(String name, String phone) {
    }

    /**
     * 增加活动的评论数。
     *
     * <p>原子操作，使用SQL直接修改，保证并发安全。操作完成后清空活动详情缓存。</p>
     *
     * @param activityId 活动ID
     * @see ActivityMapper#increaseCommentCount(Long)
     */
    @Override
    public void increaseCommentCount(Long activityId) {
        activityMapper.increaseCommentCount(activityId);
        redisService.delete(RedisKeyConstant.activityDetail(activityId));
    }

    /**
     * 减少活动的评论数（最低为0）。
     *
     * <p>原子操作，使用SQL的CASE语句保证计数不会变为负数。操作完成后清空活动详情缓存。</p>
     *
     * @param activityId 活动ID
     * @see ActivityMapper#decreaseCommentCount(Long)
     */
    @Override
    public void decreaseCommentCount(Long activityId) {
        activityMapper.decreaseCommentCount(activityId);
        redisService.delete(RedisKeyConstant.activityDetail(activityId));
    }

    /**
     * 从ThreadLocal上下文获取当前用户ID。
     *
     * <p>该方法是一个内部辅助方法，用于集中处理用户认证检查。所有需要用户ID的公共方法
     * 都应该调用此方法，而不是直接调用UserContext.getUserId()。</p>
     *
     * @return 当前用户的ID
     * @throws BusinessException 当用户未登录时抛出 {@link ResultCode#UNAUTHORIZED}
     */


    /**
     * 根据ID获取可见的活动。
     *
     * <p>该方法用于查询活动详情的场景。当活动不存在时，会缓存一个特殊的null标记(__NULL__)
     * 在Redis中，有效期1分钟，以避免持续向数据库查询不存在的活动。</p>
     *
     * @param activityId 活动ID
     * @return 未删除的活动对象
     * @throws BusinessException 当活动不存在或已删除时抛出 {@link ResultCode#NOT_FOUND}
     * @see ActivityMapper#selectByIdNotDeleted(Long)
     */
    private Activity getVisibleActivityOrThrow(Long activityId) {
        // 使用专用方法一次查询并检查deleted_at，避免业务逻辑中重复的null检查
        Activity activity = activityMapper.selectByIdNotDeleted(activityId);
        if (activity == null) {
            String cacheKey = RedisKeyConstant.activityDetail(activityId);
            redisService.setNullPlaceholder(cacheKey, RedisKeyConstant.ACTIVITY_DETAIL_TTL);
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND, "活动不存在");
        }
        if (publication(activity) != 1)
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        return activity;
    }

    // 保留，目前list没做缓存
    private String buildActivityPageCacheKey(Integer category, Integer status, Long lastId, Integer pageSize, String keyword) {
        return RedisKeyConstant.PREFIX + "activity:page:"
                + (category == null ? "_" : category) + ":"
                + (status == null ? "_" : status) + ":"
                + (lastId == null ? "_" : lastId) + ":"
                + (pageSize == null ? "_" : pageSize) + ":"
                + (keyword == null ? "_" : keyword.trim());
    }

    private List<MediaAttachmentBO> loadMediaList(Long activityId) {
        return mediaService.listAttachments(TargetType.ACTIVITY, activityId);
    }

    /**
     * 获取用户简版信息。
     *
     * <p>该方法用于解析活动作者的显示信息。UserService可能在内部做了缓存，
     * 而我们在pageActivity中使用本地Map进一步减少重复查询。</p>
     *
     * @param userId 用户ID
     * @return 用户简版信息
     * @see UserService#getSimpleInfo(Long)
     */
    private UserSimpleBO resolveAuthor(Long userId) {
        return userService.getSimpleInfo(userId);
    }

    /**
     * 丰富活动的用户相关状态。
     *
     * <p>该方法用于在返回给用户前，填充当前用户相关的临时字段：</p>
     * <ul>
     *   <li>{@code liked}：当前用户是否点赞了该活动（暂时始终为false，可扩展）</li>
     *   <li>{@code enrolled}：当前用户是否报名了该活动</li>
     * </ul>
     *
     * <p><b>重要</b>：这些字段不会被Redis缓存，每次都需要根据当前用户动态计算，
     * 防止不同用户看到彼此的状态。</p>
     *
     * @param detailVO   活动详情业务对象
     * @param activityId 活动ID，用于查询报名状态
     */
    private void enrichUserState(ActivityDetailVO detailVO, Long activityId) {
        detailVO.setLiked(interactService.isLiked(TargetType.ACTIVITY, activityId));
        detailVO.setEnrolled(activityEnrollmentService.isEnrolled(activityId));
    }

    /**
     * 丰富分页结果中所有活动的用户相关状态。(保留，但目前分页接口不返回用户状态字段)
     *
     * <p>该方法为分页列表中的每个活动对象都计算当前用户的相关状态。
     * 避免在循环内调用enrichUserState以减少方法调用开销。</p>
     *
     * <p><b>性能说明</b>：该方法会对每个活动调用activityEnrollmentService.isEnrolled()，
     * 相关查询应该在服务层做了缓存处理。</p>
     *
     * @param pageResult 分页结果对象
     */
    private void enrichPageUserState(PageResult<ActivityDetailVO> pageResult) {
        if (pageResult == null || pageResult.getList() == null) {
            return;
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            for (ActivityDetailVO detailVO : pageResult.getList()) {
                detailVO.setLiked(Boolean.FALSE);
                detailVO.setEnrolled(Boolean.FALSE);
            }
            return;
        }

        List<Long> activityIds = new ArrayList<>(pageResult.getList().size());
        for (ActivityDetailVO detailVO : pageResult.getList()) {
            activityIds.add(detailVO.getId());
        }
        Set<Long> enrolledActivityIds = activityEnrollmentService.listEnrolledActivityIds(activityIds);

        for (ActivityDetailVO detailVO : pageResult.getList()) {
            detailVO.setLiked(Boolean.FALSE);
            detailVO.setEnrolled(enrolledActivityIds.contains(detailVO.getId()));
        }
    }

    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try { task.run(); }
                    catch (RuntimeException e) { log.error("活动提交后副作用失败", e); }
                }
            });
        } else {
            task.run();
        }
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishAdminActivity(Long activityId, Long operatorId) {
        validateAdminAction(activityId, operatorId, null, false);
        Activity current = activityMapper.selectForUpdate(activityId);
        assertAdminEditable(current);
        if (publication(current) == 1) return;
        validateAdminSubmission(current);
        if (activityMapper.publishDirectly(activityId) != 1)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动发布失败");
        current.setPublishStatus(1);
        refreshActivityReminder(current);
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void takeDownAdminActivity(Long activityId, Long operatorId, String reason) {
        validateAdminAction(activityId, operatorId, reason, true);
        Activity current = activityMapper.selectForUpdate(activityId);
        if (current == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        if (publication(current) == 2) return;
        if (publication(current) != 1 || activityMapper.takeDownDirectly(activityId) != 1)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "只有已发布活动可以下架");
        notifyService.cancelActivityPlans(activityId);
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
        log.info("[活动下架] id={}, operatorId={}, reason={}", activityId, operatorId, reason.trim());
    }

    private void refreshActivityReminder(Activity activity) {
        LocalDateTime sendAt = publication(activity) == 1 && Integer.valueOf(2).equals(activity.getStartPrecision())
                && activity.getStartTime() != null ? activity.getStartTime().minusHours(1) : null;
        notifyService.replaceEventReminder(TargetType.ACTIVITY, activity.getId(), activity.getTitle(), sendAt);
    }

    private int publication(Activity activity) {
        return activity.getPublishStatus() == null ? 0 : activity.getPublishStatus();
    }

    private void normalizeSchedule(Activity activity) {
        if (activity.getTimeDescription() != null && activity.getTimeDescription().length() > 255)
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "时间说明超过255字");
        if (activity.getRegistrationEnd() == null) activity.setRegistrationEnd(activity.getEnrollDeadline());
        activity.setStartPrecision(EventTimePolicy.precision(activity.getStartTime(), activity.getStartPrecision()));
        activity.setEndPrecision(EventTimePolicy.precision(activity.getEndTime(), activity.getEndPrecision()));
        activity.setRegistrationStartPrecision(EventTimePolicy.precision(activity.getRegistrationStart(), activity.getRegistrationStartPrecision()));
        activity.setRegistrationEndPrecision(EventTimePolicy.precision(activity.getRegistrationEnd(), activity.getRegistrationEndPrecision()));
        EventTimePolicy.range(activity.getStartTime(), activity.getEndTime(), activity.getEndPrecision());
        EventTimePolicy.range(activity.getRegistrationStart(), activity.getRegistrationEnd(), activity.getRegistrationEndPrecision());
        activity.setEnrollDeadline(activity.getRegistrationEnd());
    }
}
