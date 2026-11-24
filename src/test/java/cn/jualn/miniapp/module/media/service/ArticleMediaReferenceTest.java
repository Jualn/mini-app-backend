package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.service.ArticleAuthorization;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.module.media.mapper.*;
import cn.jualn.miniapp.third.cos.service.CosService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArticleMediaReferenceTest {
    @Test void articleCanSaveBasicVideoWithoutWeakeningImageAndResourceChecks() {
        var mapper = mock(MediaAttachmentMapper.class);
        var converter = mock(MediaConverter.class);
        var service = new ArticleMediaService(mapper, mock(MediaUploadRecordMapper.class), converter,
                mock(CosService.class), mock(MediaUploadRecordService.class), mock(ArticleMediaWriter.class),
                mock(ManagedArticleMediaInspector.class), mock(ArticleAuthorization.class));
        var video = MediaAttachment.builder().id(1L).registered(true).verified(true).kind("VIDEO")
                .objectKey("article/retained/9/video").contentType("video/mp4").sizeBytes(12L).build();
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(video));
        when(converter.toBOList(anyList())).thenReturn(List.of(MediaAttachmentBO.builder().id(1L).build()));
        assertEquals(1, service.requireAttachments(Map.of(1L, "VIDEO")).size());
        video.setWidth(1920);
        assertThrows(ContractProblemException.class, () -> service.requireAttachments(Map.of(1L, "VIDEO")));
        video.setHeight(1080); video.setDurationSeconds(30d);
        assertDoesNotThrow(() -> service.requireAttachments(Map.of(1L, "VIDEO")));
        video.setVerified(false);
        assertThrows(ContractProblemException.class, () -> service.requireAttachments(Map.of(1L, "VIDEO")));
        video.setVerified(true); video.setSizeBytes(209715201L);
        assertThrows(ContractProblemException.class, () -> service.requireAttachments(Map.of(1L, "VIDEO")));
        video.setSizeBytes(12L); video.setKind("IMAGE"); video.setContentType("image/png"); video.setWidth(null);
        assertThrows(ContractProblemException.class, () -> service.requireAttachments(Map.of(1L, "IMAGE")));
    }
}
