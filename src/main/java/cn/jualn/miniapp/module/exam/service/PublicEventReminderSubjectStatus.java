package cn.jualn.miniapp.module.exam.service;

import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.notify.reminder.ReminderSubjectStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PublicEventReminderSubjectStatus implements ReminderSubjectStatus {
    private final ExamInfoMapper examInfoMapper;
    private final cn.jualn.miniapp.module.timeline.mapper.TimelineMapper timelineMapper;
    @Override public int sourceType() { return 2; }
    @Override public boolean isCurrentAndEligible(long sourceId, Long generation, Long timelineId,
                                                   java.time.LocalDateTime now) {
        ExamInfo event = examInfoMapper.selectReminderStatus(sourceId);
        if (event == null || !Integer.valueOf(1).equals(event.getPublishStatus())
                || !Integer.valueOf(0).equals(event.getLifecycleStatus()) || timelineId == null) return false;
        cn.jualn.miniapp.module.timeline.entity.Timeline node = timelineMapper.selectById(timelineId);
        return node != null && Integer.valueOf(2).equals(node.getStartPrecision())
                && node.getStartTime() != null && node.getStartTime().isAfter(now)
                && Integer.valueOf(cn.jualn.miniapp.common.enums.TargetType.EXAM.getCode()).equals(node.getTargetType())
                && java.util.Objects.equals(sourceId, node.getTargetId());
    }
}
