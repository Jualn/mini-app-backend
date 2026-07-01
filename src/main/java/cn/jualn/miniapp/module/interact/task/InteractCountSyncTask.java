package cn.jualn.miniapp.module.interact.task;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 互动计数回写任务。
 *
 * <p>每 5 分钟把 Redis 中 like/view 计数回写到内容表冗余字段，确保最终一致性。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractCountSyncTask {

    private final RedisService redisService;
    private final PostMapper postMapper;
    private final ActivityMapper activityMapper;
    private final ExamInfoMapper examInfoMapper;
    private final CommentMapper commentMapper;

    @Scheduled(cron = "0 */5 * * * ?")
    public void syncInteractCounts() {
        int likeSynced = syncLikeCounts();
        int viewSynced = syncViewCounts();

        if (likeSynced > 0 || viewSynced > 0) {
            log.info("[InteractCountSyncTask] 同步完成，likeSynced={}, viewSynced={}", likeSynced, viewSynced);
        } else {
            log.debug("[InteractCountSyncTask] 无需同步");
        }
    }

    private int syncLikeCounts() {
        // 直接取脏集合，再原子清空
        Set<String> dirtyKeys = redisService.sMembers(RedisKeyConstant.LIKE_DIRTY_SET);
        if (dirtyKeys.isEmpty()) return 0;

        // 先删集合，后处理：处理期间新增的 key 下一轮再同步，不会漏
        redisService.delete(RedisKeyConstant.LIKE_DIRTY_SET);

        int synced = 0;
        for (String key : dirtyKeys) {
            CounterTarget target = parseCounterTarget(key, RedisKeyConstant.LIKE_COUNT_PREFIX);
            if (target == null) {
                continue;
            }

            Long count = redisService.getLong(key);
            if (count == null) {
                continue;
            }

            if (applyLikeCount(target.type(), target.targetId(), Math.max(0L, count))) {
                synced++;
            }
        }
        return synced;
    }

    private int syncViewCounts() {
        Set<String> keys = redisService.sMembers(RedisKeyConstant.VIEW_DIRTY_SET);
        if (keys.isEmpty()) return 0;

        int synced = 0;

        for (String key : keys) {
            CounterTarget target = parseCounterTarget(key, RedisKeyConstant.VIEW_DELTA_PREFIX);
            if (target == null) {
                redisService.sRemove(RedisKeyConstant.VIEW_DIRTY_SET, key);
                continue;
            }

            Long delta = redisService.getAndDeleteLong(key);
            if (delta == null || delta <= 0) {
                redisService.sRemove(RedisKeyConstant.VIEW_DIRTY_SET, key);
                continue;
            }

            try {
                if (applyViewDelta(target.type(), target.targetId(), delta)) {
                    redisService.sRemove(RedisKeyConstant.VIEW_DIRTY_SET, key);
                    synced++;
                } else {
                    // 目标类型不支持或更新失败，回补 delta，避免丢数
                    redisService.incrementByAndRefresh(key, delta, RedisKeyConstant.VIEW_DELTA_TTL);
                    redisService.sAdd(RedisKeyConstant.VIEW_DIRTY_SET, key);
                }
            } catch (Exception e) {
                // DB 更新异常，回补 delta，避免丢数
                redisService.incrementByAndRefresh(key, delta, RedisKeyConstant.VIEW_DELTA_TTL);
                redisService.sAdd(RedisKeyConstant.VIEW_DIRTY_SET, key);
                log.warn("[InteractCountSyncTask] view delta 同步失败，key={}, delta={}", key, delta, e);
            }
        }

        return synced;
    }

    private boolean applyLikeCount(TargetType type, Long targetId, Long count) {
        switch (type) {
            case POST -> postMapper.setLikeCount(targetId, count);
            case ACTIVITY -> activityMapper.setLikeCount(targetId, count);
            case EXAM -> examInfoMapper.setLikeCount(targetId, count);
            case COMMENT -> commentMapper.setLikeCount(targetId, count);
            default -> {
                log.warn("[InteractCountSyncTask] 跳过不支持的 like 目标类型，type={}, targetId={}", type, targetId);
                return false;
            }
        }
        return true;
    }

    private boolean applyViewDelta(TargetType type, Long targetId, Long delta) {
        if (delta == null || delta <= 0) {
            return false;
        }

        int affected = switch (type) {
            case POST -> postMapper.incrementViewCount(targetId, delta);
            case ACTIVITY -> activityMapper.incrementViewCount(targetId, delta);
            case EXAM -> examInfoMapper.incrementViewCount(targetId, delta);
            default -> {
                log.warn("[InteractCountSyncTask] 跳过不支持的 view 目标类型，type={}, targetId={}", type, targetId);
                yield 0;
            }
        };

        return affected > 0;
    }

    private CounterTarget parseCounterTarget(String key, String prefix) {
        if (key == null || !key.startsWith(prefix)) {
            return null;
        }

        String body = key.substring(prefix.length());
        String[] parts = body.split(":");
        if (parts.length != 2) {
            log.warn("[InteractCountSyncTask] key 格式非法，key={}", key);
            return null;
        }

        TargetType type = resolveTargetType(parts[0]);
        if (type == null) {
            log.warn("[InteractCountSyncTask] 未识别的目标类型，key={}", key);
            return null;
        }

        try {
            Long targetId = Long.parseLong(parts[1]);
            return new CounterTarget(type, targetId);
        } catch (NumberFormatException e) {
            log.warn("[InteractCountSyncTask] 目标ID解析失败，key={}", key);
            return null;
        }
    }

    private TargetType resolveTargetType(String typeKey) {
        for (TargetType type : TargetType.values()) {
            if (type.getKey().equals(typeKey)) {
                return type;
            }
        }
        return null;
    }

    private record CounterTarget(TargetType type, Long targetId) {
    }
}
