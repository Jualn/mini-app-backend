package cn.jualn.miniapp.module.timeline.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineUpdateBO;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.module.timeline.entity.Timeline;
import cn.jualn.miniapp.module.timeline.mapper.TimelineMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimelineServiceImplTest {

    @Mock
    private TimelineMapper timelineMapper;
    @Mock
    private TimelineConverter timelineConverter;
    @Mock
    private TargetValidator targetValidator;

    @Test
    void replaceTimelines_shouldInsertNewNodesWithoutDeletingOtherTargets() {
        TimelineServiceImpl service = new TimelineServiceImpl(timelineMapper, timelineConverter, targetValidator);
        TimelineSaveBO saveBO = TimelineSaveBO.builder()
                .targetType(TargetType.ACTIVITY)
                .targetId(11L)
                .timelines(List.of(TimelineItemBO.builder().label("L1").sortOrder(1).build()))
                .build();
        when(timelineConverter.toTimelineList(saveBO)).thenReturn(List.of(Timeline.builder().label("L1").sortOrder(1).build()));

        when(timelineMapper.insert(any(Timeline.class))).thenReturn(1);
        service.replaceTimelines(saveBO);

        verify(targetValidator).assertExists(TargetType.ACTIVITY, 11L);
        verify(timelineMapper, org.mockito.Mockito.never()).delete(any());
        verify(timelineMapper).insert(any(Timeline.class));
    }

    @Test
    void updateTimeline_shouldRequireOwningAggregate() {
        TimelineServiceImpl service = new TimelineServiceImpl(timelineMapper, timelineConverter, targetValidator);
        TimelineUpdateBO updateBO = TimelineUpdateBO.builder().id(1L).label("x").build();

        assertThrows(BusinessException.class, () -> service.updateTimeline(updateBO));
    }

    @Test
    void deleteTimeline_shouldRequireOwningAggregate() {
        TimelineServiceImpl service = new TimelineServiceImpl(timelineMapper, timelineConverter, targetValidator);

        assertThrows(BusinessException.class, () -> service.deleteTimeline(1L));
    }

    @Test
    void listTimelinesByTarget_shouldMapResults() {
        TimelineServiceImpl service = new TimelineServiceImpl(timelineMapper, timelineConverter, targetValidator);
        when(timelineMapper.selectList(any())).thenReturn(List.of(Timeline.builder().id(1L).build()));
        when(timelineConverter.toItemDTOList(any())).thenReturn(List.of(TimelineItemDTO.builder().label("L1").build()));

        List<TimelineItemDTO> result = service.listTimelinesByTarget(TargetType.EXAM, 5L);

        assertEquals(1, result.size());
        verify(targetValidator).assertExists(TargetType.EXAM, 5L);
    }
}
