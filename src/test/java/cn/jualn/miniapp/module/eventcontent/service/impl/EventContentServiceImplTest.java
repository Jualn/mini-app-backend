package cn.jualn.miniapp.module.eventcontent.service.impl;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.eventcontent.bo.*;
import cn.jualn.miniapp.module.eventcontent.entity.*;
import cn.jualn.miniapp.module.eventcontent.mapper.*;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class EventContentServiceImplTest {
    EventSectionMapper sections;
    EventActionMapper actions;
    MediaService media;
    EventContentServiceImpl service;
    @BeforeEach void setup() {
        for (Class<?> type : List.of(EventSection.class, EventAction.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        sections = mock(EventSectionMapper.class); actions = mock(EventActionMapper.class); media = mock(MediaService.class);
        service = new EventContentServiceImpl(sections, actions, media);
    }
    @Test void emptySectionsRemainEmptyWithoutLegacyFallback() {
        when(sections.selectList(any())).thenReturn(List.of());
        assertTrue(service.sections(TargetType.ACTIVITY, 1L).isEmpty());
        verify(sections, never()).insert(any(EventSection.class));
    }
    @Test void savesCanonicalSectionsWithoutSubjectProjection() {
        service.saveSections(TargetType.ACTIVITY, 1L, List.of(
                EventSectionBO.builder().sectionKey("intro").title("介绍").content("甲").build(),
                EventSectionBO.builder().sectionKey("clothing").title("服装").content("乙").build()));
        verify(sections, times(2)).insert(any(EventSection.class));
    }
    @Test void missingSectionsRejected() {
        assertThrows(BusinessException.class, () -> service.saveSections(TargetType.EXAM, 1L, null));
    }
    @Test void foreignAttachmentRejected() {
        when(media.listAttachments(TargetType.ACTIVITY, 1L)).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.saveActions(TargetType.ACTIVITY, 1L,
                List.of(EventActionBO.builder().actionKey("join").actionType(5).label("加群").attachmentId(99L).build())));
        verify(actions, never()).insert(any(EventAction.class));
    }
    @Test void resolvesSameSaveObjectKey() {
        when(media.listAttachments(TargetType.ACTIVITY, 1L)).thenReturn(List.of(
                MediaAttachmentBO.builder().id(9L).type(MediaType.IMAGE).objectKey("activity/1/qr.png").build()));
        service.saveActions(TargetType.ACTIVITY, 1L, List.of(EventActionBO.builder().actionKey("join").actionType(5).label("加群")
                .attachmentObjectKey("activity/1/qr.png").isRequired(true).build()));
        ArgumentCaptor<EventAction> captor = ArgumentCaptor.forClass(EventAction.class);
        verify(actions).insert(captor.capture());
        assertEquals(9L, captor.getValue().getAttachmentId());
        assertTrue(captor.getValue().getIsRequired());
    }
    @Test void viewAttachmentCanReferenceDocument() {
        when(media.listAttachments(TargetType.ACTIVITY, 1L)).thenReturn(List.of(MediaAttachmentBO.builder().id(9L).type(MediaType.PDF).build()));
        service.saveActions(TargetType.ACTIVITY, 1L,
                List.of(EventActionBO.builder().actionKey("view").actionType(5).label("查看附件").attachmentId(9L).build()));
        verify(actions).insert(any(EventAction.class));
    }
    @Test void groupAndOtherActionsCanUseOnlyInstructions() {
        service.saveActions(TargetType.ACTIVITY, 1L, List.of(
                EventActionBO.builder().actionKey("group").actionType(2).label("加群")
                        .description("请按通知中的群号联系主办方").build(),
                EventActionBO.builder().actionKey("onsite").actionType(8).label("现场办理")
                        .description("请到服务台填写报名表").build()));
        var captor = ArgumentCaptor.forClass(EventAction.class);
        verify(actions, times(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(action -> action.getTargetValue() == null));
    }

    @Test void externalRegistrationActionStillRequiresHttpUrl() {
        assertThrows(BusinessException.class, () -> service.saveActions(TargetType.ACTIVITY, 1L,
                List.of(EventActionBO.builder().actionKey("web").actionType(7).label("网页报名")
                        .description("请报名").build())));
        verify(actions, never()).insert(any(EventAction.class));
    }

    @Test void rejectsUnsafeUrl() {
        when(media.listAttachments(TargetType.EXAM, 1L)).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.saveActions(TargetType.EXAM, 1L,
                List.of(EventActionBO.builder().actionKey("official").actionType(1).label("官网").targetValue("javascript:alert(1)").build())));
    }
    @Test void omittedActionsDoNotDeleteExistingReferences() {
        service.prepareActions(TargetType.ACTIVITY, 1L, null);
        verifyNoInteractions(actions);
        service.prepareActions(TargetType.ACTIVITY, 1L, List.of());
        verify(actions).delete(any());
    }
    @Test void coverMustBeOwnedImage() {
        when(media.listAttachments(TargetType.ACTIVITY, 1L)).thenReturn(List.of(MediaAttachmentBO.builder().id(9L).type(MediaType.PDF).build()));
        assertThrows(BusinessException.class, () -> service.validateCover(TargetType.ACTIVITY, 1L, 9L));
    }
}
