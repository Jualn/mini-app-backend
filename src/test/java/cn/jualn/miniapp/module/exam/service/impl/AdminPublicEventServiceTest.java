package cn.jualn.miniapp.module.exam.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.eventcontent.service.EventContactCodec;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.converter.ExamConverter;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamSubscriptionMapper;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.service.MediaService;
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
        var mapper = mock(ExamInfoMapper.class);
        var converter = mock(ExamConverter.class);
        var media = mock(MediaService.class);
        var timeline = mock(TimelineService.class);
        var content = mock(EventContentService.class);
        var notify = mock(NotifyService.class);
        var current = ExamInfo.builder().id(12L).title("事项").contractVersion(5L)
                .publishStatus(1).lifecycleStatus(0).build();
        var changed = ExamInfo.builder().id(12L).title("事项").contractVersion(6L)
                .publishStatus(1).lifecycleStatus(2).build();
        when(mapper.selectForUpdate(12L)).thenReturn(current, changed);
        when(mapper.transitionContract(12L, 5L, 1, 2, false)).thenReturn(1);
        when(converter.toDetailBO(changed)).thenReturn(ExamDetailBO.builder().id(12L)
                .contractVersion(6L).publishStatus(1).lifecycleStatus(2).build());
        when(media.listAttachments(TargetType.EXAM, 12L)).thenReturn(List.of());
        when(timeline.listTimelinesByTarget(TargetType.EXAM, 12L)).thenReturn(List.of());
        when(content.sections(TargetType.EXAM, 12L)).thenReturn(List.of());
        when(content.actions(TargetType.EXAM, 12L)).thenReturn(List.of());
        var service = new ExamServiceImpl(content, mapper, mock(ExamSubscriptionMapper.class), converter,
                media, timeline, mock(UserService.class), mock(RedisService.class), mock(InteractService.class),
                mock(ExamSubscriptionService.class), notify,
                new EventContactCodec(new ObjectMapper()));
        TransactionSynchronizationManager.initSynchronization();

        var result = service.transitionAdminPublicEvent(
                12L, 9L, StrongEtag.of("public-event", 12L, 5L), "cancel");

        assertEquals(1, result.getPublishStatus());
        assertEquals(2, result.getLifecycleStatus());
        verify(mapper).transitionContract(12L, 5L, 1, 2, false);
    }

    @Test
    void publishFailureContainsContractFieldPointers() {
        var mapper = mock(ExamInfoMapper.class);
        var current = ExamInfo.builder().id(12L).title("事项").contractVersion(5L)
                .publishStatus(0).lifecycleStatus(0).build();
        when(mapper.selectForUpdate(12L)).thenReturn(current);
        var service = new ExamServiceImpl(mock(EventContentService.class), mapper,
                mock(ExamSubscriptionMapper.class), mock(ExamConverter.class), mock(MediaService.class),
                mock(TimelineService.class), mock(UserService.class), mock(RedisService.class),
                mock(InteractService.class), mock(ExamSubscriptionService.class), mock(NotifyService.class),
                new EventContactCodec(new ObjectMapper()));

        var problem = assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                () -> service.transitionAdminPublicEvent(
                        12L, 9L, StrongEtag.of("public-event", 12L, 5L), "publish"));

        assertEquals("/problems/publish-validation-failed", problem.getType());
        assertEquals(List.of("/summary", "/type", "/sourceName", "/sourceUrl"),
                problem.getErrors().stream().map(
                        cn.jualn.miniapp.common.exception.ContractProblemException.Violation::pointer).toList());
    }
}
