package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.common.enums.TargetType;
import java.util.Collection;
import java.util.Map;

/** Subject-owned metadata and derived counter projection. Does not grant public access. */
public interface InteractionSubject {
    record Summary(Long ownerId, String title) { }
    TargetType targetType();
    Summary notificationSummary(long id);
    void commentApproved(long id);
    void setLikeCount(long id, long count);
    int incrementViews(long id, long delta);
    Long viewCount(long id);
    Map<Long,Integer> viewCounts(Collection<Long> ids);
}
