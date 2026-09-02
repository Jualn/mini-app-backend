package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.mapper.AdminActivityListRow;
import cn.jualn.miniapp.module.activity.mapper.AdminActivitySummaryRow;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailVO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.payload.AuditMediaBatchPayload;
import cn.jualn.miniapp.module.audit.payload.AuditTextPayload;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final AuditReservationService auditReservationService;
    private final QueueProducer queueProducer;

    /**
     * 创建活动。
     *
     * <p>该方法在事务内执行，确保活动主数据与媒体附件关联的一致性。过程包括：</p>
     * <ol>
     *   <li>获取当前用户ID（通过UserContext ThreadLocal）</li>
     *   <li>将BO对象转换为Entity并设置用户ID</li>
     *   <li>保存活动到数据库，由数据库自动生成ID</li>
     *   <li>如果提供了媒体列表，则调用媒体服务关联附件</li>
     * </ol>
     *
     * <p><b>事务控制</b>：任何异常都会导致整个操作回滚，包括已插入的活动和媒体附件。</p>
     *
     * @param command 活动创建参数对象，已由Controller层@Valid注解校验过合法性
     * @return 新创建活动的数据库自增ID
     * @throws BusinessException 当用户未登录时抛出 {@link ResultCode#UNAUTHORIZED}
     * @throws BusinessException 当媒体服务操作失败时抛出相应异常
     * @see ActivityConverter#toEntity(ActivityCreateBO)
     * @see MediaService#replaceAttachments(MediaAttachmentSaveBO)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createActivity(ActivityCreateBO command) {
        Long userId = requireUserId();
        userService.assertContentCreationAllowed(userId);

        Activity activity = activityConverter.toEntity(command);
        activity.setUserId(userId);
        // 直接设置状态为通过，确保searchService能及时搜索到新活动；如果需要审核流程，可以改为PENDING，等待审核回调修改状态
        // DRAFT 暂时代表默认
        activity.setStatus(ActivityStatus.DRAFT.getCode());
        activity.setPublishedAt(LocalDateTime.now());

        activityMapper.insert(activity);

        if (!CollectionUtils.isEmpty(command.getTimelineItems())) {
            timelineService.replaceTimelines(
                    TimelineSaveBO.builder()
                            .targetType(TargetType.ACTIVITY)
                            .targetId(activity.getId())
                            .timelines(command.getTimelineItems())
                            .build()
            );
        }

        if (!CollectionUtils.isEmpty(command.getAttachmentItems())) {
            mediaService.replaceAttachments(
                    MediaAttachmentSaveBO.builder()
                            .targetId(activity.getId())
                            .targetType(TargetType.ACTIVITY)
                            .attachments(command.getAttachmentItems())
                            .build()
            );
        }

        // 原本是交给审核后提交，但目前不需要审核，直接提交
        // 事务提交后同步搜索索引，确保搜索服务看到的活动数据是最终一致的状态
//        afterCommit(() -> searchService.syncActivity(activityConverter.toSearchBO(activity)));
        log.info("[活动] 用户 {} 创建活动 {}", userId, activity.getId());
        return activity.getId();
    }

    /**
     * 更新活动。
     *
     * <p>该方法支持部分更新（只更新提供的字段）。过程包括：</p>
     * <ol>
     *   <li>获取当前用户ID，一次查询同时验证活动存在性和所有权</li>
     *   <li>应用更新的字段到Entity对象</li>
     *   <li>设置审核状态为待审核（PENDING）</li>
     *   <li>保存更新后的Entity到数据库</li>
     *   <li>如果提供了新的媒体列表，则替换所有关联的附件</li>
     *   <li>清空活动详情缓存，保证用户立即看到最新数据</li>
     * </ol>
     *
     * <p><b>权限验证</b>：只有活动创建者可以编辑。使用{@code selectByIdAndUserId}一次查询同时完成
     * 活动获取和权限验证，避免N+1问题。</p>
     *
     * <p><b>事务控制</b>：任何异常都会导致整个操作回滚，缓存清空操作不会执行。</p>
     *
     * @param command 活动更新参数对象，包含要更新的字段（null字段不更新）和活动ID
     * @throws BusinessException 当用户未登录时抛出 {@link ResultCode#UNAUTHORIZED}
     * @throws BusinessException 当活动不存在或用户无权编辑时抛出 {@link ResultCode#NOT_FOUND}
     * @throws BusinessException 当媒体服务操作失败时抛出相应异常
     * @see ActivityMapper#selectByIdAndUserId(Long, Long)
     * @see ActivityConverter#updateEntityFromUpdateBO(Activity, ActivityUpdateBO)
     * @see MediaService#replaceAttachments(MediaAttachmentSaveBO)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateActivity(ActivityUpdateBO command) {
        Long userId = requireUserId();
        userService.assertContentCreationAllowed(userId);
        // 一次查询同时获取活动和验证权限，避免N+1查询
        UserAuthBO currentUser = userService.getUserAuthInfo(userId);

        Activity activity = activityMapper.selectOne(
                new LambdaQueryWrapper<Activity>()
                        .select(Activity::getId, Activity::getUserId)
                        .eq(Activity::getId, command.getId())
        );

        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND, "活动不存在或无权编辑");
        }

        if (currentUser.getRole() == UserRole.USER && !userId.equals(activity.getUserId())) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND, "活动不存在或无权编辑");
        }

        activityConverter.updateEntityFromUpdateBO(activity, command);

        activityMapper.updateById(activity);

        if (command.getAttachmentItems() != null) {
            mediaService.replaceAttachments(
                    MediaAttachmentSaveBO.builder()
                            .targetId(activity.getId())
                            .targetType(TargetType.ACTIVITY)
                            .attachments(command.getAttachmentItems())
                            .build()
            );
        }

        if (command.getTimelineItems() != null) {
            timelineService.replaceTimelines(
                    TimelineSaveBO.builder()
                            .targetType(TargetType.ACTIVITY)
                            .targetId(activity.getId())
                            .timelines(command.getTimelineItems())
                            .build()
            );
        }

        // 清空活动详情缓存，保证用户立即看到最新数据
        redisService.delete(RedisKeyConstant.activityDetail(activity.getId()));
        log.info("[活动] 用户 {} 更新活动 {}", userId, activity.getId());
    }

    /**
     * 删除活动（软删除）。
     *
     * <p>该方法执行软删除（逻辑删除），不真正移除数据库记录。过程包括：</p>
     * <ol>
     *   <li>获取当前用户ID，一次查询同时验证活动存在性和所有权</li>
     *   <li>设置活动状态为DELETED，设置deletedAt为当前时间</li>
     *   <li>保存更新到数据库</li>
     *   <li>清空所有相关缓存，包括详情页缓存和存在性缓存</li>
     * </ol>
     *
     * <p><b>权限验证</b>：只有活动创建者可以删除。使用{@code selectByIdAndUserId}一次查询同时完成
     * 活动获取和权限验证。</p>
     *
     * <p><b>缓存清空</b>：需要同时清空详情缓存和存在性缓存，因为存在性缓存可能记录过此活动的信息。</p>
     *
     * <p><b>事务控制</b>：任何异常都会导致操作回滚，缓存清空操作不会执行。</p>
     *
     * @param id 包含活动ID的参数对象
     * @throws BusinessException 当用户未登录时抛出 {@link ResultCode#UNAUTHORIZED}
     * @throws BusinessException 当活动不存在或用户无权删除时抛出 {@link ResultCode#NOT_FOUND}
     * @see ActivityMapper#selectByIdAndUserId(Long, Long)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeActivity(Long id) {
        Long userId = requireUserId();
        // 一次查询同时获取活动和验证权限
        UserAuthBO currentUser = userService.getUserAuthInfo(userId);

        Activity activity = activityMapper.selectOne(
                new LambdaQueryWrapper<Activity>()
                        .select(Activity::getUserId)
                        .eq(Activity::getId, id)
        );

        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND, "活动不存在");
        }

        if (currentUser.getRole() == UserRole.USER && !userId.equals(activity.getUserId())) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND, "活动不存在或无权删除");
        }

        activity.setId(id);
        activity.setStatus(ActivityStatus.DELETED.getCode());
        activity.setDeletedAt(LocalDateTime.now());
        activityMapper.updateById(activity);

        // 作废该活动的所有通知计划
        notifyService.cancelActivityPlans(id);

        // 清空所有活动相关缓存
        redisService.delete(RedisKeyConstant.activityDetail(activity.getId()));
        redisService.delete(RedisKeyConstant.targetExists(TargetType.ACTIVITY.getKey(), id));
//        afterCommit(() -> searchService.removeByTarget(TargetType.ACTIVITY, id));
        log.info("[活动] 用户 {} 删除活动 {}", userId, id);
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
        String cacheKey = RedisKeyConstant.activityDetail(id);

        // 1. 查缓存
        ActivityDetailBO cached = redisService.get(cacheKey, ActivityDetailBO.class);

        ActivityDetailBO detailBO;

        if (cached != null) {
            detailBO = cached;
        } else {
            // 2. 查数据库
            Activity activity = getVisibleActivityOrThrow(id);

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
        Integer status = command.getStatus() == null ? ActivityStatus.DRAFT.getCode() : command.getStatus();

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
        if (detail.getStatus() != ActivityStatus.DRAFT && detail.getStatus() != ActivityStatus.REJECTED) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED,
                    "只有草稿或审核拒绝的活动可以继续编辑");
        }
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAdminActivity(AdminActivitySaveBO command) {
        validateAdminSave(command);
        Activity activity = toAdminEntity(command);
        activity.setUserId(command.getOperatorId());
        activity.setStatus(ActivityStatus.DRAFT.getCode());
        activity.setAuditStatus(0);
        activity.setIsPinned(Boolean.FALSE);
        activity.setCommentCount(0);
        activity.setLikeCount(0);
        activity.setViewCount(0);
        if (activityMapper.insert(activity) != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动草稿创建失败");
        }
        saveAdminChildren(activity.getId(), command, false);
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
        Activity current = activityMapper.selectAdminActivityById(command.getId());
        assertAdminEditable(current);

        int updated = activityMapper.update(null,
                new LambdaUpdateWrapper<Activity>()
                        .set(Activity::getTitle, command.getTitle())
                        .set(Activity::getContent, command.getContent())
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
                        .set(Activity::getStatus, ActivityStatus.DRAFT.getCode())
                        .set(Activity::getAuditStatus, 0)
                        .set(Activity::getRejectReason, null)
                        .eq(Activity::getId, command.getId())
                        .in(Activity::getStatus, ActivityStatus.DRAFT.getCode(), ActivityStatus.REJECTED.getCode())
                        .isNull(Activity::getDeletedAt));
        if (updated != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "活动草稿更新失败");
        }
        saveAdminChildren(command.getId(), command, true);
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(command.getId())));
        log.info("[管理端活动] 管理员 {} 更新草稿 {}", command.getOperatorId(), command.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitAdminActivityReview(Long activityId, Long operatorId) {
        validateAdminAction(activityId, operatorId, null, false);
        Activity current = activityMapper.selectAdminActivityById(activityId);
        assertAdminEditable(current);
        validateAdminSubmission(current);
        String auditText = String.join("\n", current.getTitle(), current.getOrganizer(), current.getContent());
        List<MediaAttachmentBO> attachments = mediaService.listAttachments(TargetType.ACTIVITY, activityId);
        AuditReserveResultBO reserveResult = auditReservationService.reserveAuditLogs(AuditReserveBO.builder()
                .auditScene(AuditScene.ACTIVITY)
                .targetId(activityId)
                .textContent(auditText)
                .mediaItems(buildActivityAuditMediaItems(attachments))
                .build());
        if (activityMapper.submitAdminReview(activityId) != 1) {
            throwAdminActivityConflict(activityId, "活动当前状态不允许提交审核");
        }
        if (reserveResult == null || !reserveResult.hasAuditTask()) {
            if (activityMapper.approveAdminReview(activityId) != 1) {
                throwAdminActivityConflict(activityId, "活动自动发布失败");
            }
        }
        afterCommit(() -> {
            redisService.delete(RedisKeyConstant.activityDetail(activityId));
            enqueueActivityAudit(activityId, auditText, reserveResult);
        });
        log.info("[管理端活动] 管理员 {} 提交活动 {} 审核", operatorId, activityId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAdminActivityPinned(Long activityId, Long operatorId, boolean pinned) {
        validateAdminAction(activityId, operatorId, null, false);
        Activity current = activityMapper.selectAdminActivityById(activityId);
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
        Activity current = activityMapper.selectAdminActivityById(activityId);
        if (current == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        if (activityMapper.cancelAdminActivity(activityId) != 1) {
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
        Activity current = activityMapper.selectAdminActivityById(activityId);
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
        Activity current = activityMapper.selectAdminActivityById(id);
        assertAdminEditable(current);
        int updated = activityMapper.update(null,
                new LambdaUpdateWrapper<Activity>()
                        .set(Activity::getStatus, ActivityStatus.DELETED.getCode())
                        .set(Activity::getIsPinned, Boolean.FALSE)
                        .set(Activity::getDeletedAt, LocalDateTime.now())
                        .eq(Activity::getId, id)
                        .in(Activity::getStatus, ActivityStatus.DRAFT.getCode(), ActivityStatus.REJECTED.getCode())
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
        validateActivityReview(activityId, operatorId, false, remark);
        if (activityMapper.approveAdminReview(activityId) != 1) {
            throwActivityReviewConflict(activityId, "活动当前状态不允许通过复核");
        }
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
        log.info("[ActivityService.approveActivityReview][完成] operatorId={}, activityId={}, remark={}",
                operatorId, activityId, remark);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectActivityReview(Long activityId, Long operatorId, String reason) {
        validateActivityReview(activityId, operatorId, true, reason);
        if (activityMapper.rejectAdminReview(activityId, reason.trim()) != 1) {
            throwActivityReviewConflict(activityId, "活动当前状态不允许拒绝复核");
        }
        afterCommit(() -> redisService.delete(RedisKeyConstant.activityDetail(activityId)));
        log.info("[ActivityService.rejectActivityReview][完成] operatorId={}, activityId={}, reason={}",
                operatorId, activityId, reason);
    }

    private void validateActivityReview(Long activityId, Long operatorId, boolean reasonRequired, String reason) {
        if (activityId == null || operatorId == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "人工复核参数不完整");
        }
        if (reasonRequired && (reason == null || reason.isBlank())) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "拒绝原因不能为空");
        }
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
                || !StringUtils.hasText(activity.getLocation())
                || !StringUtils.hasText(activity.getOrganizer())
                || activity.getCategory() == null
                || activity.getAudienceScope() == null
                || activity.getAudienceScope() == 0
                || activity.getStartTime() == null
                || activity.getEndTime() == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动必填信息未填写完整");
        }
        if (!activity.getEndTime().isAfter(activity.getStartTime())) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动结束时间必须晚于开始时间");
        }
        if (activity.getEnrollDeadline() != null
                && activity.getEnrollDeadline().isAfter(activity.getStartTime())) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "报名截止时间不能晚于活动开始时间");
        }
    }

    private List<AuditReserveBO.MediaItem> buildActivityAuditMediaItems(List<MediaAttachmentBO> attachments) {
        return safeList(attachments).stream()
                .filter(item -> item != null && StringUtils.hasText(item.getUrl()) && item.getType() != null)
                .map(item -> AuditReserveBO.MediaItem.builder()
                        .mediaType(item.getType())
                        .mediaUrl(item.getUrl())
                        .build())
                .toList();
    }

    private void enqueueActivityAudit(
            Long activityId,
            String content,
            AuditReserveResultBO reserveResult) {
        if (reserveResult == null || !reserveResult.hasAuditTask()) {
            return;
        }
        List<AuditMediaBatchPayload.AuditMediaItem> mediaItems = safeList(reserveResult.getMediaItems()).stream()
                .filter(item -> item != null && item.getAuditLogId() != null)
                .map(item -> AuditMediaBatchPayload.AuditMediaItem.builder()
                        .auditLogId(item.getAuditLogId())
                        .mediaType(item.getMediaType())
                        .mediaUrl(item.getMediaUrl())
                        .build())
                .toList();
        if (!mediaItems.isEmpty()) {
            queueProducer.send(AuditMediaBatchPayload.builder()
                    .auditScene(AuditScene.ACTIVITY)
                    .targetId(activityId)
                    .scene(3)
                    .items(mediaItems)
                    .build());
        }
        if (reserveResult.getTextAuditLogId() != null) {
            queueProducer.send(AuditTextPayload.builder()
                    .auditLogId(reserveResult.getTextAuditLogId())
                    .auditScene(AuditScene.ACTIVITY)
                    .targetId(activityId)
                    .content(content)
                    .scene(3)
                    .build());
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
                .title(command.getTitle())
                .content(command.getContent())
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

    private void saveAdminChildren(Long activityId, AdminActivitySaveBO command, boolean replaceEmpty) {
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
    }

    private void validateAdminSave(AdminActivitySaveBO command) {
        if (command == null || command.getOperatorId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (command.getCategory() == null || command.getAudienceScope() == null || command.getAudienceScope() == 0) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID);
        }
        if ((command.getAudienceScope() & 1) != 0 && command.getAudienceScope() != 1) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "全院与具体学科部不能同时选择");
        }
        if (!command.getEndTime().isAfter(command.getStartTime())) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动结束时间必须晚于开始时间");
        }
        if (command.getEnrollDeadline() != null && command.getEnrollDeadline().isAfter(command.getStartTime())) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "报名截止时间不能晚于活动开始时间");
        }
        boolean hasContactName = command.getContactName() != null;
        boolean hasContactPhone = command.getContactPhone() != null;
        if (hasContactName != hasContactPhone) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "联系人姓名和电话必须同时填写");
        }
        for (var item : safeList(command.getTimelineItems())) {
            if (item.getStartTime() != null && item.getEndTime() != null
                    && item.getEndTime().isBefore(item.getStartTime())) {
                throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "时间线结束时间不能早于开始时间");
            }
        }
    }

    private void assertAdminEditable(Activity activity) {
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        if (activity.getStatus() == null
                || (activity.getStatus() != ActivityStatus.DRAFT.getCode()
                && activity.getStatus() != ActivityStatus.REJECTED.getCode())) {
            throw new BusinessException(ResultCode.ACTIVITY_OPERATION_NOT_ALLOWED, "只有草稿或审核拒绝的活动可以编辑或删除");
        }
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
    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

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
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
