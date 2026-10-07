package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserOfficialAccountIdentityTest {

    @Mock UserProfileMapper profileMapper;
    @Mock UserAgreementMapper agreementMapper;
    @Mock RedisService redisService;
    @Mock UserConverter converter;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MediaService mediaService;
    @Mock AdminOperationLogService operationLogService;
    @InjectMocks UserServiceImpl service;

    @Test
    void bind_isIdempotentForSameIdentity() {
        when(profileMapper.selectMpOpenIdById(42L)).thenReturn("openid-1");
        service.bindOfficialAccountIdentity(42L, "openid-1");
        verify(profileMapper, never()).bindMpOpenidIfUnchanged(42L, "openid-1");
    }

    @Test
    void bind_rejectsReplacementAndCrossUserConflict() {
        when(profileMapper.selectMpOpenIdById(42L)).thenReturn("old-openid");
        assertThrows(BusinessException.class,
                () -> service.bindOfficialAccountIdentity(42L, "new-openid"));

        when(profileMapper.selectMpOpenIdById(43L)).thenReturn(null);
        when(profileMapper.selectIdsByMpOpenid("owned-openid")).thenReturn(List.of(99L));
        assertThrows(BusinessException.class,
                () -> service.bindOfficialAccountIdentity(43L, "owned-openid"));
    }

    @Test
    void find_rejectsAmbiguousHistoricalIdentity() {
        when(profileMapper.selectIdsByMpOpenid("duplicate")).thenReturn(List.of(1L, 2L));
        assertThrows(BusinessException.class,
                () -> service.findUserIdByOfficialAccountOpenid("duplicate"));
    }
}
