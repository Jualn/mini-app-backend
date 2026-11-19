package cn.jualn.miniapp.module.user.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.user.bo.UserAgreementBO;
import cn.jualn.miniapp.module.user.controller.UserController;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.entity.UserAgreement;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserAgreementServiceTest {
    private final UserAgreementMapper mapper = mock(UserAgreementMapper.class);
    private final RedisService cache = mock(RedisService.class);
    private final UserAgreementService service = new UserAgreementService(mapper, cache);
    private final UserController controller = new UserController(mock(UserService.class), service,
            Mappers.getMapper(UserConverter.class));

    @AfterEach void clearIdentity() { UserContext.clear(); }

    @Test void databaseRecordBecomesDetachedBusinessResultAndOriginalResponse() {
        UserContext.setUserId(7L);
        var time = LocalDateTime.of(2026, 10, 31, 9, 0);
        var row = UserAgreement.builder().userId(7L).version("v1").agreedAt(time).build();
        when(mapper.selectById(7L)).thenReturn(row);

        var result = service.getCurrentAgreement();
        assertEquals("v1", result.getVersion());
        assertEquals(time, result.getAgreedAt());
        result.setVersion("changed");
        assertEquals("v1", row.getVersion());
        verify(cache).set(eq(RedisKeyConstant.userAgreement(7L)), isA(UserAgreementBO.class),
                eq(RedisKeyConstant.USER_AGREEMENT_TTL));

        var response = controller.getCurrentAgreementStatus().getData();
        assertTrue(response.getAgreed());
        assertEquals("v1", response.getVersion());
        assertEquals(time, response.getAgreedAt());
    }

    @Test void cachedBusinessRecordAvoidsPersistenceRead() {
        UserContext.setUserId(7L);
        var cached = UserAgreementBO.builder().userId(7L).version("v2").build();
        when(cache.get(RedisKeyConstant.userAgreement(7L), UserAgreementBO.class)).thenReturn(cached);
        assertEquals("v2", service.getCurrentAgreement().getVersion());
        verifyNoInteractions(mapper);
    }

    @Test void missingRecordKeepsNotAgreedResponse() {
        UserContext.setUserId(7L);
        var response = controller.getCurrentAgreementStatus().getData();
        assertFalse(response.getAgreed());
        assertNull(response.getVersion());
        assertNull(response.getAgreedAt());
    }

    @Test void unauthenticatedReadAndWriteDoNotAccessStorage() {
        assertThrows(BusinessException.class, service::getCurrentAgreement);
        assertThrows(BusinessException.class, () -> service.agreeCurrentAgreement("v1"));
        verifyNoInteractions(mapper, cache);
    }

    @Test void agreementWritesRetainCurrentIdentityAndInvalidateOwnedCache() {
        UserContext.setUserId(7L);
        service.agreeCurrentAgreement("v1");
        verify(mapper).insert(argThat((UserAgreement row) -> row.getUserId().equals(7L)
                && row.getVersion().equals("v1")));
        verify(cache).delete(RedisKeyConstant.userAgreement(7L));
    }
}
