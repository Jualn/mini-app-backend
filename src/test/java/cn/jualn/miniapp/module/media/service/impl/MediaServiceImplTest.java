package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.module.media.service.MediaUploadRecordService;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceImplTest {

    @Mock
    private MediaConverter mediaConverter;
    @Mock
    private MediaAttachmentMapper mediaAttachmentMapper;
    @Mock
    private TargetValidator targetValidator;
    @Mock
    private CosService cosService;
    @Mock
    private MediaUploadRecordService uploadRecordService;

    private MediaServiceImpl mediaService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MediaAttachment.class);
        org.mockito.Mockito.lenient().when(mediaAttachmentMapper.insert(any(MediaAttachment.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(mediaAttachmentMapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.lenient().when(mediaConverter.toMediaAttachmentList(any())).thenAnswer(invocation ->
                ((MediaAttachmentSaveBO) invocation.getArgument(0)).getAttachments().stream().map(a ->
                        MediaAttachment.builder().type(a.getType().getCode()).objectKey(a.getObjectKey())
                                .url(a.getUrl()).originalName(a.getOriginalName()).sortOrder(a.getSortOrder()).build()).toList());
        mediaService = new MediaServiceImpl(
                mediaConverter, mediaAttachmentMapper, targetValidator, cosService, uploadRecordService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void generateUploadCredential_shouldBuildKeysInsideCurrentUserPrefix() {
        UserContext.setUserId(7L);
        CosUploadCredentialDTO credential = CosUploadCredentialDTO.builder().build();
        when(cosService.generateUploadCredential(any())).thenReturn(credential);

        assertEquals(credential,
                mediaService.generateUploadCredential(TargetType.POST, List.of("My Pic.JPG")));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(cosService).generateUploadCredential(captor.capture());
        String objectKey = captor.getValue().get(0);
        assertTrue(objectKey.startsWith("post/7/"));
        assertTrue(objectKey.endsWith("_mypic.jpg"));
    }

    @Test
    void registerAttachmentStoresImmutableCanonicalMetadata() {
        mediaService.registerAttachment("POSTER", " 招新海报 ", "https://cdn.example/poster.png", 9L);

        ArgumentCaptor<MediaAttachment> captor = ArgumentCaptor.forClass(MediaAttachment.class);
        verify(mediaAttachmentMapper).insert(captor.capture());
        assertEquals("POSTER", captor.getValue().getKind());
        assertEquals(MediaType.IMAGE.getCode(), captor.getValue().getType());
        assertEquals("招新海报", captor.getValue().getOriginalName());
        assertEquals(9L, captor.getValue().getRegisteredBy());
    }

    @Test
    void adminCredentialUsesExplicitOperatorEvenWhenUserContextIsDifferent() {
        UserContext.setUserId(7L);
        when(cosService.generateUploadCredential(any())).thenReturn(CosUploadCredentialDTO.builder().build());
        mediaService.generateAdminUploadCredential(TargetType.EXAM, List.of("file.pdf"), 9L);
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(cosService).generateUploadCredential(captor.capture());
        assertTrue(captor.getValue().get(0).startsWith("exam/9/"));
        verify(uploadRecordService).recordPending(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(TargetType.EXAM), any(), any());
    }

    @Test
    void replaceAttachmentLinksValidatesRegistryBeforeReplacing() {
        var entity = MediaAttachment.builder().id(31L).registered(true).build();
        when(mediaAttachmentMapper.selectBatchIds(any())).thenReturn(List.of(entity));
        when(mediaAttachmentMapper.selectById(31L)).thenReturn(entity);
        when(mediaConverter.toBOList(List.of(entity))).thenReturn(List.of(MediaAttachmentBO.builder().id(31L).build()));

        mediaService.replaceAttachmentLinks(TargetType.ACTIVITY, 7L, List.of(new AttachmentLinkBO(31L, 0)));

        verify(mediaAttachmentMapper).deleteLinks(TargetType.ACTIVITY.getCode(), 7L);
        verify(mediaAttachmentMapper).insertLink(TargetType.ACTIVITY.getCode(), 7L, 31L, 0);
    }

    @Test
    void generateUploadCredential_shouldRejectEmptyFileNames() {
        UserContext.setUserId(7L);

        assertThrows(BusinessException.class,
                () -> mediaService.generateUploadCredential(TargetType.POST, List.of()));
        verify(cosService, never()).generateUploadCredential(any());
    }

    @Test
    void replaceAttachments_shouldDeriveTrustedUrlFromOwnedObjectKey() {
        UserContext.setUserId(7L);
        AttachmentItemBO item = AttachmentItemBO.builder()
                .type(MediaType.IMAGE)
                .objectKey("post/7/image.jpg")
                .url("https://untrusted.example/image.jpg")
                .originalName("image.jpg")
                .sortOrder(0)
                .build();
        MediaAttachmentSaveBO saveBO = MediaAttachmentSaveBO.builder()
                .targetType(TargetType.POST)
                .targetId(11L)
                .attachments(List.of(item))
                .build();
        when(cosService.buildPublicUrl("post/7/image.jpg"))
                .thenReturn("https://cos.example/post/7/image.jpg");

        mediaService.replaceAttachments(saveBO);

        AttachmentItemBO normalized = saveBO.getAttachments().get(0);
        assertEquals("post/7/image.jpg", normalized.getObjectKey());
        assertEquals("https://cos.example/post/7/image.jpg", normalized.getUrl());
        verify(mediaAttachmentMapper).insert(any(MediaAttachment.class));
    }

    @Test
    void replaceAttachments_shouldRejectAnotherUsersObjectKey() {
        UserContext.setUserId(7L);
        MediaAttachmentSaveBO saveBO = MediaAttachmentSaveBO.builder()
                .targetType(TargetType.POST)
                .targetId(11L)
                .attachments(List.of(AttachmentItemBO.builder()
                        .type(MediaType.IMAGE)
                        .objectKey("post/8/image.jpg")
                        .url("https://cos.example/post/8/image.jpg")
                        .build()))
                .build();

        assertThrows(BusinessException.class, () -> mediaService.replaceAttachments(saveBO));
        verify(mediaConverter, never()).toMediaAttachmentList(any());
    }

    @Test
    void replaceAttachments_shouldPersistDeletionIntentWithoutRemoteCall() {
        UserContext.setUserId(7L);
        when(mediaAttachmentMapper.selectList(any())).thenReturn(List.of(
                MediaAttachment.builder().id(41L).type(MediaType.IMAGE.getCode())
                        .objectKey("post/7/old.jpg")
                        .url("https://cos.example/post/7/old.jpg")
                        .build()));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        mediaService.replaceAttachments(MediaAttachmentSaveBO.builder()
                .targetType(TargetType.POST)
                .targetId(11L)
                .attachments(List.of())
                .build());

        verify(cosService, never()).deleteObject(any());
        assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size());
        verify(uploadRecordService).requestDeletion("post/7/old.jpg", TargetType.POST, 11L);
    }

    @Test
    void replaceAttachments_shouldRecoverObjectKeyFromExistingLegacyUrl() {
        UserContext.setUserId(7L);
        String publicUrl = "https://cos.example/post/7/kept.jpg";
        when(mediaAttachmentMapper.selectList(any())).thenReturn(List.of(
                MediaAttachment.builder().id(41L).type(MediaType.IMAGE.getCode())
                        .objectKey("post/7/kept.jpg")
                        .url(publicUrl)
                        .build()));
        when(cosService.buildPublicUrl("post/7/kept.jpg")).thenReturn(publicUrl);

        MediaAttachmentSaveBO saveBO = MediaAttachmentSaveBO.builder()
                .targetType(TargetType.POST)
                .targetId(11L)
                .attachments(List.of(AttachmentItemBO.builder()
                        .type(MediaType.IMAGE)
                        .url(publicUrl)
                        .build()))
                .build();

        mediaService.replaceAttachments(saveBO);

        assertEquals("post/7/kept.jpg", saveBO.getAttachments().get(0).getObjectKey());
        verify(cosService, never()).deleteObject(any());
    }

    @Test
    void replaceAttachments_shouldAllowAuthorizedCallerToRetainExistingObject() {
        String publicUrl = "https://cos.example/post/8/existing.jpg";
        when(mediaAttachmentMapper.selectList(any())).thenReturn(List.of(
                MediaAttachment.builder().id(41L).type(MediaType.IMAGE.getCode())
                        .objectKey("post/8/existing.jpg")
                        .url(publicUrl)
                        .build()));
        when(cosService.buildPublicUrl("post/8/existing.jpg")).thenReturn(publicUrl);

        mediaService.replaceAttachments(MediaAttachmentSaveBO.builder()
                .targetType(TargetType.POST)
                .targetId(11L)
                .attachments(List.of(AttachmentItemBO.builder()
                        .type(MediaType.IMAGE)
                        .objectKey("post/8/existing.jpg")
                        .url(publicUrl)
                        .build()))
                .build());

        verify(mediaAttachmentMapper).update(any(), any());
        verify(cosService, never()).deleteObject(any());
    }

    @Test
    void replaceAttachments_shouldRejectNewObjectWithoutUploadPrincipal() {
        MediaAttachmentSaveBO saveBO = MediaAttachmentSaveBO.builder()
                .targetType(TargetType.POST)
                .targetId(11L)
                .attachments(List.of(AttachmentItemBO.builder()
                        .type(MediaType.IMAGE)
                        .objectKey("post/8/new.jpg")
                        .url("https://cos.example/post/8/new.jpg")
                        .build()))
                .build();

        assertThrows(BusinessException.class, () -> mediaService.replaceAttachments(saveBO));
        verify(mediaConverter, never()).toMediaAttachmentList(any());
    }

}
