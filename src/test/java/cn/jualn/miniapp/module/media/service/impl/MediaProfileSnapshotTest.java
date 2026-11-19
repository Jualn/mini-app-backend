package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.module.media.service.MediaUploadRecordService;
import cn.jualn.miniapp.third.cos.service.CosService;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MediaProfileSnapshotTest {
    private final CosService cos = mock(CosService.class);
    private final MediaUploadRecordService records = mock(MediaUploadRecordService.class);
    private final MediaServiceImpl media = new MediaServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), mock(MediaConverter.class), mock(MediaAttachmentMapper.class),
            mock(SubjectExistenceService.class), cos, records, org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.ArticleMediaService.class));
    @BeforeEach void setup() { UserContext.setUserId(7L); }
    @AfterEach void close() { UserContext.clear(); }
    @Test void profileUsesTheOwnerVerifiedSnapshotAndServerUrl() {
        when(records.prepareVerified(7L,TargetType.USER,"user/7/source.png","IMAGE")).thenReturn("media-effective/5/7/snapshot");
        when(cos.buildPublicUrl("media-effective/5/7/snapshot")).thenReturn("https://media.example/snapshot");
        var value=media.prepareProfileSnapshot("user/7/source.png");
        assertEquals("media-effective/5/7/snapshot",value.objectKey());assertEquals("https://media.example/snapshot",value.url());
        verify(records).prepareVerified(7L,TargetType.USER,"user/7/source.png","IMAGE");
    }
    @Test void ineligibleUploadsCannotProduceProfileSnapshots() {
        doThrow(new BusinessException(ResultCode.INVALID_OPERATION)).when(records).prepareVerified(eq(7L),eq(TargetType.USER),anyString(),eq("IMAGE"));
        assertThrows(BusinessException.class,()->media.prepareProfileSnapshot("user/8/source.png"));
        verify(cos,never()).buildPublicUrl(anyString());
    }
    @Test void unknownPreparationCannotBeTreatedAsSuccessfulProfileSave() {
        doThrow(new cn.jualn.miniapp.common.exception.ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","unknown"))
            .when(records).prepareVerified(7L,TargetType.USER,"user/7/source.png","IMAGE");
        assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->media.prepareProfileSnapshot("user/7/source.png"));
        verify(records,never()).bindPending(any(),any(),any(),any());
    }
}
