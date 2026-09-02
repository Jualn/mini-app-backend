package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
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
        when(mediaConverter.toMediaAttachmentList(any()))
                .thenReturn(List.of(MediaAttachment.builder().build()));

        mediaService.replaceAttachments(saveBO);

        AttachmentItemBO normalized = saveBO.getAttachments().get(0);
        assertEquals("post/7/image.jpg", normalized.getObjectKey());
        assertEquals("https://cos.example/post/7/image.jpg", normalized.getUrl());
        verify(mediaAttachmentMapper).insertBatch(any());
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
    void replaceAttachments_shouldDeleteRemovedObjectOnlyAfterCommit() {
        UserContext.setUserId(7L);
        when(mediaAttachmentMapper.selectList(any())).thenReturn(List.of(
                MediaAttachment.builder()
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
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

        verify(cosService).deleteObject("post/7/old.jpg");
    }

    @Test
    void replaceAttachments_shouldRecoverObjectKeyFromExistingLegacyUrl() {
        UserContext.setUserId(7L);
        String publicUrl = "https://cos.example/post/7/kept.jpg";
        when(mediaAttachmentMapper.selectList(any())).thenReturn(List.of(
                MediaAttachment.builder()
                        .objectKey("post/7/kept.jpg")
                        .url(publicUrl)
                        .build()));
        when(cosService.buildPublicUrl("post/7/kept.jpg")).thenReturn(publicUrl);
        when(mediaConverter.toMediaAttachmentList(any()))
                .thenReturn(List.of(MediaAttachment.builder().build()));

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
                MediaAttachment.builder()
                        .objectKey("post/8/existing.jpg")
                        .url(publicUrl)
                        .build()));
        when(cosService.buildPublicUrl("post/8/existing.jpg")).thenReturn(publicUrl);
        when(mediaConverter.toMediaAttachmentList(any()))
                .thenReturn(List.of(MediaAttachment.builder().build()));

        mediaService.replaceAttachments(MediaAttachmentSaveBO.builder()
                .targetType(TargetType.POST)
                .targetId(11L)
                .attachments(List.of(AttachmentItemBO.builder()
                        .type(MediaType.IMAGE)
                        .objectKey("post/8/existing.jpg")
                        .url(publicUrl)
                        .build()))
                .build());

        verify(mediaAttachmentMapper).insertBatch(any());
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
