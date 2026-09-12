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
    @Test void legacyContentAppearsWithoutBackfillWrite() {
        when(sections.selectList(any())).thenReturn(List.of());
        assertEquals("旧正文", service.sections(TargetType.ACTIVITY, 1L, "旧正文").get(0).getContent());
        verify(sections, never()).insert(any(EventSection.class));
    }
    @Test void legacyEditorCannotFlattenStructuredSections() {
        when(sections.selectList(any())).thenReturn(List.of(
                EventSection.builder().sectionType("INTRO").title("介绍").content("甲").build(),
                EventSection.builder().sectionType("RULES").title("规则").content("乙").build()));
        assertThrows(BusinessException.class, () -> service.saveSections(TargetType.ACTIVITY, 1L, null, "旧页面覆盖"));
        verify(sections, never()).delete(any());
    }
    @Test void omittedBodyPreservesStructuredSections() {
        when(sections.selectList(any())).thenReturn(List.of(EventSection.builder().sectionType("RULES").title("规则").content("乙").build()));
        assertEquals("规则\n乙", service.saveSections(TargetType.ACTIVITY, 1L, null, null));
        verify(sections, never()).delete(any());
    }
    @Test void sectionProjectionKeepsTitlesAndText() {
        when(sections.selectList(any())).thenReturn(List.of());
        String body = service.saveSections(TargetType.ACTIVITY, 1L, List.of(
                EventSectionBO.builder().sectionType("INTRO").title("介绍").content("甲").build(),
                EventSectionBO.builder().sectionType("CUSTOM").title("服装").content("乙").build()), null);
        assertEquals("介绍\n甲\n\n服装\n乙", body);
        verify(sections, times(2)).insert(any(EventSection.class));
    }
    @Test void missingBodyRejected() {
        when(sections.selectList(any())).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.saveSections(TargetType.EXAM, 1L, null, null));
    }
    @Test void foreignAttachmentRejected() {
        when(media.listAttachments(TargetType.ACTIVITY, 1L)).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.saveActions(TargetType.ACTIVITY, 1L,
                List.of(EventActionBO.builder().actionType(5).label("加群").attachmentId(99L).build())));
        verify(actions, never()).insert(any(EventAction.class));
    }
    @Test void resolvesSameSaveObjectKey() {
        when(media.listAttachments(TargetType.ACTIVITY, 1L)).thenReturn(List.of(
                MediaAttachmentBO.builder().id(9L).type(MediaType.IMAGE).objectKey("activity/1/qr.png").build()));
        service.saveActions(TargetType.ACTIVITY, 1L, List.of(EventActionBO.builder().actionType(5).label("加群")
                .attachmentObjectKey("activity/1/qr.png").isRequired(true).build()));
        ArgumentCaptor<EventAction> captor = ArgumentCaptor.forClass(EventAction.class);
        verify(actions).insert(captor.capture());
        assertEquals(9L, captor.getValue().getAttachmentId());
        assertTrue(captor.getValue().getIsRequired());
    }
    @Test void qrCannotReferenceDocument() {
        when(media.listAttachments(TargetType.ACTIVITY, 1L)).thenReturn(List.of(MediaAttachmentBO.builder().id(9L).type(MediaType.PDF).build()));
        assertThrows(BusinessException.class, () -> service.saveActions(TargetType.ACTIVITY, 1L,
                List.of(EventActionBO.builder().actionType(5).label("加群").attachmentId(9L).build())));
    }
    @Test void rejectsUnsafeUrl() {
        when(media.listAttachments(TargetType.EXAM, 1L)).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.saveActions(TargetType.EXAM, 1L,
                List.of(EventActionBO.builder().actionType(1).label("官网").targetValue("javascript:alert(1)").build())));
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
