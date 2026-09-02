package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.media.entity.MediaUploadRecord;
import cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaUploadRecordServiceTest {

    @Mock
    private MediaUploadRecordMapper recordMapper;
    @Mock
    private CosService cosService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MediaUploadRecord.class);
    }

    @Test
    void bindPending_shouldRejectPartialConditionalUpdate() {
        MediaUploadRecordService service = new MediaUploadRecordService(recordMapper, cosService);
        when(recordMapper.bindPending(any(), anyLong(), anyInt(), anyLong(), any())).thenReturn(1);

        assertThrows(BusinessException.class, () -> service.bindPending(
                7L, TargetType.POST, 11L, List.of("post/7/a.jpg", "post/7/b.jpg")));
    }

    @Test
    void cleanupExpiredBatch_shouldDeleteOnlyClaimedObject() {
        MediaUploadRecordService service = new MediaUploadRecordService(recordMapper, cosService);
        MediaUploadRecord candidate = MediaUploadRecord.builder()
                .id(1L)
                .objectKey("post/7/orphan.jpg")
                .retryCount(0)
                .cleanupAfter(LocalDateTime.now().minusMinutes(1))
                .build();
        when(recordMapper.selectList(any())).thenReturn(List.of(candidate));
        when(recordMapper.update(any(), any())).thenReturn(0, 1);

        assertEquals(1, service.cleanupExpiredBatch());

        verify(cosService).deleteObject("post/7/orphan.jpg");
        verify(recordMapper).deleteById(1L);
    }

    @Test
    void cleanupExpiredBatch_shouldSkipObjectWhenClaimIsLost() {
        MediaUploadRecordService service = new MediaUploadRecordService(recordMapper, cosService);
        when(recordMapper.selectList(any())).thenReturn(List.of(MediaUploadRecord.builder()
                .id(1L)
                .objectKey("post/7/bound.jpg")
                .build()));
        when(recordMapper.update(any(), any())).thenReturn(0);

        assertEquals(0, service.cleanupExpiredBatch());

        verify(cosService, never()).deleteObject(any());
    }
}
