package cn.jualn.miniapp.module.exam.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.ExamStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.bo.ExamPageBO;
import cn.jualn.miniapp.module.exam.converter.ExamConverter;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamSubscriptionMapper;
import cn.jualn.miniapp.module.exam.vo.ExamDetailVO;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamServiceImplTest {

    @Mock
    private ExamInfoMapper examInfoMapper;
    @Mock
    private ExamSubscriptionMapper examSubscriptionMapper;
    @Mock
    private ExamConverter examConverter;
    @Mock
    private MediaService mediaService;
    @Mock
    private TimelineService timelineService;
    @Mock
    private UserService userService;
    @Mock
    private RedisService redisService;
    @Mock
    private InteractService interactService;
    @Mock
    private ExamSubscriptionServiceImpl examSubscriptionService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test void replacementFreezesPublicEventChangesBeforeSubjectChangesAgain() {
        var notifications = org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotifyService.class);
        var service = new ExamServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class),
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, timelineService, userService,
                redisService, interactService, examSubscriptionService, notifications,
                new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
        var current = ExamInfo.builder().id(88L).contractVersion(1L).publishStatus(1).lifecycleStatus(0).build();
        var saved = ExamInfo.builder().id(88L).contractVersion(2L).publishStatus(1).lifecycleStatus(0)
                .title("frozen event").summary("summary").eventType(1).sourceName("source").sourceUrl("https://example.com").build();
        var before = cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO.builder().id(12L).nodeType("PUBLIC_EVENT_START")
                .label("start").startPrecision(2).startTime(java.time.LocalDateTime.of(2030, 1, 1, 15, 0)).location("old place").build();
        var after = cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO.builder().id(12L).nodeType("PUBLIC_EVENT_START")
                .label("start").startPrecision(2).startTime(java.time.LocalDateTime.of(2030, 1, 1, 17, 0)).location("new place").build();
        when(examInfoMapper.selectForUpdate(88L)).thenReturn(current, saved);
        when(examConverter.toOperationsEntity(any())).thenReturn(saved);
        when(examInfoMapper.saveOperationsFields(saved)).thenReturn(1);
        when(examConverter.toDetailBO(saved)).thenReturn(ExamDetailBO.builder().id(88L).build());
        when(timelineService.listTimelinesByTarget(TargetType.EXAM, 88L)).thenReturn(List.of(before), List.of(after));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.replaceAdminPublicEvent(cn.jualn.miniapp.module.exam.bo.AdminPublicEventSaveBO.builder()
                    .id(88L).operatorId(9L).canonicalFullReplacement(true).title("frozen event").contactsJson("[]")
                    .timeline(List.of()).sections(List.of()).actions(List.of()).attachmentLinks(List.of()).build(),
                    cn.jualn.miniapp.common.web.StrongEtag.of("public-event", 88L, 1L));
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
        var time = org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot.class);
        var location = org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot.class);
        verify(notifications).enqueueBusinessNotification(org.mockito.ArgumentMatchers.eq(TargetType.EXAM), org.mockito.ArgumentMatchers.eq(88L),
                any(), org.mockito.ArgumentMatchers.eq(cn.jualn.miniapp.common.enums.NotifyType.PUBLIC_EVENT_TIME_CHANGED),
                org.mockito.ArgumentMatchers.eq("SUBSCRIBERS"), any(), any(), time.capture());
        verify(notifications).enqueueBusinessNotification(org.mockito.ArgumentMatchers.eq(TargetType.EXAM), org.mockito.ArgumentMatchers.eq(88L),
                any(), org.mockito.ArgumentMatchers.eq(cn.jualn.miniapp.common.enums.NotifyType.PUBLIC_EVENT_LOCATION_CHANGED),
                org.mockito.ArgumentMatchers.eq("SUBSCRIBERS"), any(), any(), location.capture());
        after.setLocation("third place"); saved.setTitle("changed later");
        org.junit.jupiter.api.Assertions.assertTrue(time.getValue().presentation().changes().get(0).before().contains("15:00"));
        org.junit.jupiter.api.Assertions.assertTrue(time.getValue().presentation().changes().get(0).after().contains("17:00"));
        assertEquals("old place", location.getValue().presentation().changes().get(0).before());
        assertEquals("new place", location.getValue().presentation().changes().get(0).after());
        assertEquals("frozen event", time.getValue().presentation().context());
        assertEquals("frozen event", time.getValue().presentation().subjectTitle());
    }

    @Test
    void pageExam_shouldDefaultToPublishedAndSetNextCursor() {
        ExamServiceImpl service = new ExamServiceImpl(
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class),
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, timelineService, userService, redisService, interactService, examSubscriptionService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotifyService.class), new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()));

        ExamInfo first = ExamInfo.builder().id(20L).userId(7L).publishStatus(1).build();
        ExamInfo second = ExamInfo.builder().id(10L).userId(8L).publishStatus(1).build();
        when(examInfoMapper.selectPageExams(ExamStatus.PUBLISHED.getCode(), 3, null, null, null, 50L, 2)).thenReturn(List.of(first, second));
        when(examConverter.toDetailList(any())).thenReturn(List.of(
                ExamDetailBO.builder().id(20L).build(),
                ExamDetailBO.builder().id(10L).build()
        ));

        ExamPageBO query = ExamPageBO.builder()
                .pageSize(1)
                .status(null)
                .category(3)
                .lastId(50L)
                .keyword("   ")
                .build();

        var result = service.pageExam(query);

        assertEquals(10L, result.getNextCursor());
        verify(examInfoMapper).selectPageExams(ExamStatus.PUBLISHED.getCode(), 3, null, null, null, 50L, 2);
    }

    @Test
    void getExamDetail_shouldRejectUnpublishedBeforeLoadingChildren() {
        ExamServiceImpl service = new ExamServiceImpl(
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class),
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, timelineService, userService,
                redisService, interactService, examSubscriptionService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotifyService.class), new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
        when(examInfoMapper.selectByIdNotDeleted(88L)).thenReturn(ExamInfo.builder().id(88L).publishStatus(2).build());
        org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,
                () -> service.getExamDetail(88L));
        org.mockito.Mockito.verifyNoInteractions(redisService, mediaService);
    }

    @Test
    void getExamDetail_shouldLoadAndReturnDetailWhenCacheMiss() {
        UserContext.setUserId(99L);
        ExamServiceImpl service = new ExamServiceImpl(
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class),
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, timelineService, userService, redisService, interactService, examSubscriptionService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotifyService.class), new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()));

        ExamInfo examInfo = ExamInfo.builder().id(88L).userId(20L).publishStatus(1).build();
        ExamDetailBO detailBO = ExamDetailBO.builder().id(88L).build();
        when(examInfoMapper.selectByIdNotDeleted(88L)).thenReturn(examInfo);
        when(examConverter.toDetailBO(examInfo)).thenReturn(detailBO);
        when(mediaService.listAttachments(TargetType.EXAM, 88L)).thenReturn(List.of());
        when(timelineService.listTimelinesByTarget(TargetType.EXAM, 88L)).thenReturn(List.of());
        when(interactService.isLiked(TargetType.EXAM, 88L)).thenReturn(false);
        when(examSubscriptionService.isSubscribed(88L)).thenReturn(false);

        ExamDetailBO result = service.getExamDetail(88L);

        assertEquals(detailBO, result);
        verify(mediaService).listAttachments(TargetType.EXAM, 88L);
        verify(timelineService).listTimelinesByTarget(TargetType.EXAM, 88L);
    }

}


