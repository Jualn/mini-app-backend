package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.interact.bo.InteractionStatisticsBO;
import cn.jualn.miniapp.module.interact.service.InteractionStatisticsService;
import cn.jualn.miniapp.module.interact.mapper.InteractionStatisticsMapper;
import cn.jualn.miniapp.module.comment.service.CommentStatisticsService;

import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InteractionStatisticsServiceImpl implements InteractionStatisticsService {
    private final InteractionStatisticsMapper mapper;
    private final CommentStatisticsService comments;

    @Override
    public Map<Long, InteractionStatisticsBO> counts(TargetType type, Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var commentCounts = comments.visibleCounts(type, ids);
        Map<Long, InteractionStatisticsBO> result = new HashMap<>();
        mapper.counts(type.getCode(), ids).forEach(r -> result.put(r.getTargetId(), new InteractionStatisticsBO(r.getViewCount(), r.getLikeCount(), commentCounts.getOrDefault(r.getTargetId(), 0L), r.getShareCount())));
        return result;
    }
}
