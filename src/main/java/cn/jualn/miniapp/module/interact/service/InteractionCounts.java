package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.module.interact.service.impl.InteractionAccess;
import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.interact.service.InteractionSubjects;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.interact.dto.inner.InteractCountDTO;
import cn.jualn.miniapp.module.interact.entity.LikeRecord;
import cn.jualn.miniapp.module.interact.entity.ShareRecord;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ShareRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;


/** 互动计数查询和既有缓存回源；普通浏览使用主体基准值加 Redis 未同步增量。 */
@Service
@RequiredArgsConstructor
public class InteractionCounts {
    private final LikeRecordMapper likeRecordMapper;
    private final ShareRecordMapper shareRecordMapper;
    private final RedisService redisService;
    private final InteractionSubjects subjects;
    private final ArticleService articleService;
    private final InteractionAccess access;

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
    public long getLikeCount(TargetType targetType, Long targetId) {
        access.guardPublic(targetType, targetId, false);
        if (targetType == TargetType.ARTICLE || access.canRemoveUnavailable(targetType,targetId)) return countLikeFromDB(targetType.getCode(), targetId);

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
    public Map<Long, Integer> batchGetLikeCount(TargetType targetType, Collection<Long> targetIds) {
        List<Long> distinctIds = targetIds.stream().distinct().toList();
        distinctIds.forEach(id -> access.guardPublic(targetType, id, false));

        if(!distinctIds.isEmpty() && distinctIds.stream().allMatch(id->access.canRemoveUnavailable(targetType,id))) return batchCountLikeFromDB(targetType.getCode(),distinctIds);

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

    public long getShareCount(TargetType targetType, Long targetId) {
        access.guardPublic(targetType, targetId, false);
        access.assertContentOnly(targetType);

        Long count = shareRecordMapper.selectCount(new LambdaQueryWrapper<ShareRecord>()
                .eq(ShareRecord::getTargetType, targetType.getCode())
                .eq(ShareRecord::getTargetId, targetId));
        return count == null ? 0L : count;
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
    public long getViewCount(TargetType targetType, Long targetId) {
        access.guardPublic(targetType, targetId, false);
        if (targetType == TargetType.ARTICLE) return articleService.statistics(targetId).viewCount();
        access.assertContentOnly(targetType);

        long dbCount = loadViewCountFromContentTable(targetType, targetId);

        if(targetType==TargetType.POST && access.canRemoveUnavailable(targetType,targetId)) return dbCount;
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
    public Map<Long, Integer> batchGetViewCount(TargetType targetType, Collection<Long> targetIds) {
        List<Long> distinctIds = targetIds == null
                ? List.of()
                : targetIds.stream().filter(Objects::nonNull).distinct().toList();

        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        if (targetType == TargetType.ARTICLE) {
            distinctIds.forEach(id -> access.guardPublic(targetType, id, false));
            Map<Long, Integer> result = new HashMap<>();
            distinctIds.forEach(id -> result.put(id, safeLongToInt(articleService.statistics(id).viewCount())));
            return result;
        }

        access.assertContentOnly(targetType);
        distinctIds.forEach(id -> access.guardPublic(targetType, id, false));

        // 1. DB 基准值：内容表 view_count
        Map<Long, Integer> dbCounts = batchLoadViewCountFromContentTable(targetType, distinctIds);

        if(targetType==TargetType.POST && distinctIds.stream().allMatch(id->access.canRemoveUnavailable(targetType,id))) return dbCounts;
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
        Long count = targetType == TargetType.POST || targetType == TargetType.ACTIVITY || targetType == TargetType.EXAM
                ? subjects.require(targetType).viewCount(targetId) : 0L;

        return count == null ? 0L : Math.max(0L, count);
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

        return targetType == TargetType.POST || targetType == TargetType.ACTIVITY || targetType == TargetType.EXAM
                ? subjects.require(targetType).viewCounts(targetIds) : Map.of();
    }
}
