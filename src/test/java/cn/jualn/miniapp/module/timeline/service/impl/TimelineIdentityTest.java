package cn.jualn.miniapp.module.timeline.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.timeline.bo.*;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.module.timeline.entity.Timeline;
import cn.jualn.miniapp.module.timeline.mapper.TimelineMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class TimelineIdentityTest {
    TimelineMapper mapper;
    TimelineServiceImpl service;
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Timeline.class);
        mapper = mock(TimelineMapper.class);
        service = new TimelineServiceImpl(mapper, Mappers.getMapper(TimelineConverter.class), mock(TargetValidator.class));
    }
    @Test void refusesForeignNodeId() {
        when(mapper.selectList(any())).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.replaceTimelines(save(TimelineItemBO.builder().id(99L).label("初赛").build())));
        verify(mapper, never()).insert(any(Timeline.class));
    }
    @Test void retainsIdWhenReorderingAndRenaming() {
        when(mapper.selectList(any())).thenReturn(List.of(Timeline.builder().id(8L).label("初赛").nodeType("CUSTOM").build()));
        when(mapper.update(any(), any())).thenReturn(1);
        service.replaceTimelines(save(TimelineItemBO.builder().id(8L).label("初赛改期").sortOrder(4).build()));
        verify(mapper, never()).insert(any(Timeline.class));
        verify(mapper, never()).delete(any());
    }
    @Test void rejectsNonMidnightDatePrecision() {
        when(mapper.selectList(any())).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.replaceTimelines(save(TimelineItemBO.builder().label("初赛")
                .startTime(LocalDateTime.of(2026,9,10,10,0)).startPrecision(1).build())));
    }
    @Test void permitsUnknownTimeNode() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.insert(any(Timeline.class))).thenReturn(1);
        service.replaceTimelines(save(TimelineItemBO.builder().label("复赛").timeDescription("另行通知").build()));
        verify(mapper).insert(argThat((Timeline node) -> node.getStartTime() == null && node.getStartPrecision() == 0));
    }
    @Test void rejectsPublicEventStartSemanticForActivity() {
        when(mapper.selectList(any())).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.replaceTimelines(save(TimelineItemBO.builder()
                .nodeKey("start").nodeType("PUBLIC_EVENT_START").label("开始")
                .startTime(LocalDateTime.of(2026, 9, 10, 10, 0)).startPrecision(2).build())));
        verify(mapper, never()).insert(any(Timeline.class));
    }
    private TimelineSaveBO save(TimelineItemBO node) {
        return TimelineSaveBO.builder().targetType(TargetType.ACTIVITY).targetId(1L).timelines(List.of(node)).build();
    }
}
