package cn.jualn.miniapp.module.admin.auth.service;

import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.infrastructure.cache.AdminAuthStore;
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

/**
 * 管理端 Token 的签发、校验与当前 Token 注销。
 */
@Service
@RequiredArgsConstructor
public class AdminTokenService {

    private final UserService userService;
    private final AdminPermissionPolicy permissionPolicy;
    private final AdminAuthStore adminAuthStore;
    private final AdminAuthProperties properties;

    public String issue(long userId) {
        SaLoginParameter parameter = new SaLoginParameter()
                .setTimeout(properties.getTokenTtl().toSeconds())
                .setIsLastingCookie(false);
        AdminStpUtil.STP_LOGIC.login(userId, parameter);
        return toAuthorizationValue(AdminStpUtil.STP_LOGIC.getTokenValue());
    }

    public AdminIdentityVO requireValidLogin() {
        AdminStpUtil.STP_LOGIC.checkLogin();
        String token = AdminStpUtil.STP_LOGIC.getTokenValue();
        if (adminAuthStore.isTokenRevoked(AdminAuthCrypto.sha256(token))) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效");
        }

        long userId = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        UserProfileBO profile;
        try {
            profile = userService.getUserProfile(userId);
        } catch (BusinessException e) {
            revokeCurrentToken();
            throw new BusinessException(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效");
        }
        if (!permissionPolicy.canLogin(profile)) {
            revokeCurrentToken();
            throw new BusinessException(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效");
        }
        return permissionPolicy.toIdentity(userId, profile);
    }

    public void logoutCurrent() {
        if (AdminStpUtil.STP_LOGIC.isLogin()) {
            revokeCurrentToken();
            AdminStpUtil.STP_LOGIC.logout();
        }
    }

    private void revokeCurrentToken() {
        String token = AdminStpUtil.STP_LOGIC.getTokenValue();
        if (!StringUtils.hasText(token)) {
            return;
        }
        long timeout = AdminStpUtil.STP_LOGIC.getTokenTimeout();
        if (timeout > 0) {
            adminAuthStore.revokeToken(AdminAuthCrypto.sha256(token), Duration.ofSeconds(timeout));
        }
    }

    private String toAuthorizationValue(String token) {
        String prefix = AdminStpUtil.STP_LOGIC.getConfigOrGlobal().getTokenPrefix();
        return StringUtils.hasText(prefix) ? prefix + " " + token : token;
    }
}
