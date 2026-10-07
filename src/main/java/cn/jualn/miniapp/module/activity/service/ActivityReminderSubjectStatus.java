package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.notify.reminder.ReminderSubjectStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActivityReminderSubjectStatus implements ReminderSubjectStatus {
    private final ActivityMapper activityMapper;
    private final cn.jualn.miniapp.module.timeline.mapper.TimelineMapper timelineMapper;
    @Override public int sourceType() { return 1; }
    @Override public boolean isCurrentAndEligible(long sourceId, Long generation, Long timelineId,
                                                   java.time.LocalDateTime now) {
        Activity activity = activityMapper.selectReminderStatus(sourceId);
        if (activity == null || !Integer.valueOf(1).equals(activity.getPublishStatus())
                || !Integer.valueOf(0).equals(activity.getLifecycleStatus()) || timelineId == null) return false;
        cn.jualn.miniapp.module.timeline.entity.Timeline node = timelineMapper.selectById(timelineId);
        return node != null && Integer.valueOf(2).equals(node.getStartPrecision())
                && node.getStartTime() != null && node.getStartTime().isAfter(now)
                && Integer.valueOf(cn.jualn.miniapp.common.enums.TargetType.ACTIVITY.getCode()).equals(node.getTargetType())
                && java.util.Objects.equals(sourceId, node.getTargetId());
    }
}
