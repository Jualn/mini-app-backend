package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;
import cn.jualn.miniapp.third.cos.service.CosService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }


}