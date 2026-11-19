package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
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
public class PostInteractionSubject implements InteractionSubject {
    private final PostMapper mapper;
    private final RedisService redis;

    @Override
    public TargetType targetType() {
        return TargetType.POST;
    }

    @Override
    public Summary notificationSummary(long id) {
        var row = mapper.selectOne(new LambdaQueryWrapper<Post>()
                .select(Post::getUserId, Post::getTitle).eq(Post::getId, id));
        return row == null ? null : new Summary(row.getUserId(), row.getTitle());
    }

    @Override
    public void commentApproved(long id) {
        mapper.incrementCommentCount(id);
        redis.delete(RedisKeyConstant.postDetail(id));
    }

    public void commentRemoved(long id) {
        mapper.decrementCommentCount(id);
        redis.delete(RedisKeyConstant.postDetail(id));
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
