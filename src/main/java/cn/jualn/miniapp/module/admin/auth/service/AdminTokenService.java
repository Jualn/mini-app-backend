package cn.jualn.miniapp.module.admin.auth.service;

import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.AdminAccountRestrictedException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.store.AdminAuthStore;
import cn.jualn.miniapp.module.admin.auth.config.AdminAuthProperties;
import cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.admin.auth.vo.AdminIdentityVO;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;
import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.jwt.exception.SaJwtException;
import cn.jualn.miniapp.module.admin.auth.store.AdminQrLoginStore;
import cn.jualn.miniapp.module.admin.auth.bo.qrlogin.*;
import cn.jualn.miniapp.common.exception.SystemException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * 管理端 Token 的签发、校验与当前 Token 注销。
 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AdminTokenService {

    private final UserService userService;
    private final cn.jualn.miniapp.module.user.service.UserAccountService userAccounts;
    private final AdminPermissionPolicy permissionPolicy;
    private final AdminAuthStore adminAuthStore;
    private final AdminAuthProperties properties;
    private final AdminQrLoginStore qrStore;
    private final ObjectMapper objectMapper;
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    public static final String QR_MARKER = "qrLoginV2SessionId";

    @jakarta.annotation.PostConstruct
    public void validateTokenTtl() {
        if (properties.getTokenTtl() == null || properties.getTokenTtl().compareTo(Duration.ofMinutes(5)) <= 0) {
            throw new IllegalArgumentException("Admin token TTL must cover QR login recovery");
        }
    }

    public AdminIdentityVO requireValidLogin() {
        requireAdminAccess();
        throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("admin-identity-representation-required","Use the RBAC identity representation");
    }

    public long requireAdminAccess() {
        long userId=requireAuthenticatedSession();
        var decision=authorization.current(userId);
        if(!decision.canUseAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return userId;
    }

    /** Identity only. No grant check and no token revocation merely because grants are empty. */
    public long requireAuthenticatedSessionIdentity() {
        AdminStpUtil.STP_LOGIC.checkLogin();
        String token = AdminStpUtil.STP_LOGIC.getTokenValue();
        if (adminAuthStore.isTokenRevoked(AdminAuthCrypto.sha256(token))) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效");
        }

        long userId = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        if (!validQrActivation(token, userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效");
        }
        try {
            userAccounts.requireSessionVersion(userId, AdminStpUtil.STP_LOGIC.getExtra(
                    cn.jualn.miniapp.module.user.service.UserAccountService.SESSION_VERSION));
        } catch (BusinessException failure) {
            if (failure.getResultCode() == ResultCode.UNAUTHORIZED && userAccounts.isAdminSessionRestricted(userId)) {
                throw new AdminAccountRestrictedException();
            }
            throw failure;
        }
        return userId;
    }

    public long requireAuthenticatedSession() {
        long userId=requireAuthenticatedSessionIdentity();
        requireSessionProfile(userId);
        return userId;
    }

    private UserProfileBO requireSessionProfile(long userId) {
        UserProfileBO profile;
        try {
            profile = userService.getUserProfile(userId);
        } catch (BusinessException e) {
            if (e.getResultCode() != ResultCode.USER_NOT_FOUND) throw e;
            revokeCurrentToken();
            throw new BusinessException(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效");
        }
        if (profile.getStatus() == cn.jualn.miniapp.module.user.enums.UserStatus.BANNED
                || profile.getStatus() == cn.jualn.miniapp.module.user.enums.UserStatus.MUTED) {
            revokeCurrentToken();
            throw new AdminAccountRestrictedException();
        }
        if (profile.getStatus() != cn.jualn.miniapp.module.user.enums.UserStatus.NORMAL) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效");
        }
        return profile;
    }

    public AdminQrLoginResultBO resumeBrowserSession(String token) {
        AdminStpUtil.STP_LOGIC.setTokenValueToStorage(token);
        try {
            long userId = requireAuthenticatedSessionIdentity();
            return new AdminQrLoginResultBO(token, identity(userId, requireSessionProfile(userId)), 0);
        } catch (SystemException | org.springframework.dao.DataAccessException unavailable) {
            var failure = new cn.jualn.miniapp.common.exception.ContractProblemException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "/problems/authentication-unavailable",
                    "Admin session could not be checked");
            failure.initCause(unavailable);
            throw failure;
        }
    }

    public cn.jualn.miniapp.module.authorization.model.RbacModels.Decision diagnoseCurrent() {
        return authorization.current(requireAuthenticatedSession());
    }

    public void logoutCurrent() {
        try {
            if (AdminStpUtil.STP_LOGIC.isLogin()) {
                long user = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
                revokeCurrentToken();
                AdminStpUtil.STP_LOGIC.logout();
                log.info("result=success operation=adminLogout userId={}", user);
            }
        } catch (cn.dev33.satoken.exception.NotLoginException ended) {
            // Idempotent cleanup also works after grant loss or account restriction.
        } catch (BusinessException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            var unavailable = new cn.jualn.miniapp.common.exception.ContractProblemException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "/problems/authentication-unavailable", "Admin session termination cannot be confirmed");
            unavailable.initCause(failure);
            throw unavailable;
        }
    }

    private void revokeCurrentToken() {
        String token = AdminStpUtil.STP_LOGIC.getTokenValue();
        if (!StringUtils.hasText(token)) {
            return;
        }
        String sessionId = qrSessionId(token);
        if (sessionId != null) qrStore.deactivate(sessionId);
        long timeout = AdminStpUtil.STP_LOGIC.getTokenTimeout();
        if (timeout > 0) {
            adminAuthStore.revokeToken(AdminAuthCrypto.sha256(token), Duration.ofSeconds(timeout));
        }
    }

    public AdminQrCandidateBO prepareQrCandidate(long userId, String sessionId, UserProfileBO profile, long now) {
        return prepareQrCandidate(userId,sessionId,profile,now,"RBAC_V1");
    }
    public AdminQrCandidateBO prepareQrCandidate(long userId, String sessionId, UserProfileBO profile, long now,String representation) {
        if (!"RBAC_V1".equals(representation)) throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(
            new cn.jualn.miniapp.common.exception.ContractProblemException.Violation("header","X-Admin-Identity-Representation","INVALID","Unsupported representation"));
        var current=authorization.current(userId);
        if(!current.canUseAdmin()) throw new BusinessException(ResultCode.UNAUTHORIZED);
        qrStore.requireSameTokenStorage();
        long ttl = properties.getTokenTtl().toMillis();
        if (ttl <= Duration.ofMinutes(5).toMillis()) throw new SystemException("Invalid admin token TTL for QR recovery");
        String token = AdminStpUtil.STP_LOGIC.createTokenValue(userId, "default-device", ttl / 1000,
                Map.of(QR_MARKER, sessionId,
                    cn.jualn.miniapp.module.user.service.UserAccountService.SESSION_VERSION,
                    Long.toString(userAccounts.sessionVersion(userId))));
        try {
            return new AdminQrCandidateBO(token, AdminAuthCrypto.sha256(token),
                    objectMapper.writeValueAsString(identity(userId, profile)), Math.addExact(now, ttl));
        } catch (JsonProcessingException e) { throw new SystemException("Cannot freeze QR login profile"); }
    }

    private static AdminQrIdentityBO identity(long userId, UserProfileBO profile) {
        String nickname = profile.getNickname();
        return new AdminQrIdentityBO(Long.toString(userId), nickname == null ? "管理用户" : nickname,
                nickname == null || nickname.isEmpty() ? "管" : nickname.substring(0, 1),
                null, null, null, "ASSIGNMENT");
    }

    public void materializeQrCandidate(long userId, AdminQrCandidateBO candidate, long now) {
        qrStore.requireSameTokenStorage();
        if (now >= candidate.tokenExpiresAt()) throw new SystemException("QR candidate deadline elapsed");
        if (!AdminAuthCrypto.sameDigest(AdminAuthCrypto.sha256(candidate.token()), candidate.tokenHash())) {
            throw new SystemException("QR candidate integrity failure");
        }
        long remaining = (candidate.tokenExpiresAt() - now) / 1000;
        if (remaining <= 0) throw new SystemException("QR candidate deadline elapsed");
        // createLoginSession is login's persistence path without response/cookie delivery of an uncommitted candidate.
        AdminStpUtil.STP_LOGIC.createLoginSession(userId, new SaLoginParameter()
                .setToken(candidate.token()).setTimeout(remaining).setIsConcurrent(true).setIsShare(false)
                .setIsLastingCookie(false).setIsWriteHeader(false));
        if (!String.valueOf(userId).equals(String.valueOf(AdminStpUtil.STP_LOGIC.getLoginIdByToken(candidate.token())))
                || AdminStpUtil.STP_LOGIC.getSessionByLoginId(userId, false) == null) {
            throw new SystemException("QR candidate login is not ready");
        }
    }

    public String qrMappingKey(AdminQrCandidateBO candidate) {
        return AdminStpUtil.STP_LOGIC.splicingKeyTokenValue(candidate.token());
    }

    public boolean validQrResult(long userId, String sessionId, AdminQrCandidateBO candidate, long now) {
        if (!authorization.current(userId).canUseAdmin() || !"ASSIGNMENT".equals(qrResult(candidate).profile().authorizationModel())) return false;
        if (!AdminAuthCrypto.sameDigest(AdminAuthCrypto.sha256(candidate.token()), candidate.tokenHash()) || now >= candidate.tokenExpiresAt()) return false;
        try { if (!sessionId.equals(qrSessionId(candidate.token()))) return false; }
        catch (BusinessException invalid) { if (invalid.getResultCode() == ResultCode.UNAUTHORIZED) return false; throw invalid; }
        userAccounts.requireSessionVersion(userId, SaJwtUtil.getPayloadsNotCheck(candidate.token(), AdminStpUtil.LOGIN_TYPE,
                AdminStpUtil.STP_LOGIC.getConfigOrGlobal().getJwtSecretKey()).get(
                    cn.jualn.miniapp.module.user.service.UserAccountService.SESSION_VERSION));
        return String.valueOf(userId).equals(String.valueOf(AdminStpUtil.STP_LOGIC.getLoginIdByToken(candidate.token())))
                && AdminStpUtil.STP_LOGIC.getTokenTimeout(candidate.token()) > 0
                && !adminAuthStore.isTokenRevoked(candidate.tokenHash())
                && qrStore.activated(sessionId, candidate.tokenHash(), userId);
    }

    public void discardQrCandidate(AdminQrCandidateBO candidate) {
        if (candidate != null) AdminStpUtil.STP_LOGIC.logoutByTokenValue(candidate.token());
    }

    public AdminQrLoginResultBO qrResult(AdminQrCandidateBO candidate) {
        try { return new AdminQrLoginResultBO(candidate.token(), objectMapper.readValue(candidate.profileJson(), AdminQrIdentityBO.class),
                candidate.tokenExpiresAt()); }
        catch (JsonProcessingException e) { throw new SystemException("Cannot read frozen QR login profile"); }
    }

    private boolean validQrActivation(String token, long userId) {
        String sessionId = qrSessionId(token);
        return qrStore.activated(sessionId, AdminAuthCrypto.sha256(token), userId);
    }

    private String qrSessionId(String token) {
        Object marker;
        try {
            // Simple JWT has no eff claim. This API still verifies signature/loginType; Redis owns expiry.
            marker = SaJwtUtil.getPayloadsNotCheck(token, AdminStpUtil.LOGIN_TYPE,
                    AdminStpUtil.STP_LOGIC.getConfigOrGlobal().getJwtSecretKey()).get(QR_MARKER);
        } catch (SaJwtException e) { throw new BusinessException(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效"); }
        if (!(marker instanceof String id) || !id.matches("[A-Za-z0-9_-]{22}")) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效");
        }
        return (String) marker;
    }
}
