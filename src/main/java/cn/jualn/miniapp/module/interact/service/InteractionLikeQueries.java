package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.module.interact.service.impl.InteractionAccess;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.interact.bo.UserLikeBO;
import cn.jualn.miniapp.module.interact.dto.inner.UserLikeQuery;
import cn.jualn.miniapp.module.interact.entity.LikeRecord;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;


/** 当前用户点赞状态与可见点赞历史，不拥有互动写用例。 */
@Service
@RequiredArgsConstructor
public class InteractionLikeQueries {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private final LikeRecordMapper likeRecordMapper;
    private final RedisService redisService;
    private final InteractionAccess access;

    /**
     * 查询当前登录用户是否已点赞。
     *
     * <p>读路径：likeStatus 缓存（三态）→ 回源 DB → 写缓存。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @return true 已点赞
     */
    public boolean ownRelation(TargetType type,long id) {long actor=access.requireLogin();access.assertLikeAllowed(type);return likeRecordMapper.exists(new LambdaQueryWrapper<LikeRecord>().eq(LikeRecord::getUserId,actor).eq(LikeRecord::getTargetType,type.getCode()).eq(LikeRecord::getTargetId,id));}

    public boolean isLiked(TargetType targetType, Long targetId) {
        Long userId = access.requireLogin();
        access.assertLikeAllowed(targetType);
        access.guardPublic(targetType, targetId, false);

        if(access.canRemoveUnavailable(targetType,targetId)) return likeRecordMapper.exists(new LambdaQueryWrapper<LikeRecord>().eq(LikeRecord::getUserId,userId).eq(LikeRecord::getTargetType,targetType.getCode()).eq(LikeRecord::getTargetId,targetId));
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
    public Map<Long, Boolean> batchIsLiked(TargetType targetType, Collection<Long> targetIds) {
        Long userId = access.requireLogin();
        List<Long> distinctIds = targetIds.stream().distinct().toList();
        access.assertLikeAllowed(targetType);
        distinctIds.forEach(id -> access.guardPublic(targetType, id, false));

        if(!distinctIds.isEmpty() && distinctIds.stream().allMatch(id->access.canRemoveUnavailable(targetType,id))) {
            var liked=likeRecordMapper.selectLikedTargetIds(userId,targetType.getCode(),distinctIds);var result=new HashMap<Long,Boolean>();for(long id:distinctIds)result.put(id,liked.contains(id));return result;
        }
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

    public List<UserLikeBO> pageUserLikes(UserLikeQuery query) {
        UserLikeQuery actualQuery = query == null ? new UserLikeQuery() : query;
        Long actorId = access.requireLogin();
        if (actualQuery.getUserId() != null && !actorId.equals(actualQuery.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        actualQuery.setUserId(actorId);
        int pageSize = normalizePageSize(actualQuery.getPageSize());

        List<UserLikeBO> result = new ArrayList<>();
        Long cursor = actualQuery.getLastId();
        // Scan owned history until a public page is filled; hidden records must not
        // truncate access to older legitimate likes, nor be physically deleted.
        while (result.size() < pageSize) {
            UserLikeQuery scan = UserLikeQuery.builder().userId(actorId).targetType(actualQuery.getTargetType())
                    .lastId(cursor).build();
            List<LikeRecord> chunk = likeRecordMapper.selectList(buildWrapper(scan, pageSize));
            if (chunk.isEmpty()) break;
            for (LikeRecord record : chunk) {
                if (publicLikeRecord(record))
                    result.add(UserLikeBO.builder().id(record.getId()).targetId(record.getTargetId()).build());
                if (result.size() == pageSize) return result;
            }
            cursor = chunk.get(chunk.size() - 1).getId();
            if (chunk.size() < pageSize) break;
        }
        return result;
    }

    private int normalizePageSize(Integer pageSize) {
        int actualPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (actualPageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(actualPageSize, MAX_PAGE_SIZE);
    }

    private boolean publicLikeRecord(LikeRecord record) {
        if (record.getTargetType() == null || !access.supportsLike(TargetType.fromCode(record.getTargetType())))
            return false;
        try {
            access.guardPublic(TargetType.fromCode(record.getTargetType()), record.getTargetId(), false);
            return true;
        } catch (BusinessException rejected) {
            if (Set.of(ResultCode.NOT_FOUND, ResultCode.TARGET_NOT_FOUND, ResultCode.POST_NOT_FOUND,
                            ResultCode.COMMENT_NOT_FOUND, ResultCode.ACTIVITY_NOT_FOUND, ResultCode.EXAM_NOT_FOUND)
                    .contains(rejected.getResultCode())) return false;
            throw rejected;
        } catch (ContractProblemException rejected) {
            if (rejected.getStatus().value() == 404) return false;
            throw rejected;
        }
    }

    private LambdaQueryWrapper<LikeRecord> buildWrapper(UserLikeQuery query, int pageSize) {
        Integer targetType = query.getTargetType() != null ? query.getTargetType().getCode() : null;
        return new LambdaQueryWrapper<LikeRecord>()
                .select(LikeRecord::getId, LikeRecord::getTargetId, LikeRecord::getTargetType)
                .eq(targetType != null, LikeRecord::getTargetType, targetType)
                // 按用户过滤
                .eq(query.getUserId() != null, LikeRecord::getUserId, query.getUserId())
                .lt(query.getLastId() != null, LikeRecord::getId, query.getLastId())
                .orderByDesc(LikeRecord::getId)
                .last("LIMIT " + pageSize);
    }
}
