package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.interact.service.InteractionSubject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;

/**
 * Narrow subject capability; excludes visibility decisions and interaction orchestration.
 */
@Service
@RequiredArgsConstructor
public class ActivityInteractionSubject implements InteractionSubject {
    private final ActivityMapper mapper;
    private final RedisService redis;

    public void commentRemoved(Long id) {
        mapper.decreaseCommentCount(id);
        redis.delete(RedisKeyConstant.activityDetail(id));
    }

    @Override
    public TargetType targetType() {
        return TargetType.ACTIVITY;
    }

    @Override
    public Summary notificationSummary(long id) {
        var row = mapper.selectOne(new LambdaQueryWrapper<Activity>()
                .select(Activity::getUserId, Activity::getTitle).eq(Activity::getId, id));
        return row == null ? null : new Summary(row.getUserId(), row.getTitle());
    }

    @Override
    public void commentApproved(long id) {
        mapper.increaseCommentCount(id);
        redis.delete(RedisKeyConstant.activityDetail(id));
    }

    @Override
    public void setLikeCount(long id, long count) {
        mapper.setLikeCount(id, count);
    }

    @Override
    public int incrementViews(long id, long delta) {
        return mapper.incrementViewCount(id, delta);
    }

    @Override
    public Long viewCount(long id) {
        return mapper.selectViewCountById(id);
    }

    @Override
    public Map<Long, Integer> viewCounts(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var rows = mapper.selectViewCountBatch(ids);
        if (rows == null) return Map.of();
        Map<Long, Integer> result = new HashMap<>();
        for (var row : rows)
            if (row != null)
                result.putIfAbsent(row.getTargetId(), row.getCount() == null ? 0 : Math.max(0, row.getCount()));
        return result;
    }
}
