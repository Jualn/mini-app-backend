package cn.jualn.miniapp.module.publicevent.service.impl;

import cn.jualn.miniapp.module.publicevent.service.PublicEventTestServices;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.publicevent.enums.PublicEventStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.user.enums.UserRole;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventDetailBO;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventPageBO;
import cn.jualn.miniapp.module.publicevent.converter.PublicEventConverter;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventSubscriptionMapper;
import cn.jualn.miniapp.module.publicevent.vo.PublicEventLegacyDetailVO;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.media.TestMediaService;
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
class PublicEventServiceImplTest {

    @Mock
    private PublicEventMapper examInfoMapper;
    @Mock
    private PublicEventSubscriptionMapper examSubscriptionMapper;
    @Mock
    private PublicEventConverter examConverter;
    @Mock
    private TestMediaService mediaService;
    @Mock
    private TimelineService timelineService;
    @Mock
    private UserService userService;
    @Mock
    private RedisService redisService;
    @Mock
    private InteractionLikeQueries interactService;
    @Mock
    private PublicEventSubscriptionServiceImpl examSubscriptionService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test void replacementFreezesPublicEventChangesBeforeSubjectChangesAgain() {
        var notifications = org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotifyService.class);
        var service = new PublicEventTestServices(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class),
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, mediaService, timelineService, userService,
                redisService, interactService, examSubscriptionService, notifications,
                new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
        service.managementAudit(org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        var current = PublicEvent.builder().id(88L).contractVersion(1L).publishStatus(1).lifecycleStatus(0).build();
        var saved = PublicEvent.builder().id(88L).contractVersion(2L).publishStatus(1).lifecycleStatus(0)
                .title("frozen event").summary("summary").eventType(1).sourceName("source").sourceUrl("https://example.com").build();
        var before = cn.jualn.miniapp.module.timeline.bo.TimelineFactBO.builder().id(12L).nodeType("PUBLIC_EVENT_START")
                .label("start").startPrecision(2).startTime(java.time.LocalDateTime.of(2030, 1, 1, 15, 0)).location("old place").build();
        var after = cn.jualn.miniapp.module.timeline.bo.TimelineFactBO.builder().id(12L).nodeType("PUBLIC_EVENT_START")
                .label("start").startPrecision(2).startTime(java.time.LocalDateTime.of(2030, 1, 1, 17, 0)).location("new place").build();
        when(examInfoMapper.selectForUpdate(88L)).thenReturn(current, saved);
        when(examConverter.toOperationsEntity(any())).thenReturn(saved);
        when(examInfoMapper.saveOperationsFields(saved)).thenReturn(1);
        when(examConverter.toDetailBO(saved)).thenReturn(PublicEventDetailBO.builder().id(88L).build());
        when(timelineService.listTimelinesByTarget(TargetType.EXAM, 88L)).thenReturn(List.of(before), List.of(after));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.drafts().replaceAdminPublicEvent(cn.jualn.miniapp.module.publicevent.bo.AdminPublicEventSaveBO.builder()
                    .id(88L).operatorId(9L).canonicalFullReplacement(true).title("frozen event").contactsJson("[]")
                    .timeline(List.of()).sections(List.of()).actions(List.of()).attachmentLinks(List.of()).build(),
                    cn.jualn.miniapp.common.web.StrongEtag.of("public-event", 88L, 1L));
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
        var time = org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot.class);
        var location = org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot.class);
        verify(notifications).enqueueBusinessNotification(org.mockito.ArgumentMatchers.eq(TargetType.EXAM), org.mockito.ArgumentMatchers.eq(88L),
                any(), org.mockito.ArgumentMatchers.eq(cn.jualn.miniapp.module.notify.enums.NotifyType.PUBLIC_EVENT_TIME_CHANGED),
                org.mockito.ArgumentMatchers.eq("SUBSCRIBERS"), any(), any(), time.capture());
        verify(notifications).enqueueBusinessNotification(org.mockito.ArgumentMatchers.eq(TargetType.EXAM), org.mockito.ArgumentMatchers.eq(88L),
                any(), org.mockito.ArgumentMatchers.eq(cn.jualn.miniapp.module.notify.enums.NotifyType.PUBLIC_EVENT_LOCATION_CHANGED),
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
        PublicEventTestServices service = new PublicEventTestServices(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class),
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class),
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, mediaService, timelineService, userService, redisService, interactService, examSubscriptionService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotifyService.class), new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
        service.managementAudit(org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));

        PublicEvent first = PublicEvent.builder().id(20L).userId(7L).publishStatus(1).build();
        PublicEvent second = PublicEvent.builder().id(10L).userId(8L).publishStatus(1).build();
        when(examInfoMapper.selectPageExams(PublicEventStatus.PUBLISHED.getCode(), 3, null, null, null, 50L, 2)).thenReturn(List.of(first, second));
        when(examConverter.toDetailList(any())).thenReturn(List.of(
                PublicEventDetailBO.builder().id(20L).build(),
                PublicEventDetailBO.builder().id(10L).build()
        ));

        PublicEventPageBO query = PublicEventPageBO.builder()
                .pageSize(1)
                .status(null)
                .category(3)
                .lastId(50L)
                .keyword("   ")
                .build();

        var result = service.queries().pageExam(query);

        assertEquals(10L, result.getNextCursor());
        verify(examInfoMapper).selectPageExams(PublicEventStatus.PUBLISHED.getCode(), 3, null, null, null, 50L, 2);
    }

    @Test
    void getExamDetail_shouldRejectUnpublishedBeforeLoadingChildren() {
        PublicEventTestServices service = new PublicEventTestServices(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class),
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class),
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, mediaService, timelineService, userService,
                redisService, interactService, examSubscriptionService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotifyService.class), new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
        service.managementAudit(org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        when(examInfoMapper.selectByIdNotDeleted(88L)).thenReturn(PublicEvent.builder().id(88L).publishStatus(2).build());
        org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,
                () -> service.queries().getExamDetail(88L));
        org.mockito.Mockito.verifyNoInteractions(redisService, mediaService);
    }

    @Test
    void getExamDetail_shouldLoadAndReturnDetailWhenCacheMiss() {
        UserContext.setUserId(99L);
        PublicEventTestServices service = new PublicEventTestServices(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class),
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class),
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, mediaService, timelineService, userService, redisService, interactService, examSubscriptionService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotifyService.class), new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
        service.managementAudit(org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));

        PublicEvent examInfo = PublicEvent.builder().id(88L).userId(20L).publishStatus(1).build();
        PublicEventDetailBO detailBO = PublicEventDetailBO.builder().id(88L).build();
        when(examInfoMapper.selectByIdNotDeleted(88L)).thenReturn(examInfo);
        when(examConverter.toDetailBO(examInfo)).thenReturn(detailBO);
        when(mediaService.listAttachments(TargetType.EXAM, 88L)).thenReturn(List.of());
        when(timelineService.listTimelinesByTarget(TargetType.EXAM, 88L)).thenReturn(List.of());
        when(interactService.isLiked(TargetType.EXAM, 88L)).thenReturn(false);
        when(examSubscriptionService.isSubscribed(88L)).thenReturn(false);

        PublicEventDetailBO result = service.queries().getExamDetail(88L);

        assertEquals(detailBO, result);
        verify(mediaService).listAttachments(TargetType.EXAM, 88L);
        verify(timelineService).listTimelinesByTarget(TargetType.EXAM, 88L);
    }

}


