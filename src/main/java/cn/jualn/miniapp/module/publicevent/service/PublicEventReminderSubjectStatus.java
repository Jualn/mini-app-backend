package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.notify.reminder.ReminderSubjectStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PublicEventReminderSubjectStatus implements ReminderSubjectStatus {
    private final PublicEventMapper examInfoMapper;
    private final cn.jualn.miniapp.module.timeline.service.TimelineFactReader timelines;
    @Override public int sourceType() { return 2; }
    @Override public boolean isCurrentAndEligible(long sourceId, Long generation, Long timelineId,
                                                   java.time.LocalDateTime now) {
        PublicEvent event = examInfoMapper.selectReminderStatus(sourceId);
        if (event == null || !Integer.valueOf(1).equals(event.getPublishStatus())
                || !Integer.valueOf(0).equals(event.getLifecycleStatus()) || timelineId == null) return false;
        var node = timelines.start(timelineId,cn.jualn.miniapp.common.enums.TargetType.EXAM,sourceId);
        return node != null && Integer.valueOf(2).equals(node.precision())
                && node.time() != null && node.time().isAfter(now);
    }
}
