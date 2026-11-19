package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.timeline.entity.Timeline;
import cn.jualn.miniapp.module.timeline.mapper.TimelineMapper;
import cn.jualn.miniapp.module.timeline.service.TimelineFactReader;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicEventReminderSubjectStatusTest {
    @Test void reminderUsesLiveOwnedTimelineAndRejectsAnotherSubjectsNode() {
        var events=mock(PublicEventMapper.class);
        var timelines=mock(TimelineMapper.class);
        var status=new PublicEventReminderSubjectStatus(events,new TimelineFactReader(timelines));
        var now=LocalDateTime.of(2026,10,1,12,0);
        when(events.selectReminderStatus(7L)).thenReturn(PublicEvent.builder().publishStatus(1).lifecycleStatus(0).build());
        var node=Timeline.builder().id(9L).targetType(TargetType.EXAM.getCode()).targetId(7L)
                .startPrecision(2).startTime(now.plusHours(1)).build();
        when(timelines.selectById(9L)).thenReturn(node);
        assertTrue(status.isCurrentAndEligible(7L,1L,9L,now));
        node.setTargetId(8L);
        assertFalse(status.isCurrentAndEligible(7L,1L,9L,now));
        node.setTargetId(7L);node.setStartTime(now.minusHours(1));
        assertFalse(status.isCurrentAndEligible(7L,1L,9L,now));
        verify(timelines,times(3)).selectById(9L);
    }
}
