package cn.jualn.miniapp.module.exam.service.impl;

import cn.jualn.miniapp.module.exam.bo.HomePublicMatterReminderRow;
import cn.jualn.miniapp.module.exam.bo.HomePublicMatterRemindersBO;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HomePublicMatterReminderServiceImplTest {

    private final ExamInfoMapper mapper = mock(ExamInfoMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T02:03:04.123456Z"), ZoneOffset.UTC);
    private final HomePublicMatterReminderServiceImpl service =
            new HomePublicMatterReminderServiceImpl(mapper, clock);

    @Test
    void returnsSubscribedRowsUsingOneEvaluationTime() {
        LocalDateTime evaluatedAt = LocalDateTime.of(2026, 9, 12, 10, 3, 4, 123_456_000);
        when(mapper.selectHomePublicMatterReminders(42L, evaluatedAt, 5)).thenReturn(List.of(
                HomePublicMatterReminderRow.builder()
                        .publicMatterId(8L)
                        .name("教师资格考试")
                        .timeNodeId(11L)
                        .nodeName("报名截止")
                        .reminderAt(LocalDateTime.of(2026, 9, 13, 18, 0))
                        .source("SUBSCRIPTIONS")
                        .build(),
                HomePublicMatterReminderRow.builder()
                        .publicMatterId(7L)
                        .name("研究生考试")
                        .timeNodeId(12L)
                        .nodeName("笔试")
                        .reminderAt(LocalDateTime.of(2026, 9, 20, 9, 0))
                        .source("SUBSCRIPTIONS")
                        .build()));

        HomePublicMatterRemindersBO result = service.listForUser(42L);

        assertEquals("2026-09-12T10:03:04.123456+08:00", result.evaluatedAt().toString());
        assertEquals(HomePublicMatterRemindersBO.Source.SUBSCRIPTIONS, result.source());
        assertEquals(2, result.items().size());
        assertEquals("报名截止", result.items().get(0).nodeName());
        assertEquals("2026-09-13T18:00+08:00", result.items().get(0).reminderAt().toString());
        assertEquals("笔试", result.items().get(1).nodeName());
        verify(mapper).selectHomePublicMatterReminders(42L, evaluatedAt, 5);
    }

    @Test
    void emptyCandidatesReturnDefaultSourceAndEmptyItems() {
        when(mapper.selectHomePublicMatterReminders(42L, LocalDateTime.of(
                2026, 9, 12, 10, 3, 4, 123_456_000), 5)).thenReturn(List.of());

        HomePublicMatterRemindersBO result = service.listForUser(42L);

        assertEquals(HomePublicMatterRemindersBO.Source.DEFAULT, result.source());
        assertEquals(List.of(), result.items());
    }
}
