package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
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
    private final MediaServiceImpl media = new MediaServiceImpl(mock(MediaConverter.class), mock(MediaAttachmentMapper.class),
            mock(TargetValidator.class), cos, records);
    @BeforeEach void setup() { UserContext.setUserId(7L); }
    @AfterEach void close() { UserContext.clear(); }
    @Test void copyIsOutsideUploadPrefixAndRegisteredBeforeTheExternalCall() {
        when(cos.buildPublicUrl(anyString())).thenAnswer(call -> "https://media.example/" + call.getArgument(0));
        var snapshot = media.prepareProfileSnapshot("user/7/source.png");
        assertTrue(snapshot.objectKey().startsWith("profile-effective/7/"));
        assertFalse(snapshot.objectKey().startsWith("user/7/"));
        assertEquals("https://media.example/" + snapshot.objectKey(), snapshot.url());
        var order = inOrder(records, cos);
        order.verify(cos).buildPublicUrl("user/7/source.png");
        order.verify(records).assertPendingProfileUpload(7L, "user/7/source.png");
        order.verify(records).recordPending(eq(7L), eq(TargetType.USER), eq(java.util.List.of(snapshot.objectKey())), any());
        order.verify(cos).copyObject("user/7/source.png", snapshot.objectKey());
    }
    @Test void otherOwnersAndUnregisteredUploadsCannotBeCopied() {
        assertThrows(BusinessException.class, () -> media.prepareProfileSnapshot("user/8/source.png"));
        doThrow(new BusinessException(ResultCode.INVALID_OPERATION)).when(records).assertPendingProfileUpload(anyLong(), anyString());
        assertThrows(BusinessException.class, () -> media.prepareProfileSnapshot("user/7/missing.png"));
        verify(cos, never()).copyObject(anyString(), anyString());
        verify(records, never()).recordPending(any(), any(), any(), any());
    }
    @Test void lostCopyResponseStillLeavesADurableCleanupCandidate() {
        doThrow(new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos", "synthetic uncertain copy"))
                .when(cos).copyObject(anyString(), anyString());
        assertThrows(ExternalServiceException.class, () -> media.prepareProfileSnapshot("user/7/source.png"));
        verify(records).recordPending(eq(7L), eq(TargetType.USER), any(), any());
        verify(records, never()).bindPending(any(), any(), any(), any());
    }
}
