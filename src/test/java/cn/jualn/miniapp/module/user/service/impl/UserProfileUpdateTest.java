package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.bo.UserProfileUpdateBO;
import cn.jualn.miniapp.module.user.controller.UserController;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.dto.request.UserProfileUpdateRequest;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无数据库单元测试：校验返回值和提交回调注册，不替代真实事务/Redis/COS 联调。 */
@ExtendWith(MockitoExtension.class)
class UserProfileUpdateTest {
    @Mock private UserProfileMapper mapper;
    @Mock private UserAgreementMapper agreementMapper;
    @Mock private RedisService redis;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private MediaService media;
    @Mock private AdminOperationLogService operationLog;

    private final UserConverter converter = Mappers.getMapper(UserConverter.class);
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(7L);
        TransactionSynchronizationManager.initSynchronization();
        service = new UserServiceImpl(mapper, agreementMapper, redis, converter, publisher, media, operationLog);
    }

    @AfterEach
    void cleanUp() {
        UserContext.clear();
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void returnsStoredProfileWithCanonicalMediaAndUnchangedFields() {
        UserProfile before = entity("旧昵称", "old.png");
        UserProfile stored = entity("新昵称", "https://media.example/owned.png");
        when(mapper.selectById(7L)).thenReturn(before, stored);
        when(mapper.updateById(any(UserProfile.class))).thenReturn(1);
        when(media.resolveOwnedUploadUrl(any(), eq("owned/key"))).thenReturn(stored.getAvatarUrl());
        UserProfileUpdateBO command = new UserProfileUpdateBO();
        command.setNickname("新昵称");
        command.setAvatarObjectKey(" owned/key ");
        command.setAvatarUrl("https://untrusted.example/submitted.png");

        UserProfileBO result = service.updateCurrentProfile(command);

        assertEquals(7L, result.getId());
        assertEquals("新昵称", result.getNickname());
        assertEquals(stored.getAvatarUrl(), result.getAvatarUrl());
        assertEquals("未修改简介", result.getBio());
        assertEquals("banner.png", result.getBackgroundUrl());
        assertEquals(before.getCreatedAt(), result.getCreatedAt());
        ArgumentCaptor<UserProfile> updated = ArgumentCaptor.forClass(UserProfile.class);
        verify(mapper).updateById(updated.capture());
        assertEquals("owned/key", updated.getValue().getAvatarObjectKey());
        assertEquals(stored.getAvatarUrl(), updated.getValue().getAvatarUrl());
        verifyNoInteractions(redis);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(redis, times(3)).delete(anyString());
    }

    @Test
    void failedUpdateDoesNotReturnProfileOrRegisterInvalidation() {
        when(mapper.selectById(7L)).thenReturn(entity("原昵称", "old.png"));
        when(mapper.updateById(any(UserProfile.class))).thenReturn(0);
        UserProfileUpdateBO command = new UserProfileUpdateBO();
        command.setBio("新简介");

        assertThrows(BusinessException.class, () -> service.updateCurrentProfile(command));
        verify(mapper, times(1)).selectById(7L);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(redis, publisher);
    }

    @Test
    void cacheFailureAfterCommitDoesNotTurnSaveIntoFailure() {
        when(mapper.selectById(7L)).thenReturn(entity("昵称", "avatar.png"));
        when(mapper.updateById(any(UserProfile.class))).thenReturn(1);
        doThrow(new IllegalStateException("Redis unavailable")).when(redis).delete(anyString());
        UserProfileUpdateBO command = new UserProfileUpdateBO();
        command.setBio("未修改简介");

        assertNotNull(service.updateCurrentProfile(command));
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCommit()));
    }

    @Test
    void controllerReturnsSameProfileContractAsGet() {
        UserService userService = mock(UserService.class);
        UserProfileBO stored = converter.toProfileBO(entity("最终昵称", "final.png"));
        when(userService.updateCurrentProfile(any())).thenReturn(stored);
        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        request.setNickname("最终昵称");

        var response = new UserController(userService, converter).updateCurrentProfile(request);

        assertEquals(7L, response.getData().getId());
        assertEquals("final.png", response.getData().getAvatarUrl());
        assertEquals("未修改简介", response.getData().getBio());
        assertNotNull(response.getData().getCapabilities());
        verify(userService, never()).getCurrentProfile();
    }

    private UserProfile entity(String nickname, String avatar) {
        return UserProfile.builder().id(7L).nickname(nickname).avatarUrl(avatar)
                .backgroundUrl("banner.png").bio("未修改简介")
                .role(UserRole.USER.getCode()).status(UserStatus.NORMAL.getCode())
                .createdAt(LocalDateTime.of(2025, 1, 1, 0, 0)).build();
    }
}
