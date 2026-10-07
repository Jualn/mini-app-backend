package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.controller.UserController;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.dto.request.UserProfileUpdateRequest;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Legacy response compatibility; invariants now have real MySQL coverage in EffectiveProfileDatabaseTest. */
class UserProfileUpdateTest {
    @Test void controllerStillReturnsLegacyAccountRepresentation() {
        UserService service = mock(UserService.class);
        when(service.updateCurrentProfile(any())).thenReturn(UserProfileBO.builder().id(7L)
                .nickname("最终昵称").avatarUrl("https://media.example/avatar").bio("简介")
                .role(UserRole.USER).status(UserStatus.NORMAL).build());
        var request = new UserProfileUpdateRequest(); request.setNickname("最终昵称");
        var response = new UserController(service, Mappers.getMapper(UserConverter.class)).updateCurrentProfile(request);
        assertEquals(7L, response.getData().getId());
        assertEquals("最终昵称", response.getData().getNickname());
        assertEquals("https://media.example/avatar", response.getData().getAvatarUrl());
        assertNotNull(response.getData().getCapabilities());
        verify(service).updateCurrentProfile(any()); verify(service, never()).getCurrentProfile();
    }
}
