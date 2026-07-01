package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.interact.bo.UserLikeBO;
import cn.jualn.miniapp.module.interact.dto.inner.InteractCountDTO;
import cn.jualn.miniapp.module.interact.dto.inner.UserLikeQuery;
import cn.jualn.miniapp.module.interact.entity.LikeRecord;
import cn.jualn.miniapp.module.interact.entity.ShareRecord;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ShareRecordMapper;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 互动服务实现类。
 *
 * <p>负责处理用户与内容之间的全部互动行为：点赞/取消点赞、分享、浏览。</p>
 *
 * <h2>计数器设计</h2>
 * <pre>
 *   likeCount / viewCount：Redis 是主来源（永久 key，无 TTL），
 *   由定时任务每5分钟批量刷回 DB 冗余字段（最终一致）。
 *   互动操作直接 incr/decr Redis，不调用各业务 service 的 increaseLikeCount。
 *
 *   shareCount：极低频，不缓存，直接读 DB 冗余字段。
 * </pre>
 *
 * <h2>点赞状态缓存三态</h2>
 * <pre>
 *   null         → 缓存未命中，需查 DB
 *   "1"          → 已点赞
 *   NULL_VALUE   → 确认未点赞（防穿透占位）
 * </pre>
 *
 * <h2>缓存一致性</h2>
 * <pre>
 *   所有缓存写操作通过 afterCommit 钩子在事务提交后执行，
 *   避免事务回滚导致缓存脏写。
 * </pre>
 *
 * <h2>允许点赞的目标类型</h2>
 * <pre>POST / ACTIVITY / COMMENT，不含 EXAM（产品决策）。</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractServiceImpl implements InteractService {

    // ── 允许的目标类型 ─────────────────────────────────────────────────────────
    private static final Set<TargetType> LIKE_ALLOWED_TYPES =
            Set.of(TargetType.POST, TargetType.ACTIVITY, TargetType.COMMENT, TargetType.EXAM);

    private static final Set<TargetType> CONTENT_ONLY_TYPES =
            Set.of(TargetType.POST, TargetType.ACTIVITY, TargetType.EXAM);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    // ── 依赖 ──────────────────────────────────────────────────────────────────
    private final LikeRecordMapper likeRecordMapper;
    private final ShareRecordMapper shareRecordMapper;
    private final RedisService redisService;
    private final TargetValidator targetValidator;
    private final PostMapper postMapper;
    private final ActivityMapper activityMapper;
    private final ExamInfoMapper examInfoMapper;

    // =========================================================================
    // 点赞
    // =========================================================================

    /**
     * 点赞目标内容。
     *
     * <p>流程：
     * <ol>
     *   <li>身份校验（必须登录）</li>
     *   <li>类型校验（仅支持 POST / ACTIVITY / COMMENT）</li>
     *   <li>存在性校验（TargetValidator，缓存优先）</li>
     *   <li>写 like_record，重复点赞幂等处理</li>
     *   <li>事务提交后同步 Redis 计数与状态缓存</li>
     * </ol>
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @throws BusinessException UNAUTHORIZED 未登录；BAD_REQUEST 类型不支持；NOT_FOUND 目标不存在
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void like(TargetType targetType, Long targetId) {
        Long userId = requireLogin();
        assertLikeAllowed(targetType);
        targetValidator.assertExists(targetType, targetId);

        boolean inserted = false;
        try {
            likeRecordMapper.insert(LikeRecord.builder()
                    .userId(userId)
                    .targetType(targetType.getCode())
                    .targetId(targetId)
                    .build());
            inserted = true;
        } catch (DuplicateKeyException e) {
            // 同一用户重复点赞：幂等，视为成功，不抛异常
            log.debug("[Interact.like] 幂等：userId={}, targetType={}, targetId={}",
                    userId, targetType, targetId);
        }

        final boolean actualInserted = inserted;
        afterCommit(() -> syncLikeCache(userId, targetType, targetId, true, actualInserted));

        // 只有真正新增点赞时才发通知（幂等重复点赞不发）

    }

    /**
     * 取消点赞。
     *
     * <p>若用户本未点赞（记录不存在），不更新计数，接口幂等。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlike(TargetType targetType, Long targetId) {
        Long userId = requireLogin();
        assertLikeAllowed(targetType);
        targetValidator.assertExists(targetType, targetId);

        int deleted = likeRecordMapper.delete(new LambdaQueryWrapper<LikeRecord>()
                .eq(LikeRecord::getUserId, userId)
                .eq(LikeRecord::getTargetType, targetType.getCode())
                .eq(LikeRecord::getTargetId, targetId));

        afterCommit(() -> syncLikeCache(userId, targetType, targetId, false, deleted > 0));
    }

    /**
     * 查询当前登录用户是否已点赞。
     *
     * <p>读路径：likeStatus 缓存（三态）→ 回源 DB → 写缓存。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @return true 已点赞
     */
    @Override
    public boolean isLiked(TargetType targetType, Long targetId) {
        Long userId = requireLogin();
        assertLikeAllowed(targetType);
        targetValidator.assertExists(targetType, targetId);

        String statusKey = RedisKeyConstant.likeStatus(userId, targetType.getKey(), targetId);
        String cached = redisService.getString(statusKey);

        if ("1".equals(cached)) return true;
        if (redisService.isNullPlaceholder(cached)) return false;

        // 缓存未命中，查 DB
        boolean liked = likeRecordMapper.exists(new LambdaQueryWrapper<LikeRecord>()
                .eq(LikeRecord::getUserId, userId)
                .eq(LikeRecord::getTargetType, targetType.getCode())
                .eq(LikeRecord::getTargetId, targetId));

        // 回写缓存（两种结果都缓存，防止反复穿透 DB）
        if (liked) {
            redisService.set(statusKey, "1", RedisKeyConstant.LIKE_STATUS_TTL);
        } else {
            redisService.setNullPlaceholder(statusKey, RedisKeyConstant.LIKE_STATUS_TTL);
        }
        return liked;
    }

    /**
     * 批量查询当前登录用户对多个目标的点赞状态。
     * <p>流程：
     * <ol>
     *  <li>multiGet 一次拿所有命中的缓存</li>
     *  <li>找出未命中的 targetId，批量查 DB</li>
     *  <li>DB 结果 multiset 批量回写缓存</li>
     *  <li>合并返回完整 Map</li>
     * </ol>
     *
     * @param targetType 目标类型
     * @param targetIds  目标 IDs
     * @return targetId → Boolean 的 Map；对于每个 targetId，true=已点赞，false=未点赞（包括未命中和确认未点赞两种情况）
     */
    @Override
    public Map<Long, Boolean> batchIsLiked(TargetType targetType, Collection<Long> targetIds) {
        Long userId = requireLogin();
        List<Long> distinctIds = targetIds.stream().distinct().toList();
        assertLikeAllowed(targetType);
        targetValidator.assertAllExist(targetType, distinctIds);

        // 1. 构建 key 列表，multiGet 一次取缓存
        List<String> keys = distinctIds.stream()
                .map(targetId -> RedisKeyConstant.likeStatus(userId, targetType.getKey(), targetId))
                .toList();

        Map<String, String> cachedValues = redisService.multiGet(keys, String.class);

        Map<Long, Boolean> result = new HashMap<>(distinctIds.size());
        List<Long> cacheMissIds = new ArrayList<>();

        for (int i = 0; i < keys.size(); i++) {
            Long targetId = distinctIds.get(i);
            String cached = cachedValues.get(keys.get(i));
            if ("1".equals(cached)) {
                // 缓存命中：存在，跳过
                result.put(targetId, true);
            } else if (redisService.isNullPlaceholder(cached)) {
                // 缓存命中：确认不存在
                result.put(targetId, false);
            } else {
                // 缓存未命中：需要查 DB
                cacheMissIds.add(targetId);
            }
        }

        // 2. 全部命中缓存，直接返回
        if (cacheMissIds.isEmpty()) return result;

        // 3. 批量查 DB：返回"已点赞"的 targetId 集合
        Set<Long> likedIds = likeRecordMapper.selectLikedTargetIds(
                userId, targetType.getCode(), cacheMissIds);

        // 4. 填充结果 + 批量回写缓存
        Map<String, Object> toCache = new HashMap<>(cacheMissIds.size());
        for (Long targetId : cacheMissIds) {
            boolean liked = likedIds.contains(targetId);
            result.put(targetId, liked);

            String key = RedisKeyConstant.likeStatus(userId, targetType.getKey(), targetId);
            if (liked) {
                toCache.put(key, "1");
            } else {
                toCache.put(key, "__NULL__");
            }
        }
        redisService.multiSet(toCache, RedisKeyConstant.LIKE_STATUS_TTL);
        return result;
    }

    /**
     * 查询目标点赞总数。
     *
     * <p>读路径：Redis 计数器（永久 key）→ 缓存 Miss 时查 DB 并写入 Redis。
     * 使用分布式锁防止缓存 Miss 时大量请求同时打到 DB（缓存击穿）。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @return 点赞数，无记录时返回 0
     */
    @Override
    public long getLikeCount(TargetType targetType, Long targetId) {
        targetValidator.assertExists(targetType, targetId);

        String countKey = RedisKeyConstant.likeCount(targetType.getKey(), targetId);

        // 第一次读缓存
        Long cached = redisService.getLong(countKey);
        if (cached != null) return cached;

        // 缓存 Miss：加锁，防止击穿
        String lockKey = RedisKeyConstant.likeCountLock(targetType.getKey(), targetId);
        if (redisService.setIfAbsent(lockKey, "1", RedisKeyConstant.LIKE_COUNT_LOCK_TTL)) {
            try {
                // Double-Check：拿到锁后再读一次，防止上一轮已回填
                Long afterLock = redisService.getLong(countKey);
                if (afterLock != null) return afterLock;

                long dbCount = countLikeFromDB(targetType.getCode(), targetId);

                redisService.set(countKey, dbCount, RedisKeyConstant.LIKE_COUNT_TTL);
                return dbCount;
            } finally {
                redisService.delete(lockKey);
            }
        }

        // 未抢到锁：等待回填后再读一次，兜底查 DB
        Long retry = redisService.getLong(countKey);
        return retry != null ? retry : countLikeFromDB(targetType.getCode(), targetId);
    }

    /**
     * 批量查询目标点赞总数。
     *
     * <p>读路径：MGET 一次取全部缓存 → 未命中部分一次 IN 查 DB → 批量回写 Redis。
     * <p>与单条不同，批量场景请求本身已聚合，击穿风险极低，无需逐 key 加锁。
     *
     * @param targetType 目标类型
     * @param targetIds  目标 ID 集合
     * @return targetId → 点赞数，无记录时为 0
     */
    @Override
    public Map<Long, Integer> batchGetLikeCount(TargetType targetType, Collection<Long> targetIds) {
        List<Long> distinctIds = targetIds.stream().distinct().toList();
        targetValidator.assertAllExist(targetType, distinctIds);

        // 1. 构建 key 列表
        List<String> keys = distinctIds.stream()
                .map(id -> RedisKeyConstant.likeCount(targetType.getKey(), id))
                .toList();

        // 2. 一次 mGET 取全部缓存
        Map<String, Integer> cachedValues = redisService.multiGet(keys, Integer.class);

        Map<Long, Integer> result = new HashMap<>(distinctIds.size());
        List<Long> cacheMissIds = new ArrayList<>();

        for (int i = 0; i < distinctIds.size(); i++) {
            Long targetId = distinctIds.get(i);
            Integer cached = cachedValues.get(keys.get(i));
            if (cached != null) {
                result.put(targetId, cached);
            } else {
                cacheMissIds.add(targetId);
            }
        }

        // 3. 全部命中缓存，直接返回
        if (cacheMissIds.isEmpty()) return result;

        // 4. 批量查 DB（一次 GROUP BY IN 查询）
        Map<Long, Integer> dbCounts = batchCountLikeFromDB(targetType.getCode(), cacheMissIds);

        // 5. 填充结果 + 批量回写缓存（无 TTL，与单条保持一致）
        Map<String, Object> toCache = new HashMap<>(cacheMissIds.size());
        for (Long targetId : cacheMissIds) {
            int count = dbCounts.getOrDefault(targetId, 0);
            result.put(targetId, count);
            toCache.put(RedisKeyConstant.likeCount(targetType.getKey(), targetId), count);
        }
        redisService.multiSet(toCache, RedisKeyConstant.LIKE_COUNT_TTL);
        return result;
    }

    @Override
    public List<UserLikeBO> pageUserLikes(UserLikeQuery query) {
        UserLikeQuery actualQuery = query == null ? new UserLikeQuery() : query;
        int pageSize = normalizePageSize(actualQuery.getPageSize());

        List<LikeRecord> likeRecords = likeRecordMapper.selectList(buildWrapper(actualQuery, pageSize));

        return likeRecords.stream()
                .map(record -> UserLikeBO.builder()
                        .targetId(record.getTargetId())
                        .build())
                .toList();
    }

    // =========================================================================
    // 分享
    // =========================================================================

    /**
     * 记录分享行为。
     *
     * <p>仅支持内容型目标（POST / ACTIVITY / EXAM），不支持 COMMENT。
     * 分享数极低频，不缓存，直接读 DB 冗余字段即可，此处只写记录。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @param platform   分享平台编码
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void share(TargetType targetType, Long targetId, Integer platform) {
        Long userId = requireLogin();
        assertContentOnly(targetType);
        targetValidator.assertExists(targetType, targetId);

        shareRecordMapper.insert(ShareRecord.builder()
                .userId(userId)
                .targetType(targetType.getCode())
                .targetId(targetId)
                .platform(platform)
                .build());
        // 分享数不缓存，由 DB 冗余字段（share_count）维护，调用方直接读内容详情即可
    }

    @Override
    public long getShareCount(TargetType targetType, Long targetId) {
        assertContentOnly(targetType);
        targetValidator.assertExists(targetType, targetId);

        Long count = shareRecordMapper.selectCount(new LambdaQueryWrapper<ShareRecord>()
                .eq(ShareRecord::getTargetType, targetType.getCode())
                .eq(ShareRecord::getTargetId, targetId));
        return count == null ? 0L : count;
    }

    // =========================================================================
    // 浏览
    // =========================================================================

    /**
     * 记录浏览行为。
     *
     * <p>轻量版设计：
     * <ul>
     *   <li>不写 view_log</li>
     *   <li>不做用户/设备/IP 去重</li>
     *   <li>Redis 只保存未同步增量 view_delta</li>
     *   <li>定时任务批量把 delta 加到内容表 view_count</li>
     * </ul>
     *
     * @param targetType 目标类型（POST / ACTIVITY / EXAM，不支持 COMMENT）
     * @param targetId   目标 ID
     */
    @Override
    public void view(TargetType targetType, Long targetId) {
        assertContentOnly(targetType);
        targetValidator.assertExists(targetType, targetId);

        String deltaKey = RedisKeyConstant.viewDelta(targetType.getKey(), targetId);

        redisService.incrementAndRefresh(deltaKey, RedisKeyConstant.VIEW_DELTA_TTL);
        redisService.sAdd(RedisKeyConstant.VIEW_DIRTY_SET, deltaKey);
    }

    /**
     * 查询目标浏览总数。
     *
     * <p>读路径：
     * <pre>
     *   内容表 view_count + Redis view_delta
     * </pre>
     *
     * <p>DB 是长期基准，Redis 只保存尚未刷库的短期增量。
     *
     * @param targetType 目标类型（POST / ACTIVITY / EXAM，不支持 COMMENT）
     * @param targetId   目标 ID
     * @return 浏览量
     */
    @Override
    public long getViewCount(TargetType targetType, Long targetId) {
        assertContentOnly(targetType);
        targetValidator.assertExists(targetType, targetId);

        long dbCount = loadViewCountFromContentTable(targetType, targetId);

        String deltaKey = RedisKeyConstant.viewDelta(targetType.getKey(), targetId);
        Long delta = redisService.getLong(deltaKey);

        return dbCount + (delta == null ? 0L : Math.max(0L, delta));
    }

    /**
     * 批量查询目标浏览总数。
     *
     * <p>读路径：
     * <pre>
     *   批量读取内容表 view_count
     *   +
     *   批量读取 Redis view_delta
     * </pre>
     *
     * @param targetType 目标类型
     * @param targetIds  目标 ID 集合
     * @return targetId → 浏览量，无记录时为 0
     */
    @Override
    public Map<Long, Integer> batchGetViewCount(TargetType targetType, Collection<Long> targetIds) {
        List<Long> distinctIds = targetIds == null
                ? List.of()
                : targetIds.stream().filter(Objects::nonNull).distinct().toList();

        if (distinctIds.isEmpty()) {
            return Map.of();
        }

        assertContentOnly(targetType);
        targetValidator.assertAllExist(targetType, distinctIds);

        // 1. DB 基准值：内容表 view_count
        Map<Long, Integer> dbCounts = batchLoadViewCountFromContentTable(targetType, distinctIds);

        // 2. Redis 未同步增量：view_delta
        List<String> deltaKeys = distinctIds.stream()
                .map(id -> RedisKeyConstant.viewDelta(targetType.getKey(), id))
                .toList();

        Map<String, Long> deltaValues = redisService.multiGetLong(deltaKeys);

        // 3. 合并 DB + Redis delta
        Map<Long, Integer> result = new HashMap<>(distinctIds.size());
        for (int i = 0; i < distinctIds.size(); i++) {
            Long targetId = distinctIds.get(i);

            long dbCount = dbCounts.getOrDefault(targetId, 0);
            Long delta = deltaValues.get(deltaKeys.get(i));
            long total = dbCount + (delta == null ? 0L : Math.max(0L, delta));

            result.put(targetId, safeLongToInt(total));
        }

        return result;
    }
    // =========================================================================
    // 缓存同步
    // =========================================================================

    /**
     * 同步点赞状态至 Redis。
     *
     * <p>职责：
     * <ul>
     *   <li>更新 likeStatus 缓存（"1" 或 NULL_VALUE）</li>
     *   <li>若状态实际发生变更（countChanged=true），对 likeCount 计数器 incr/decr</li>
     * </ul>
     *
     * <p>注意：likeCount 是永久 key（无 TTL），只在 key 存在时才操作，
     * 冷 key（从未被查询过）不主动创建，等 getLikeCount 查询时再从 DB 建立。
     *
     * @param userId       用户 ID
     * @param targetType   目标类型
     * @param targetId     目标 ID
     * @param liked        操作后状态（true=已点赞）
     * @param countChanged 是否实际插入/删除了 DB 记录（幂等情况下为 false）
     */
    private void syncLikeCache(Long userId, TargetType targetType, Long targetId,
                               boolean liked, boolean countChanged) {
        String statusKey = RedisKeyConstant.likeStatus(userId, targetType.getKey(), targetId);
        String countKey = RedisKeyConstant.likeCount(targetType.getKey(), targetId);

        try {
            // 更新点赞状态（三态：null/1/NULL_VALUE）
            if (liked) {
                redisService.set(statusKey, "1", RedisKeyConstant.LIKE_STATUS_TTL);
            } else {
                redisService.setNullPlaceholder(statusKey, RedisKeyConstant.LIKE_STATUS_TTL);
            }

            // 只在计数器 key 存在时才更新（冷 key 不主动创建，防止计数不准）
            if (countChanged && redisService.getLong(countKey) != null) {
                long after = liked
                        ? redisService.incrementAndRefresh(countKey, RedisKeyConstant.LIKE_COUNT_TTL)
                        : redisService.decrementAndRefresh(countKey, RedisKeyConstant.LIKE_COUNT_TTL);
                // 并发取消点赞可能导致负数，归零兜底
                if (after < 0) {
                    redisService.set(countKey, 0L);
                    log.warn("[Interact.syncLikeCache] 计数出现负值已归零，targetType={}, targetId={}",
                            targetType, targetId);
                }
                // 变更登记到脏集合
                redisService.sAdd(RedisKeyConstant.LIKE_DIRTY_SET, countKey);
            }
        } catch (Exception e) {
            // 缓存失败不影响主流程，下次查询时自动回源 DB 重建
            log.warn("[Interact.syncLikeCache] Redis 同步失败，userId={}, targetType={}, targetId={}",
                    userId, targetType, targetId, e);
        }
    }

    // =========================================================================
    // DB 回源
    // =========================================================================

    private long countLikeFromDB(Integer targetTypeCode, Long targetId) {
        Long count = likeRecordMapper.selectCount(new LambdaQueryWrapper<LikeRecord>()
                .eq(LikeRecord::getTargetType, targetTypeCode)
                .eq(LikeRecord::getTargetId, targetId));
        return count == null ? 0L : count;
    }

    private Map<Long, Integer> batchCountLikeFromDB(Integer targetTypeCode, List<Long> targetIds) {
        List<InteractCountDTO> rows = likeRecordMapper.selectLikeCountBatch(targetTypeCode, targetIds);
        return rows.stream()
                .collect(Collectors.toMap(InteractCountDTO::getTargetId, InteractCountDTO::getCount));
    }

    /**
     * 从内容表读取浏览量基准值。
     *
     * <p>注意：不再 count view_log。
     * view_log 当前不参与浏览量统计。</p>
     */
    private long loadViewCountFromContentTable(TargetType targetType, Long targetId) {
        Long count = switch (targetType) {
            case POST -> postMapper.selectViewCountById(targetId);
            case ACTIVITY -> activityMapper.selectViewCountById(targetId);
            case EXAM -> examInfoMapper.selectViewCountById(targetId);
            default -> 0L;
        };

        return count == null ? 0L : Math.max(0L, count);
    }

    // =========================================================================
    // 校验辅助
    // =========================================================================

    /**
     * 获取当前登录用户 ID，未登录抛出 UNAUTHORIZED。
     */
    private Long requireLogin() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        return userId;
    }

    /**
     * 断言目标类型支持点赞（POST / ACTIVITY / COMMENT）。
     */
    private void assertLikeAllowed(TargetType targetType) {
        if (!LIKE_ALLOWED_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该目标类型不支持点赞");
        }
    }

    /**
     * 断言目标类型为内容型（POST / ACTIVITY / EXAM），COMMENT 不支持分享和浏览。
     */
    private void assertContentOnly(TargetType targetType) {
        if (!CONTENT_ONLY_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该目标类型不支持此操作");
        }
    }

    // =========================================================================
    // 事务钩子
    // =========================================================================

    /**
     * 在事务提交后执行任务；若无活动事务则立即执行。
     *
     * <p>所有缓存写操作通过此方法注册，保证事务回滚时缓存不被脏写。
     */
    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    });
        } else {
            task.run();
        }
    }

    private int normalizePageSize(Integer pageSize) {
        int actualPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (actualPageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(actualPageSize, MAX_PAGE_SIZE);
    }

    private LambdaQueryWrapper<LikeRecord> buildWrapper(UserLikeQuery query, int pageSize) {
        Integer targetType = query.getTargetType() != null ? query.getTargetType().getCode() : null;
        return new LambdaQueryWrapper<LikeRecord>()
                .select(LikeRecord::getId, LikeRecord::getTargetId)
                .eq(targetType != null, LikeRecord::getTargetType, targetType)
                // 按用户过滤
                .eq(query.getUserId() != null, LikeRecord::getUserId, query.getUserId())
                .lt(query.getLastId() != null, LikeRecord::getId, query.getLastId())
                .orderByDesc(LikeRecord::getId)
                .last("LIMIT " + pageSize);
    }

    private int safeLongToInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private Map<Long, Integer> batchLoadViewCountFromContentTable(TargetType targetType, List<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }

        List<InteractCountDTO> rows = switch (targetType) {
            case POST -> postMapper.selectViewCountBatch(targetIds);
            case ACTIVITY -> activityMapper.selectViewCountBatch(targetIds);
            case EXAM -> examInfoMapper.selectViewCountBatch(targetIds);
            default -> List.of();
        };

        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        return rows.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        InteractCountDTO::getTargetId,
                        row -> row.getCount() == null ? 0 : Math.max(0, row.getCount()),
                        (a, b) -> a
                ));
    }
}