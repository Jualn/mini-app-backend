package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.module.comment.service.CommentStatisticsService;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.common.enums.TargetType;

import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CommentStatisticsServiceImpl implements CommentStatisticsService {
    private final CommentMapper mapper;

    @Override
    public void setLikeCount(long commentId, long count) {
        mapper.setLikeCount(commentId, count);
    }

    @Override
    public Map<Long, Long> visibleCounts(TargetType type, Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, Long> result = new HashMap<>();
        mapper.visibleCounts(type.getCode(), ids).forEach(r -> result.put(r.getTargetId(), r.getCount()));
        return result;
    }
}
