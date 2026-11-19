package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.module.media.bo.AttachmentTargetBO;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.module.media.service.*;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.third.cos.service.CosService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaAuthorizationTest {
    final RbacAuthorizationReader reader=mock(RbacAuthorizationReader.class);
    final MediaAttachmentMapper attachments=mock(MediaAttachmentMapper.class);
    final MediaUploadRecordService uploads=mock(MediaUploadRecordService.class);
    final CosService cos=mock(CosService.class);
    final MediaConverter converter=mock(MediaConverter.class);
    @org.junit.jupiter.api.BeforeEach void metadata() {
        var entity=cn.jualn.miniapp.module.media.entity.MediaAttachment.builder().id(21L).registeredBy(9L).build();
        when(attachments.selectById(21L)).thenReturn(entity);
        when(converter.toBO(entity)).thenReturn(cn.jualn.miniapp.module.media.bo.MediaAttachmentBO.builder().id(21L).registeredBy(9L).build());
    }
    final MediaServiceImpl service=new MediaServiceImpl(new CurrentAuthorization(reader),converter,
            attachments,mock(SubjectExistenceService.class),cos,uploads,mock(ArticleMediaService.class));
    final MediaAccess access=new MediaAccessService(new CurrentAuthorization(reader),attachments,
            new MediaQueriesService(converter,attachments,mock(SubjectExistenceService.class),mock(ArticleMediaService.class)),uploads,mock(ArticleMediaService.class));
    void grants(String space) {
        when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),
                List.of(new Grant("a","test",space,"GLOBAL",List.of("media:read","media:write"),null))));
    }
    @Test void registeredUploaderNeedsCurrentPermissionInTheDurablePurpose() {
        grants("ACTIVITY");when(uploads.registeredPurpose(21L,9)).thenReturn(TargetType.ACTIVITY);
        assertDoesNotThrow(()->access.authorizeReusableRead(21L,9));
        grants("PUBLIC_EVENT");assertThrows(BusinessException.class,()->access.authorizeReusableRead(21L,9));
        grants("ACTIVITY");when(uploads.registeredPurpose(21L,9)).thenReturn(null);
        assertThrows(BusinessException.class,()->access.authorizeReusableRead(21L,9));
    }
    @Test void privateUnknownAndUnregisteredMediaDoNotAcquireGlobalScope() {
        grants("ACTIVITY");
        for(TargetType type:List.of(TargetType.USER,TargetType.COMMENT,TargetType.ARTICLE)) {
            when(uploads.registeredPurpose(21L,9)).thenReturn(type);
            assertThrows(BusinessException.class,()->access.authorizeReusableRead(21L,9));
        }
        assertThrows(ContractProblemException.class,()->service.registerAttachment("LINK","external","https://synthetic.invalid/file",9L));
        verify(attachments,never()).insert(any(cn.jualn.miniapp.module.media.entity.MediaAttachment.class));
    }
    @Test void anotherUploaderBindingCannotBorrowTheCurrentActorsUploadPurpose() {
        var foreign=cn.jualn.miniapp.module.media.entity.MediaAttachment.builder().id(21L).registeredBy(10L).build();
        when(attachments.selectById(21L)).thenReturn(foreign);
        when(converter.toBO(foreign)).thenReturn(cn.jualn.miniapp.module.media.bo.MediaAttachmentBO.builder().id(21L).registeredBy(10L).build());
        grants("ACTIVITY");when(attachments.selectTargets(21L)).thenReturn(List.of(new AttachmentTargetBO(3,44L)));
        assertThrows(BusinessException.class,()->access.authorizeReusableRead(21L,9));
        verify(uploads,never()).registeredPurpose(any(),anyLong());
    }
}
