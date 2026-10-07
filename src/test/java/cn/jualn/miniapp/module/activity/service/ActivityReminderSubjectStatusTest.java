package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.timeline.entity.Timeline;
import cn.jualn.miniapp.module.timeline.mapper.TimelineMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityReminderSubjectStatusTest {
    @Test
    void reminderExpiresAtItsExactBusinessNodeRatherThanAtSendAt() {
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        TimelineMapper timelineMapper = mock(TimelineMapper.class);
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 8, 0);
        when(activityMapper.selectReminderStatus(7L)).thenReturn(
                Activity.builder().id(7L).publishStatus(1).lifecycleStatus(0).build());
        Timeline node = Timeline.builder().id(9L).targetType(TargetType.ACTIVITY.getCode()).targetId(7L)
                .startPrecision(2).startTime(now.plusMinutes(1)).build();
        when(timelineMapper.selectById(9L)).thenReturn(node);
        ActivityReminderSubjectStatus status = new ActivityReminderSubjectStatus(activityMapper, timelineMapper);

        assertTrue(status.isCurrentAndEligible(7L, 2L, 9L, now));
        node.setStartTime(now);
        assertFalse(status.isCurrentAndEligible(7L, 2L, 9L, now));
    }
}
