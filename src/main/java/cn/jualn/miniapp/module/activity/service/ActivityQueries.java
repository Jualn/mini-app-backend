package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.eventcontent.service.EventContactCodec;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import cn.jualn.miniapp.module.timeline.service.CardTimelinePolicy;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/** 公开活动查询与当前参与状态快照。 */
@Service
@RequiredArgsConstructor
public class ActivityQueries {
    private final EventContentService eventContentService;
    private final ActivityMapper activityMapper;
    private final ActivityConverter activityConverter;
    private final ActivityEnrollmentService activityEnrollmentService;
    private final cn.jualn.miniapp.module.media.service.MediaQueries mediaQueries;
    private final UserService userService;
    private final TimelineService timelineService;
    private final RedisService redisService;
    private final InteractionLikeQueries interactService;
    private final EventContactCodec eventContactCodec;
    private final ActivityParticipationPolicy participationPolicy;
    private final ActivityRegistrationService activityRegistrationService;

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

    /** Canonical detail read with current visibility, content and participation facts. */
    public ActivityDetailBO getActivityResource(Long id) {
        Activity activity = getVisibleActivityOrThrow(id);
        ActivityDetailBO result = activityConverter.toDetailBO(activity);
        result.setAttachmentItems(mediaQueries.listAttachments(TargetType.ACTIVITY, id));
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
        result.setAllowRegistrationAfterStart(activity.getAllowRegistrationAfterStart());
        result.setStageState(ActivityStagePolicy.state(activity.getLifecycleStatus(), result.getTimelineItems(), now));
        result.setEvaluatedAt(now);
        result.setParticipationState(participationPolicy.evaluate(result.getPublishStatus(), result.getLifecycleStatus(),
                result.getRegistrationMode(), result.getTimelineItems(), result.getCapacity(), result.getSubmittedCount(), now, Boolean.TRUE.equals(activity.getAllowRegistrationAfterStart())));
        return result;
    }

    public ActivityResourcePageBO pageActivityResources(ActivityPageBO query) {
        query.setAudienceUserId(UserContext.getUserId());
        query.setLastId(ActivityPublicCursorCodec.decode(query));
        PageResult<ActivityListBO> page = pageActivityList(query);
        var coverIds = page.getList().stream().map(ActivityListBO::getCoverAttachmentId)
                .filter(java.util.Objects::nonNull).toList();
        Map<Long, MediaAttachmentBO> covers = mediaQueries.batchGetAttachments(coverIds);
        var ids = page.getList().stream().filter(item -> participationPolicy.platform(item.getRegistrationMode()))
                .map(ActivityListBO::getId).toList();
        Map<Long, Long> counts = activityRegistrationService.countSubmittedByActivityIds(ids);
        Map<Long, List<cn.jualn.miniapp.module.timeline.bo.TimelineFactBO>> timelines =
                timelineService.listTimelinesByTargets(TargetType.ACTIVITY,
                        page.getList().stream().map(ActivityListBO::getId).toList());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        for (ActivityListBO item : page.getList()) {
            MediaAttachmentBO cover = item.getCoverAttachmentId() == null ? null : covers.get(item.getCoverAttachmentId());
            if (cover != null && Integer.valueOf(TargetType.ACTIVITY.getCode()).equals(cover.getTargetType())
                    && item.getId().equals(cover.getTargetId())) {
                item.setCoverAttachment(cover);
            }
            item.setStageState(ActivityStagePolicy.state(item.getLifecycleStatus(), timelines.getOrDefault(item.getId(), List.of()), now));
            item.setEvaluatedAt(now);
            item.setCardTimeline(CardTimelinePolicy.select(
                    timelines.getOrDefault(item.getId(), List.of()), now));
            if (participationPolicy.platform(item.getRegistrationMode())) {
                item.setSubmittedCount(counts.getOrDefault(item.getId(), 0L));
            }
            item.setParticipationState(participationPolicy.evaluate(item.getPublishStatus(), item.getLifecycleStatus(),
                    item.getRegistrationMode(), timelines.getOrDefault(item.getId(), List.of()),
                    item.getCapacity(), item.getSubmittedCount(), now, Boolean.TRUE.equals(item.getAllowRegistrationAfterStart())));
        }
        String nextCursor = Boolean.TRUE.equals(page.getHasMore())
                ? ActivityPublicCursorCodec.encode(query, page.getNextCursor()) : null;
        return new ActivityResourcePageBO(page.getList(), nextCursor);
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
        cn.jualn.miniapp.module.activity.service.ActivityPublicAccess.requirePublished(activity);
        return activity;
    }

    public record PublicCard(long id, String title, Long coverAttachmentId) {}
    public record PublicOptions(List<PublicCard> items, long totalItems) {}
    public record StageCard(PublicCard card, String state, TimelineFactBO main) {}

    public PublicCard publicCard(long id, boolean discovery, boolean lock) {
        var rows = activityMapper.publicOptions(id, null, discovery, 0, 1, lock);
        return rows.isEmpty() ? null : publicCard(rows.get(0));
    }

    public PublicOptions publicOptions(Long id, String titleQuery, long offset, int limit) {
        long total = activityMapper.countPublicOptions(id, titleQuery, false);
        var rows = offset >= total ? List.<Activity>of()
                : activityMapper.publicOptions(id, titleQuery, false, offset, limit, false);
        return new PublicOptions(rows.stream().map(ActivityQueries::publicCard).toList(), total);
    }

    public List<StageCard> upcomingPublicCards(LocalDateTime now, int limit) {
        Comparator<StageCard> order = Comparator.comparing((StageCard row) -> !"ONGOING".equals(row.state()))
                .thenComparing((first, second) -> {
                    int byStart = first.main().getStartTime().compareTo(second.main().getStartTime());
                    return "ONGOING".equals(first.state()) ? -byStart : byStart;
                })
                .thenComparingLong(row -> row.card().id());
        PriorityQueue<StageCard> best = new PriorityQueue<>(order.reversed());
        long afterId = 0;
        int batchSize = 100;
        List<Activity> rows;
        do {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            rows = activityMapper.publicStageCandidates(afterId, batchSize);
            var timelines = timelineService.listTimelinesByTargets(TargetType.ACTIVITY,
                    rows.stream().map(Activity::getId).toList());
            for (var row : rows) {
                afterId = row.getId();
                var timeline = timelines.getOrDefault(row.getId(), List.of());
                var main = ActivityStagePolicy.main(timeline);
                if (main == null || !Integer.valueOf(2).equals(main.getStartPrecision())) continue;
                String state = ActivityStagePolicy.state(row.getLifecycleStatus(), timeline, now);
                if (!"ONGOING".equals(state) && !"NOT_STARTED".equals(state)) continue;
                best.add(new StageCard(publicCard(row), state, main));
                if (best.size() > limit) best.remove();
            }
        } while (rows.size() == batchSize);
        return best.stream().sorted(order).toList();
    }

    private static PublicCard publicCard(Activity row) {
        return new PublicCard(row.getId(), row.getTitle(), row.getCoverAttachmentId());
    }

    private List<MediaAttachmentBO> loadMediaList(Long activityId) {
        return mediaQueries.listAttachments(TargetType.ACTIVITY, activityId);
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

    private PageResult<ActivityListBO> pageActivityList(ActivityPageBO command) {
        int pageSize = command.getPageSize();

        // 复杂动态SQL下沉到Mapper XML，Service层只做业务编排
        List<Activity> activities = activityMapper.selectPageActivities(
                command.getCategory(),
                command.getLifecycleStatus(),
                command.getKeyword(),
                command.getLastId(),
                command.isCampusAudienceOnly(),
                command.getDepartmentId(),
                pageSize + 1
        );
        boolean hasMore = activities.size() > pageSize;
        if (hasMore) {
            activities = activities.subList(0, pageSize);
        }

        List<ActivityListBO> list = activityConverter.toListBOList(activities);
        for (int index = 0; index < list.size(); index++) {
            ActivityListBO item = list.get(index);
            item.setAllowRegistrationAfterStart(activities.get(index).getAllowRegistrationAfterStart());
            item.setRegistrationMode(activities.get(index).getRegistrationMode());
            item.setParticipantMode(activities.get(index).getParticipantMode());
        }

        return PageResult.of(list, hasMore, list.isEmpty() ? null : list.get(list.size() - 1).getId());
    }
}
