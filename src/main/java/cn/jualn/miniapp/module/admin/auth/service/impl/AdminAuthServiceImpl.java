package cn.jualn.miniapp.module.admin.auth.service.impl;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.AdminAuthStore;
import cn.jualn.miniapp.infrastructure.cache.AdminAuthStore.SessionState;
import cn.jualn.miniapp.module.admin.auth.config.AdminAuthProperties;
import cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginStatus;
import cn.jualn.miniapp.module.admin.auth.service.AdminAuthService;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.admin.auth.vo.AdminIdentityVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrConfirmationVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrSessionCreateVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrSessionPollVO;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {

    private static final int SESSION_ID_BYTES = 16;
    private static final int POLL_SECRET_BYTES = 32;
    private static final int RANDOM_GENERATION_ATTEMPTS = 3;

    private static final String ROLE_DENIED_MESSAGE = "当前微信身份不是运营或管理员";
    private static final String STATUS_DENIED_MESSAGE = "账号状态不允许登录管理端";
    private static final String SESSION_NOT_FOUND_MESSAGE = "扫码会话不存在或已失效";

    private final AdminAuthStore adminAuthStore;
    private final AdminTokenService adminTokenService;
    private final UserService userService;
    private final AdminPermissionPolicy permissionPolicy;
    private final AdminAuthProperties properties;

    @Override
    public AdminQrSessionCreateVO createQrSession(String clientIp) {
        enforceRateLimit(
                "create:" + AdminAuthCrypto.sha256(clientIp),
                properties.getCreateLimitPerWindow()
        );

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getQrSessionTtl());
        Duration redisTtl = properties.getQrSessionTtl().plus(properties.getExpiredRetention());

        for (int attempt = 0; attempt < RANDOM_GENERATION_ATTEMPTS; attempt++) {
            String sessionId = AdminAuthCrypto.randomUrlToken(SESSION_ID_BYTES);
            String pollSecret = AdminAuthCrypto.randomUrlToken(POLL_SECRET_BYTES);
            boolean created = adminAuthStore.createSession(
                    sessionId,
                    AdminAuthCrypto.sha256(pollSecret),
                    expiresAt.toEpochMilli(),
                    redisTtl
            );
            if (created) {
                return AdminQrSessionCreateVO.builder()
                        .sessionId(sessionId)
                        .pollSecret(pollSecret)
                        .qrPayload(properties.getQrPayloadPrefix() + sessionId)
                        .expiresAt(OffsetDateTime.ofInstant(expiresAt, ZoneId.systemDefault()))
                        .pollIntervalMs(properties.getPollIntervalMs())
                        .build();
            }
        }
        throw new SystemException("管理端二维码会话随机标识连续冲突");
    }

    @Override
    public AdminQrSessionPollVO pollQrSession(String sessionId, String pollSecret) {
        long now = System.currentTimeMillis();
        String secretHash = AdminAuthCrypto.sha256(pollSecret);
        SessionState state = requireSession(adminAuthStore.poll(sessionId, secretHash, now));

        if (state.status() != AdminQrLoginStatus.CONFIRMED) {
            return pollResult(state.status(), state.deniedReason());
        }
        if (state.userId() == null) {
            throw new SystemException("已确认的管理端二维码会话缺少 userId");
        }

        UserProfileBO profile = loadUserProfile(state.userId());
        if (!permissionPolicy.canLogin(profile)) {
            String reason = deniedReason(profile);
            SessionState denied = adminAuthStore.deny(sessionId, reason, now);
            if (denied == null) {
                throw sessionNotFound();
            }
            if (denied.status() == AdminQrLoginStatus.EXPIRED) {
                return pollResult(AdminQrLoginStatus.EXPIRED, null);
            }
            return pollResult(AdminQrLoginStatus.DENIED, denied.deniedReason());
        }

        Long claimedUserId = adminAuthStore.claim(sessionId, secretHash, now);
        if (claimedUserId == null) {
            throw sessionNotFound();
        }
        if (!claimedUserId.equals(state.userId())) {
            throw new SystemException("管理端二维码会话领取用户不一致");
        }

        AdminIdentityVO identity = permissionPolicy.toIdentity(claimedUserId, profile);
        return AdminQrSessionPollVO.builder()
                .status(AdminQrLoginStatus.CONFIRMED)
                .token(adminTokenService.issue(claimedUserId))
                .profile(identity)
                .build();
    }

    @Override
    public void cancelQrSession(String sessionId, String pollSecret) {
        adminAuthStore.cancel(sessionId, AdminAuthCrypto.sha256(pollSecret));
    }

    @Override
    public AdminQrConfirmationVO confirmQrSession(String sessionId, long userId) {
        enforceRateLimit("confirm:" + userId, properties.getConfirmLimitPerWindow());

        UserProfileBO profile = loadUserProfile(userId);
        if (!permissionPolicy.canLogin(profile)) {
            String reason = deniedReason(profile);
            SessionState denied = adminAuthStore.deny(sessionId, reason, System.currentTimeMillis());
            if (denied == null || denied.status() == AdminQrLoginStatus.EXPIRED) {
                throw sessionNotFound();
            }
            throw new BusinessException(ResultCode.FORBIDDEN, reason);
        }

        SessionState confirmed = requireSession(
                adminAuthStore.confirm(sessionId, userId, System.currentTimeMillis())
        );
        if (confirmed.status() == AdminQrLoginStatus.EXPIRED) {
            throw sessionNotFound();
        }
        if (confirmed.status() != AdminQrLoginStatus.CONFIRMED
                || confirmed.userId() == null
                || confirmed.userId() != userId) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "扫码会话状态不允许确认");
        }

        AdminIdentityVO identity = permissionPolicy.toIdentity(userId, profile);
        return AdminQrConfirmationVO.builder()
                .confirmed(true)
                .displayName(identity.getDisplayName())
                .build();
    }

    @Override
    public AdminIdentityVO getCurrentIdentity() {
        return adminTokenService.requireValidLogin();
    }

    @Override
    public void logoutCurrent() {
        adminTokenService.logoutCurrent();
    }

    private SessionState requireSession(SessionState state) {
        if (state == null) {
            throw sessionNotFound();
        }
        return state;
    }

    private UserProfileBO loadUserProfile(long userId) {
        try {
            return userService.getUserProfile(userId);
        } catch (BusinessException e) {
            return null;
        }
    }

    private String deniedReason(UserProfileBO profile) {
        if (profile == null || profile.getStatus() != UserStatus.NORMAL) {
            return STATUS_DENIED_MESSAGE;
        }
        if (profile.getRole() != UserRole.OPR && profile.getRole() != UserRole.ADMIN) {
            return ROLE_DENIED_MESSAGE;
        }
        return STATUS_DENIED_MESSAGE;
    }

    private AdminQrSessionPollVO pollResult(AdminQrLoginStatus status, String message) {
        return AdminQrSessionPollVO.builder()
                .status(status)
                .message(message == null || message.isBlank() ? null : message)
                .build();
    }

    private void enforceRateLimit(String discriminator, int limit) {
        long count = adminAuthStore.incrementRateLimit(discriminator, properties.getRateLimitWindow());
        if (count > limit) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS);
        }
    }

    private BusinessException sessionNotFound() {
        return new BusinessException(ResultCode.NOT_FOUND, SESSION_NOT_FOUND_MESSAGE);
    }
}
