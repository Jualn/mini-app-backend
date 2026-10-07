package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.common.mapper.EnumConverter;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.module.media.service.MediaUploadRecordService;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;

class MediaAttachmentIdentityTest {
    @Test void reorderRetainsExistingAttachmentId() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MediaAttachment.class);
        MediaAttachmentMapper mapper = mock(MediaAttachmentMapper.class);
        MediaConverter converter = Mappers.getMapper(MediaConverter.class);
        ReflectionTestUtils.setField(converter, "enumConverter", new EnumConverter());
        when(mapper.selectList(any())).thenReturn(List.of(MediaAttachment.builder().id(11L)
                .type(MediaType.URL.getCode()).url("https://example.org/notice").build()));
        when(mapper.update(any(), any())).thenReturn(1);
        var service = new MediaServiceImpl(converter, mapper, mock(TargetValidator.class), mock(CosService.class), mock(MediaUploadRecordService.class));
        service.replaceAttachments(MediaAttachmentSaveBO.builder().targetType(TargetType.ACTIVITY).targetId(7L)
                .attachments(List.of(AttachmentItemBO.builder().type(MediaType.URL)
                        .url("https://example.org/notice").sortOrder(5).originalName("通知").build())).build());
        verify(mapper, never()).insert(any(MediaAttachment.class));
        verify(mapper, never()).delete(any());
        verify(mapper).update(isNull(), argThat(w -> w.getSqlSegment().contains("id") && ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MediaAttachment>) w).getParamNameValuePairs().containsValue(11L)));
    }
}
