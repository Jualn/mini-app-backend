package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;


/** Interact 内部点赞缓存同步；缓存写入在提交后执行，冷计数键不主动创建。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeCache {
    private final RedisService redisService;

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

    public void afterChange(Long userId, TargetType type, Long targetId, boolean liked, boolean changed) {
        afterCommit(() -> syncLikeCache(userId, type, targetId, liked, changed));
    }
}
