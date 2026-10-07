package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.eventcontent.service.EventTimePolicy;
import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.mapper.AdminActivityListRow;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.ActivityPublicCursorCodec;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import cn.jualn.miniapp.module.timeline.service.CardTimelinePolicy;
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
import cn.jualn.miniapp.common.web.StrongEtag;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private static final cn.jualn.miniapp.module.activity.service.ActivityReminderPolicy REMINDER_POLICY =
            new cn.jualn.miniapp.module.activity.service.ActivityReminderPolicy();

    private final ActivityMapper activityMapper;
    private final ActivityConverter activityConverter;
    private final ActivityEnrollmentService activityEnrollmentService;
    private final MediaService mediaService;
    private final UserService userService;
    private final TimelineService timelineService;
    private final RedisService redisService;
    private final InteractService interactService;
    private final NotifyService notifyService;
    private final cn.jualn.miniapp.module.eventcontent.service.EventContactCodec eventContactCodec;
    private final cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy participationPolicy;
    private final cn.jualn.miniapp.module.activity.service.ActivityRegistrationService activityRegistrationService;
    private final cn.jualn.miniapp.module.activity.service.ActivityFormAvailability activityFormAvailability;

    /**
     * 旧用户写入口已停用；事项统一由独立管理端身份维护。
     */
    @Override
    public boolean isPubliclyVisible(Long id) {
        return id != null && activityMapper.existsPublicById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createActivity(ActivityCreateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动由运维管理，请使用管理端接口");
    }

    /**
     * 旧用户写入口已停用；事项统一由独立管理端身份维护。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateActivity(ActivityUpdateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动由运维管理，请使用管理端接口");
    }

    /**
     * 旧用户写入口已停用；事项统一由独立管理端身份维护。
     */
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
     * @see #enrichUserState(ActivityDetailBO, Long)
     * @see ActivityConverter#toDetailBO(Activity)
     */
    @Override
    public ActivityDetailBO getActivityDetail(Long id) {
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
        detailBO.setRegistrationMode(visibleActivity.getRegistrationMode());
        detailBO.setParticipantMode(visibleActivity.getParticipantMode());
        detailBO.setSections(eventContentService.sections(TargetType.ACTIVITY, id));
        detailBO.setActions(eventContentService.actions(TargetType.ACTIVITY, id));
        detailBO.setLifecycleStatus(visibleActivity.getLifecycleStatus());
        detailBO.setAudienceDepartmentIds(visibleActivity.getAudienceDepartmentIds());
        detailBO.setContactsJson(visibleActivity.getContactsJson());
        enrichUserState(detailBO, id);
        return detailBO;
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
     * @return 分页结果，包含活动列表、是否有下一页标志、下次查询的lastId
     * @see ActivityConverter#toListBOList(List)
     */
    @Override
    public ActivityDetailBO getActivityResource(Long id) {
        Activity activity = getVisibleActivityOrThrow(id);
        ActivityDetailBO result = activityConverter.toDetailBO(activity);
        result.setAttachmentItems(mediaService.listAttachments(TargetType.ACTIVITY, id));
        result.setTimelineItems(timelineService.listTimelinesByTarget(TargetType.ACTIVITY, id));
        result.setSections(eventContentService.sections(TargetType.ACTIVITY, id));
        result.setActions(eventContentService.actions(TargetType.ACTIVITY, id));
        result.setContacts(eventContactCodec.read(result.getContactsJson(), null, null, null));
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        result.setCardTimeline(CardTimelinePolicy.select(result.getTimelineItems(), now));
        if (participationPolicy.platform(result.getRegistrationMode())) {
            var form = activityRegistrationService.getForm(id);
            result.setRegistrationForm(form);
            result.setSubmittedCount(form.submittedCount());
        }
        result.setEvaluatedAt(now);
        result.setParticipationState(participationPolicy.evaluate(result.getPublishStatus(), result.getLifecycleStatus(),
                result.getRegistrationMode(), result.getTimelineItems(), result.getCapacity(), result.getSubmittedCount(), now));
        return result;
    }

    @Override
    public ActivityResourcePageBO pageActivityResources(ActivityPageBO query) {
        query.setLastId(ActivityPublicCursorCodec.decode(query));
        PageResult<ActivityListBO> page = pageActivityList(query);
        var coverIds = page.getList().stream().map(ActivityListBO::getCoverAttachmentId)
                .filter(java.util.Objects::nonNull).toList();
        Map<Long, MediaAttachmentBO> covers = mediaService.batchGetAttachments(coverIds);
        var ids = page.getList().stream().filter(item -> participationPolicy.platform(item.getRegistrationMode()))
                .map(ActivityListBO::getId).toList();
        Map<Long, Long> counts = activityRegistrationService.countSubmittedByActivityIds(ids);
        Map<Long, List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO>> timelines =
                timelineService.listTimelinesByTargets(TargetType.ACTIVITY,
                        page.getList().stream().map(ActivityListBO::getId).toList());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        for (ActivityListBO item : page.getList()) {
            MediaAttachmentBO cover = covers.get(item.getCoverAttachmentId());
            if (cover != null && Integer.valueOf(TargetType.ACTIVITY.getCode()).equals(cover.getTargetType())
                    && item.getId().equals(cover.getTargetId())) {
                item.setCoverAttachment(cover);
            }
            item.setEvaluatedAt(now);
            item.setCardTimeline(CardTimelinePolicy.select(
                    timelines.getOrDefault(item.getId(), List.of()), now));
            if (participationPolicy.platform(item.getRegistrationMode())) {
                item.setSubmittedCount(counts.getOrDefault(item.getId(), 0L));
            }
            item.setParticipationState(participationPolicy.evaluate(item.getPublishStatus(), item.getLifecycleStatus(),
                    item.getRegistrationMode(), timelines.getOrDefault(item.getId(), List.of()),
                    item.getCapacity(), item.getSubmittedCount(), now));
        }
        String nextCursor = Boolean.TRUE.equals(page.getHasMore())
                ? ActivityPublicCursorCodec.encode(query, page.getNextCursor()) : null;
        return new ActivityResourcePageBO(page.getList(), nextCursor);
    }

    @Override
    public PageResult<ActivityListBO> pageActivityList(ActivityPageBO command) {
        int pageSize = command.getPageSize();
        Integer status = command.getStatus();
        if (status != null && !java.util.Set.of(2, 3, 4).contains(status)) status = null;

        // 复杂动态SQL下沉到Mapper XML，Service层只做业务编排
        List<Activity> activities = activityMapper.selectPageActivities(
                status,
                command.getCategory(),
                command.getLifecycleStatus(),
                command.getKeyword(),
                command.getLastId(),
                command.isCampusAudienceOnly(),
                pageSize + 1
        );
        boolean hasMore = activities.size() > pageSize;
        if (hasMore) {
            activities = activities.subList(0, pageSize);
        }

        List<ActivityListBO> list = activityConverter.toListBOList(activities);
        for (int index = 0; index < list.size(); index++) {
            ActivityListBO item = list.get(index);
            item.setRegistrationMode(activities.get(index).getRegistrationMode());
            item.setParticipantMode(activities.get(index).getParticipantMode());
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
        int page = query.getPage() == null ? 1 : query.getPage();
        int pageSize = query.getPageSize() == null ? 20 : query.getPageSize();
        Long keywordId = parseId(query.getKeyword());
        List<AdminActivityListRow> rows = activityMapper.selectAdminActivityPage(
                query.getPublishStatus(),
                query.getLifecycleStatus(),
                query.getKeyword(),
                keywordId,
                (page - 1) * pageSize,
                pageSize);
        List<AdminActivityListBO> items = rows.stream().map(this::toAdminListBO).toList();
        long totalItems = activityMapper.countAdminActivities(
                query.getPublishStatus(), query.getLifecycleStatus(), query.getKeyword(), keywordId);

        return AdminActivityPageBO.builder()
                .items(items)
                .page(page)
                .pageSize(pageSize)
                .totalItems(totalItems)
                .build();
    }

    @Override
    public AdminActivityDetailBO getAdminActivityDetail(Long id) {
        Activity activity = activityMapper.selectAdminActivityById(id);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        return AdminActivityDetailBO.builder()
                .contractVersion(activity.getContractVersion()).lifecycleStatus(activity.getLifecycleStatus()).formVersion(activity.getFormVersion())
                .formSchema(cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.parse(activity.getFormSchema()))
                .cancelledAt(activity.getCancelledAt()).cancelReason(activity.getCancelReason())
                .publishStatus(activity.getPublishStatus())
                .summary(activity.getSummary()).audienceSummary(activity.getAudienceSummary())
                .audienceDepartmentIds(activity.getAudienceDepartmentIds()).contactsJson(activity.getContactsJson())
                .registrationMode(activity.getRegistrationMode()).participantMode(activity.getParticipantMode())
                .capacityUnit(activity.getCapacityUnit()).coverAttachmentId(activity.getCoverAttachmentId())
                .sections(eventContentService.sections(TargetType.ACTIVITY, id))
                .actions(eventContentService.actions(TargetType.ACTIVITY, id))
                .id(activity.getId())
                .userId(activity.getUserId())
                .title(activity.getTitle())
                .location(activity.getLocation())
                .category(ActivityCategory.fromCode(activity.getCategory()))
                .organizer(activity.getOrganizer())
                .audienceScope(activity.getAudienceScope())
                .officialCapacity(activity.getCapacity())
                .commentCount(activity.getCommentCount())
                .likeCount(activity.getLikeCount())
                .viewCount(activity.getViewCount())
                .subscriberCount(activityEnrollmentService.countActiveSubscribers(id))
                .notifyEnabledSubscriberCount(activityEnrollmentService.countNotifyEnabledSubscribers(id))
                .publishedAt(activity.getPublishedAt())
                .createdAt(activity.getCreatedAt())
                .updatedAt(activity.getUpdatedAt())
                .attachments(mediaService.listAttachments(TargetType.ACTIVITY, id))
                .timeline(timelineService.listTimelinesByTarget(TargetType.ACTIVITY, id))
                .build();
    }

    @Override
    public AdminActivityDetailBO getAdminActivityDraft(Long id) {
        return getAdminActivityDetail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAdminActivity(AdminActivitySaveBO command) {
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
        boolean notifyActionableChange = publication(current) == 1
                && Integer.valueOf(0).equals(current.getLifecycleStatus());
        String previousLocation = current.getLocation();
        List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> previousTimeline = notifyActionableChange
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
                        .set(Activity::getAudienceScope, command.getAudienceScope())
                        .set(Activity::getAudienceDepartmentIds, command.getAudienceDepartmentIds())
                        .set(Activity::getContactsJson, command.getContactsJson())
                        .set(Activity::getPublishStatus, publication(current))
                        .setSql("contract_version = contract_version + 1")
                        .eq(Activity::getId, command.getId())
                        .eq(Activity::getPublishStatus, publication(current))
                        .isNull(Activity::getDeletedAt));
        if (updated != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动草稿更新失败");
        }
        saveAdminChildren(command.getId(), command);
        Activity saved = activityMapper.selectForUpdate(command.getId());
        if (publication(saved) == 1) {
            validateAdminSubmission(saved);
            initializeFormVersionIfAbsent(saved);
        }
        refreshActivityReminder(saved);
        if (notifyActionableChange) {
            enqueueActivityActionableChanges(saved, previousLocation, previousTimeline,
                    timelineService.listTimelinesByTarget(TargetType.ACTIVITY, saved.getId()));
        }
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(command.getId())));
        log.info("[管理端活动] 管理员 {} 更新草稿 {}", command.getOperatorId(), command.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminActivityDetailBO replaceAdminActivity(AdminActivitySaveBO command, String ifMatch) {
        if (command == null || command.getId() == null) throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID);
        Activity current = activityMapper.selectForUpdate(command.getId());
        if (current == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        StrongEtag.require(ifMatch, "activity", command.getId(), current.getContractVersion() == null ? 1L : current.getContractVersion());
        updateAdminActivity(command);
        return getAdminActivityDetail(command.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitAdminActivityReview(Long activityId, Long operatorId) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动暂不接入审核，请使用直接发布接口");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAdminActivity(Long activityId, Long operatorId, String reason) {
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
        notifyService.enqueueBusinessNotification(TargetType.ACTIVITY, activityId,
                "activity:" + activityId + ":end:v" + nextVersion(current), NotifyType.ACTIVITY_ENDED_EARLY,
                "SUBSCRIBERS_OR_REGISTERED_USERS", "活动已提前结束", noticeContent,
                subjectSnapshot(current, "活动已提前结束", noticeContent));
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
        log.info("[管理端活动] 管理员 {} 提前结束活动 {}", operatorId, activityId);
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
                        .set(Activity::getPublishStatus, 2)
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
        log.info("[管理端活动] 管理员 {} 删除草稿 {}", operatorId, id);
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
                || !StringUtils.hasText(activity.getSummary())
                || !StringUtils.hasText(activity.getOrganizer())
                || !StringUtils.hasText(activity.getAudienceSummary())
                || activity.getCategory() == null
                || activity.getAudienceScope() == null
                || activity.getParticipantMode() == null
                || !Set.of(1, 2).contains(activity.getParticipantMode())) {
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                    "publish-validation-failed", "发布所需的摘要、分类、主办方、参与范围或参与形式不完整");
        }
        List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> timeline =
                timelineService.listTimelinesByTarget(TargetType.ACTIVITY, activity.getId());
        if (activity.getRegistrationMode() == null || !Set.of(1, 2, 3, 4).contains(activity.getRegistrationMode()))
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                    "publish-validation-failed", "发布前请确认报名方式");
        if ((activity.getCapacity() == null) != (activity.getCapacityUnit() == null)
                || (activity.getCapacity() != null && activity.getCapacity() <= 0)
                || (activity.getCapacityUnit() != null && !Set.of(1, 2).contains(activity.getCapacityUnit())))
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                    "publish-validation-failed", "capacity 与 capacityUnit 必须成对且合法");
        if (activity.getCapacityUnit() != null
                && ((Integer.valueOf(1).equals(activity.getParticipantMode()) && !Integer.valueOf(1).equals(activity.getCapacityUnit()))
                || (Integer.valueOf(2).equals(activity.getParticipantMode()) && !Integer.valueOf(2).equals(activity.getCapacityUnit()))))
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                    "publish-validation-failed", "参与形式与容量单位不一致");
        if (activity.getRegistrationMode() == 1
                && activity.getFormSchema() != null)
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                    "publish-validation-failed", "无需报名时不能配置报名窗口或平台表单");
        if (activity.getRegistrationMode() == 3) {
            if (activity.getFormSchema() != null || eventContentService.actions(TargetType.ACTIVITY, activity.getId()).stream()
                    .noneMatch(a -> Integer.valueOf(7).equals(a.getActionType()) && StringUtils.hasText(a.getTargetValue())))
                throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                        "publish-validation-failed", "纯外部报名须提供外部报名入口且不能配置平台表单");
        }
        if (Set.of(2, 4).contains(activity.getRegistrationMode())) {
            activityFormAvailability.requireEnabled();
            cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.schema(
                    cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.parse(activity.getFormSchema()));
            var window = participationPolicy.window(timeline);
            cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.require(Integer.valueOf(1).equals(activity.getParticipantMode())
                            && window.platformValid(),
                    "平台表单仅支持个人报名；timeline须有唯一EXACT_POINT截止节点且至多一个EXACT_POINT开始节点");
            if (activity.getRegistrationMode() == 4)
                cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.require(eventContentService.actions(TargetType.ACTIVITY, activity.getId()).stream()
                                .anyMatch(a -> Integer.valueOf(7).equals(a.getActionType()) && StringUtils.hasText(a.getTargetValue())),
                        "组合报名须提供外部报名入口");
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
                .publishStatus(row.getPublishStatus())
                .lifecycleStatus(row.getLifecycleStatus())
                .organizer(row.getOrganizer())
                .location(row.getLocation())
                .audienceScope(row.getAudienceScope())
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
                .title(command.getTitle())
                .summary(command.getSummary()).audienceSummary(command.getAudienceSummary())
                .location(command.getLocation())
                .category(command.getCategory() == null ? null : command.getCategory().getCode())
                .organizer(command.getOrganizer())
                .audienceScope(command.getAudienceScope())
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
        Integer unit = command.getCapacityUnit() == null ? (capacity == null ? null : 1) : command.getCapacityUnit();
        if ((capacity == null) != (unit == null))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量与单位必须同时填写");

        int rows = activityMapper.update(null, new LambdaUpdateWrapper<Activity>()
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
        if (command.getParticipantMode() != null && (command.getParticipantMode() < 0 || command.getParticipantMode() > 3))
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "参与形式不合法");
        if (command.getCapacity() != null && command.getCapacity() <= 0)
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量必须大于0");
        if (command.getCapacityUnit() != null && command.getCapacityUnit() != 1 && command.getCapacityUnit() != 2)
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "容量单位不合法");
        if (command.getAudienceScope() != null && (command.getAudienceScope() & 1) != 0 && command.getAudienceScope() != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "全院与具体学科部不能同时选择");
        }
        for (var item : safeList(command.getTimelineItems())) {
            EventTimePolicy.range(item.getStartTime(), item.getEndTime(), item.getEndPrecision());
        }
    }

    private void assertAdminEditable(Activity activity) {
        if (activity == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        if (activity.getDeletedAt() != null)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "已删除活动不可编辑");
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
     * @param detailBO   活动详情业务对象
     * @param activityId 活动ID，用于查询报名状态
     */
    private void enrichUserState(ActivityDetailBO detailBO, Long activityId) {
        detailBO.setLiked(interactService.isLiked(TargetType.ACTIVITY, activityId));
        detailBO.setEnrolled(activityEnrollmentService.isEnrolled(activityId));
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishAdminActivity(Long activityId, Long operatorId) {
        validateAdminAction(activityId, operatorId, null, false);
        Activity current = activityMapper.selectForUpdate(activityId);
        assertAdminEditable(current);
        if (publication(current) == 1) return;
        validateAdminSubmission(current);
        if (activityMapper.publishDirectly(activityId, initialFormVersionFor(current)) != 1)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动发布失败");
        refreshActivityReminder(activityMapper.selectForUpdate(activityId));
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
        log.info("[活动下架] id={}, operatorId={}", activityId, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminActivityDetailBO transitionAdminActivity(Long activityId, Long operatorId, String ifMatch, String action) {
        validateAdminAction(activityId, operatorId, null, false);
        Activity current = activityMapper.selectForUpdate(activityId);
        if (current == null) throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        long version = current.getContractVersion() == null ? 1L : current.getContractVersion();
        StrongEtag.require(ifMatch, "activity", activityId, version);
        int publication = publication(current);
        int lifecycle = current.getLifecycleStatus() == null ? 0 : current.getLifecycleStatus();
        int nextPublication = publication;
        int nextLifecycle = lifecycle;
        boolean publishing = false;
        switch (action) {
            case "publish" -> {
                if (publication == 1) return getAdminActivityDetail(activityId);
                validateAdminSubmission(current);
                nextPublication = 1;
                publishing = true;
            }
            case "unpublish" -> {
                if (publication == 2) return getAdminActivityDetail(activityId);
                if (publication != 1)
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("state-conflict", "Only published content can be unpublished");
                nextPublication = 2;
            }
            case "cancel" -> {
                if (lifecycle == 2) return getAdminActivityDetail(activityId);
                if (publication != 1 || lifecycle != 0)
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("state-conflict", "Only a published active activity can be cancelled");
                nextPublication = 1;
                nextLifecycle = 2;
            }
            case "end" -> {
                if (lifecycle == 1) return getAdminActivityDetail(activityId);
                if (publication != 1 || lifecycle != 0)
                    throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("state-conflict", "Only a published active activity can be ended");
                nextLifecycle = 1;
            }
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "Unknown lifecycle action");
        }
        String formVersion = publishing ? initialFormVersionFor(current) : null;
        int updated = activityMapper.transitionContract(activityId, version, nextPublication, nextLifecycle,
                publishing, formVersion);
        if (updated != 1) {
            throw cn.jualn.miniapp.common.exception.ContractProblemException.preconditionFailed(
                    "activity-transition update-count=" + updated + ", id=" + activityId + ", version=" + version
                            + ", action=" + action);
        }
        notifyService.cancelActivityPlans(activityId);
        Activity changed = activityMapper.selectForUpdate(activityId);
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
        return getAdminActivityDetail(changed.getId());
    }

    private void initializeFormVersionIfAbsent(Activity activity) {
        String formVersion = initialFormVersionFor(activity);
        if (formVersion == null || activity.getFormVersion() != null) return;
        if (activityMapper.initializeFormVersionIfAbsent(activity.getId(), formVersion) != 1)
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动表单版本初始化失败");
        activity.setFormVersion(formVersion);
    }

    private String initialFormVersionFor(Activity activity) {
        return activity != null && participationPolicy.platform(activity.getRegistrationMode())
                ? "activity-" + activity.getId() + "-form-v1" : null;
    }

    private void refreshActivityReminder(Activity activity) {
        LocalDateTime now = LocalDateTime.now();
        List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> timeline =
                timelineService.listTimelinesByTarget(TargetType.ACTIVITY, activity.getId());
        notifyService.reconcileReminders(TargetType.ACTIVITY, activity.getId(),
                activity.getContractVersion() == null ? 0L : activity.getContractVersion(),
                REMINDER_POLICY.evaluate(activity, timeline, now), now);
    }

    private void enqueueActivityActionableChanges(Activity saved, String previousLocation,
            List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> previousTimeline,
            List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> currentTimeline) {
        long version = saved.getContractVersion() == null ? 1L : saved.getContractVersion();
        java.util.Set<String> timeSemantics = java.util.Set.of("ACTIVITY_START", "REGISTRATION_END");
        if (cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.exactTimeChanged(
                previousTimeline, currentTimeline, timeSemantics)) {
            notifyService.enqueueBusinessNotification(TargetType.ACTIVITY, saved.getId(),
                    "activity:" + saved.getId() + ":time:v" + version, NotifyType.ACTIVITY_TIME_CHANGED,
                    "SUBSCRIBERS_OR_REGISTERED_USERS", "活动时间已变更",
                    "活动「" + saved.getTitle() + "」的行动时间已变更",
                    changeSnapshot(saved, "活动时间已变更", cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector
                            .timeChanges(previousTimeline, currentTimeline, timeSemantics)));
        }
        boolean locationChanged = cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector
                .effectiveValueChanged(previousLocation, saved.getLocation())
                || cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector
                .effectiveLocationChanged(previousTimeline, currentTimeline, java.util.Set.of("ACTIVITY_START"));
        if (locationChanged) {
            notifyService.enqueueBusinessNotification(TargetType.ACTIVITY, saved.getId(),
                    "activity:" + saved.getId() + ":location:v" + version,
                    NotifyType.ACTIVITY_LOCATION_CHANGED, "SUBSCRIBERS_OR_REGISTERED_USERS", "活动地点已变更",
                    "活动「" + saved.getTitle() + "」的行动地点已变更",
                    changeSnapshot(saved, "活动地点已变更", activityLocationChanges(previousLocation, saved.getLocation(), previousTimeline, currentTimeline)));
        }
    }

    private List<cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.Change> activityLocationChanges(
            String before, String after, List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> previous,
            List<cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO> current) {
        var changes = new java.util.ArrayList<>(cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector
                .locationChanges(previous, current, java.util.Set.of("ACTIVITY_START")));
        if (cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.effectiveValueChanged(before, after)) {
            changes.add(new cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.Change("活动地点", before.trim(), after.trim()));
        }
        return List.copyOf(changes);
    }

    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot subjectSnapshot(Activity activity, String title, String body) {
        String id = activity.getId().toString();
        return new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot(
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation(title, body, null, null, null, activity.getTitle(), null),
                null, new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject("ACTIVITY", id),
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("ACTIVITY_DETAIL", null, null, id, null));
    }

    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot changeSnapshot(Activity activity, String title,
            List<cn.jualn.miniapp.module.timeline.service.ActionableTimelineChangeDetector.Change> changes) {
        String id = activity.getId().toString();
        return new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot(
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation(title, null, activity.getTitle(), null,
                        changes.stream().map(change -> new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Change(
                                change.label(), change.before(), change.after())).toList(), activity.getTitle(), null), null,
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject("ACTIVITY", id),
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("ACTIVITY_DETAIL", null, null, id, null));
    }

    private int publication(Activity activity) {
        return activity.getPublishStatus() == null ? 0 : activity.getPublishStatus();
    }

    private long nextVersion(Activity activity) {
        return (activity.getContractVersion() == null ? 1L : activity.getContractVersion()) + 1L;
    }

}
