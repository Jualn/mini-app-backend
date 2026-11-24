package cn.jualn.miniapp.module.comment.service;

import java.util.Collection;
import java.util.Map;

import cn.jualn.miniapp.common.enums.TargetType;

public interface CommentStatisticsService {
    Map<Long, Long> visibleCounts(TargetType type, Collection<Long> targetIds);

    void setLikeCount(long commentId, long count);
}
