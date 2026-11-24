package cn.jualn.miniapp.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 管理端动态权限提供器。
 */
@Component
@RequiredArgsConstructor
public class SaPermissionProvider implements StpInterface {

    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        var decision = loadAdmin(loginId,loginType);
        return decision==null?List.of():authorization.candidates(decision);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        var decision=loadAdmin(loginId,loginType);
        if(decision==null || !decision.canUseAdmin()) return List.of();
        return decision.grants().stream().map(g->g.roleCode()).distinct().toList();
    }

    private cn.jualn.miniapp.module.authorization.model.RbacModels.Decision loadAdmin(Object loginId, String loginType) {
        if (!AdminStpUtil.LOGIN_TYPE.equals(loginType) || loginId == null) {
            return null;
        }
        try {
            return authorization.current(Long.parseLong(loginId.toString()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
