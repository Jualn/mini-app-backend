package cn.jualn.miniapp.module.admin.auth.service.impl;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.AdminAuthStore;
import cn.jualn.miniapp.infrastructure.cache.AdminAuthStore.SessionState;
import cn.jualn.miniapp.module.admin.auth.config.AdminAuthProperties;
import cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginStatus;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrConfirmationVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrSessionCreateVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrSessionPollVO;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceImplTest {

    private static final String SESSION_ID = "abcdefghijklmnopqrstuv";
    private static final String POLL_SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";

    @Mock
    private AdminAuthStore adminAuthStore;
    @Mock
    private AdminTokenService adminTokenService;
    @Mock
    private UserService userService;

    private AdminAuthServiceImpl service;
    private AdminAuthProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AdminAuthProperties();
        service = new AdminAuthServiceImpl(
                adminAuthStore,
                adminTokenService,
                userService,
                new AdminPermissionPolicy(),
                properties
        );
    }

    @Test
    void createQrSession_shouldReturnSecretButOnlyPersistItsHash() {
        when(adminAuthStore.incrementRateLimit(anyString(), any(Duration.class))).thenReturn(1L);
        when(adminAuthStore.createSession(anyString(), anyString(), anyLong(), any(Duration.class)))
                .thenReturn(true);

        AdminQrSessionCreateVO result = service.createQrSession("127.0.0.1");

        assertTrue(result.getSessionId().matches("[A-Za-z0-9_-]{22}"));
        assertTrue(result.getPollSecret().matches("[A-Za-z0-9_-]{43}"));
        assertEquals("jualn-admin-login:" + result.getSessionId(), result.getQrPayload());
        assertEquals(1500L, result.getPollIntervalMs());
        assertNotNull(result.getExpiresAt());

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(adminAuthStore).createSession(
                anyString(),
                hashCaptor.capture(),
                anyLong(),
                any(Duration.class)
        );
        assertEquals(64, hashCaptor.getValue().length());
        assertNotEquals(result.getPollSecret(), hashCaptor.getValue());
    }

    @Test
    void confirmQrSession_shouldDenyOrdinaryUserAndPersistDeniedState() {
        when(adminAuthStore.incrementRateLimit(anyString(), any(Duration.class))).thenReturn(1L);
        when(userService.getUserProfile(7L)).thenReturn(profile(UserRole.USER, UserStatus.NORMAL));
        when(adminAuthStore.deny(anyString(), anyString(), anyLong()))
                .thenReturn(new SessionState(AdminQrLoginStatus.DENIED, null, "denied"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.confirmQrSession(SESSION_ID, 7L)
        );

        assertEquals(ResultCode.FORBIDDEN.getCode(), exception.getCode());
        assertEquals("当前微信身份不是运营或管理员", exception.getMessage());
        verify(adminAuthStore, never()).confirm(anyString(), anyLong(), anyLong());
    }

    @Test
    void confirmQrSession_shouldConfirmAdminFromAuthenticatedUserId() {
        when(adminAuthStore.incrementRateLimit(anyString(), any(Duration.class))).thenReturn(1L);
        when(userService.getUserProfile(7L)).thenReturn(profile(UserRole.ADMIN, UserStatus.NORMAL));
        when(adminAuthStore.confirm(anyString(), anyLong(), anyLong()))
                .thenReturn(new SessionState(AdminQrLoginStatus.CONFIRMED, 7L, null));

        AdminQrConfirmationVO result = service.confirmQrSession(SESSION_ID, 7L);

        assertTrue(result.isConfirmed());
        assertEquals("测试用户", result.getDisplayName());
    }

    @Test
    void pollQrSession_shouldAtomicallyClaimThenIssueIndependentAdminToken() {
        when(adminAuthStore.poll(anyString(), anyString(), anyLong()))
                .thenReturn(new SessionState(AdminQrLoginStatus.CONFIRMED, 7L, null));
        when(userService.getUserProfile(7L)).thenReturn(profile(UserRole.ADMIN, UserStatus.NORMAL));
        when(adminAuthStore.claim(anyString(), anyString(), anyLong())).thenReturn(7L);
        when(adminTokenService.issue(7L)).thenReturn("Bearer admin-token");

        AdminQrSessionPollVO result = service.pollQrSession(SESSION_ID, POLL_SECRET);

        assertEquals(AdminQrLoginStatus.CONFIRMED, result.getStatus());
        assertEquals("Bearer admin-token", result.getToken());
        assertEquals("7", result.getProfile().getId());
        assertEquals("admin", result.getProfile().getRoleCode());
        assertTrue(result.getProfile().getPermissions().contains("*"));
    }

    @Test
    void pollQrSession_shouldDenyWhenConfirmedUserWasDowngraded() {
        when(adminAuthStore.poll(anyString(), anyString(), anyLong()))
                .thenReturn(new SessionState(AdminQrLoginStatus.CONFIRMED, 7L, null));
        when(userService.getUserProfile(7L)).thenReturn(profile(UserRole.USER, UserStatus.NORMAL));
        when(adminAuthStore.deny(anyString(), anyString(), anyLong()))
                .thenReturn(new SessionState(
                        AdminQrLoginStatus.DENIED,
                        null,
                        "当前微信身份不是运营或管理员"
                ));

        AdminQrSessionPollVO result = service.pollQrSession(SESSION_ID, POLL_SECRET);

        assertEquals(AdminQrLoginStatus.DENIED, result.getStatus());
        assertEquals("当前微信身份不是运营或管理员", result.getMessage());
        verify(adminAuthStore).deny(anyString(), anyString(), anyLong());
        verify(adminAuthStore, never()).claim(anyString(), anyString(), anyLong());
        verify(adminTokenService, never()).issue(anyLong());
    }

    @Test
    void pollQrSession_shouldReturnTerminalDeniedWithoutIssuingToken() {
        when(adminAuthStore.poll(anyString(), anyString(), anyLong()))
                .thenReturn(new SessionState(AdminQrLoginStatus.DENIED, null, "无管理权限"));

        AdminQrSessionPollVO result = service.pollQrSession(SESSION_ID, POLL_SECRET);

        assertEquals(AdminQrLoginStatus.DENIED, result.getStatus());
        assertEquals("无管理权限", result.getMessage());
        assertFalse(result.getMessage().isBlank());
        verify(adminTokenService, never()).issue(anyLong());
    }

    @Test
    void pollQrSession_shouldRejectSecondConcurrentClaim() {
        when(adminAuthStore.poll(anyString(), anyString(), anyLong()))
                .thenReturn(new SessionState(AdminQrLoginStatus.CONFIRMED, 7L, null));
        when(userService.getUserProfile(7L)).thenReturn(profile(UserRole.ADMIN, UserStatus.NORMAL));
        when(adminAuthStore.claim(anyString(), anyString(), anyLong())).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.pollQrSession(SESSION_ID, POLL_SECRET)
        );

        assertEquals(ResultCode.NOT_FOUND.getCode(), exception.getCode());
        verify(adminTokenService, never()).issue(anyLong());
    }

    @Test
    void createQrSession_shouldEnforceIpRateLimit() {
        when(adminAuthStore.incrementRateLimit(anyString(), any(Duration.class)))
                .thenReturn((long) properties.getCreateLimitPerWindow() + 1);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.createQrSession("127.0.0.1")
        );

        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), exception.getCode());
        verify(adminAuthStore, never()).createSession(anyString(), anyString(), anyLong(), any(Duration.class));
    }

    private UserProfileBO profile(UserRole role, UserStatus status) {
        return UserProfileBO.builder()
                .nickname("测试用户")
                .role(role)
                .status(status)
                .build();
    }
}
