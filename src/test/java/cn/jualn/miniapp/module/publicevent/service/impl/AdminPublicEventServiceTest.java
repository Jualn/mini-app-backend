package cn.jualn.miniapp.module.publicevent.service.impl;

import cn.jualn.miniapp.module.publicevent.service.PublicEventTestServices;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.eventcontent.service.EventContactCodec;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventDetailBO;
import cn.jualn.miniapp.module.publicevent.converter.PublicEventConverter;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventSubscriptionMapper;
import cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPublicEventServiceTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void cancelKeepsPublishedAndChangesOnlyLifecycle() {
        var mapper = mock(PublicEventMapper.class);
        var converter = mock(PublicEventConverter.class);
        var media = mock(TestMediaService.class);
        var timeline = mock(TimelineService.class);
        var content = mock(EventContentService.class);
        var notify = mock(NotifyService.class);
        var current = PublicEvent.builder().id(12L).title("事项").contractVersion(5L)
                .publishStatus(1).lifecycleStatus(0).build();
        var changed = PublicEvent.builder().id(12L).title("事项").contractVersion(6L)
                .publishStatus(1).lifecycleStatus(2).build();
        when(mapper.selectForUpdate(12L)).thenReturn(current, changed);
        when(mapper.transitionContract(12L, 5L, 1, 2, false)).thenReturn(1);
        when(converter.toDetailBO(changed)).thenReturn(PublicEventDetailBO.builder().id(12L)
                .contractVersion(6L).publishStatus(1).lifecycleStatus(2).build());
        when(media.listAttachments(TargetType.EXAM, 12L)).thenReturn(List.of());
        when(timeline.listTimelinesByTarget(TargetType.EXAM, 12L)).thenReturn(List.of());
        when(content.sections(TargetType.EXAM, 12L)).thenReturn(List.of());
        when(content.actions(TargetType.EXAM, 12L)).thenReturn(List.of());
        var service = new PublicEventTestServices(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), content, mapper, mock(PublicEventSubscriptionMapper.class), converter,
                media,
                media, timeline, mock(UserService.class), mock(RedisService.class), mock(InteractionLikeQueries.class),
                mock(PublicEventSubscriptionService.class), notify,
                new EventContactCodec(new ObjectMapper()));
        service.managementAudit(mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        TransactionSynchronizationManager.initSynchronization();

        var result = service.lifecycle().transitionAdminPublicEvent(
                12L, 9L, StrongEtag.of("public-event", 12L, 5L), "cancel");

        assertEquals(1, result.getPublishStatus());
        assertEquals(2, result.getLifecycleStatus());
        verify(mapper).transitionContract(12L, 5L, 1, 2, false);
    }

    @Test
    void publishFailureContainsContractFieldPointers() {
        var mapper = mock(PublicEventMapper.class);
        var current = PublicEvent.builder().id(12L).title("事项").contractVersion(5L)
                .publishStatus(0).lifecycleStatus(0).build();
        when(mapper.selectForUpdate(12L)).thenReturn(current);
        var service = new PublicEventTestServices(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), mock(EventContentService.class), mapper,
                mock(PublicEventSubscriptionMapper.class), mock(PublicEventConverter.class), mock(TestMediaService.class), mock(TestMediaService.class),
                mock(TimelineService.class), mock(UserService.class), mock(RedisService.class),
                mock(InteractionLikeQueries.class), mock(PublicEventSubscriptionService.class), mock(NotifyService.class),
                new EventContactCodec(new ObjectMapper()));

        var problem = assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                () -> service.lifecycle().transitionAdminPublicEvent(
                        12L, 9L, StrongEtag.of("public-event", 12L, 5L), "publish"));

        assertEquals("/problems/publish-validation-failed", problem.getType());
        assertEquals(List.of("/summary", "/type", "/sourceName", "/sourceUrl"),
                problem.getErrors().stream().map(
                        cn.jualn.miniapp.common.exception.ContractProblemException.Violation::pointer).toList());
    }
}
