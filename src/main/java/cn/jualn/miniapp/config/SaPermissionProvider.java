package cn.jualn.miniapp.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 管理端动态权限提供器。
 */
@Component
@RequiredArgsConstructor
public class SaPermissionProvider implements StpInterface {

    private final UserService userService;
    private final AdminPermissionPolicy permissionPolicy;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        UserProfileBO profile = loadAdminProfile(loginId, loginType);
        return profile == null ? List.of() : permissionPolicy.permissions(profile.getRole());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        UserProfileBO profile = loadAdminProfile(loginId, loginType);
        return profile == null ? List.of() : List.of(permissionPolicy.roleCode(profile.getRole()));
    }

    private UserProfileBO loadAdminProfile(Object loginId, String loginType) {
        if (!AdminStpUtil.LOGIN_TYPE.equals(loginType) || loginId == null) {
            return null;
        }
        try {
            UserProfileBO profile = userService.getUserProfile(Long.parseLong(loginId.toString()));
            return permissionPolicy.canLogin(profile) ? profile : null;
        } catch (BusinessException | NumberFormatException e) {
            return null;
        }
    }
}
