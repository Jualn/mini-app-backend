package cn.jualn.miniapp.module.admin.auth.support;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.module.admin.auth.vo.AdminIdentityVO;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 管理角色与权限点的唯一映射位置。
 */
@Component
public class AdminPermissionPolicy {

    public static final String ALL = "*";
    public static final String CONTENT_READ = "content:read";
    public static final String CONTENT_MANAGE = "content:manage";
    public static final String REVIEW_READ = "review:read";
    public static final String REVIEW_DECIDE = "review:decide";
    public static final String REPORT_READ = "report:read";
    public static final String REPORT_HANDLE = "report:handle";
    public static final String ACTIVITY_READ = "activity:read";
    public static final String ACTIVITY_EDIT = "activity:edit";
    public static final String ACTIVITY_REGISTRATION_READ = "activity:registration:read";
    public static final String ACTIVITY_REGISTRATION_EXPORT = "activity:registration:export";
    public static final String PUBLIC_EVENT_READ = "public-event:read";
    public static final String PUBLIC_EVENT_EDIT = "public-event:edit";
    public static final String NOTICE_READ = "notice:read";
    public static final String USER_READ = "user:read";
    public static final String USER_MANAGE = "user:manage";
    public static final String USER_ROLE = "user:role";
    public static final String SYSTEM_READ = "system:read";

    private static final List<String> OPERATOR_PERMISSIONS = List.of(
            CONTENT_READ,
            CONTENT_MANAGE,
            REVIEW_READ,
            REVIEW_DECIDE,
            REPORT_READ,
            REPORT_HANDLE,
            ACTIVITY_READ,
            ACTIVITY_EDIT,
            ACTIVITY_REGISTRATION_READ,
            ACTIVITY_REGISTRATION_EXPORT,
            PUBLIC_EVENT_READ,
            PUBLIC_EVENT_EDIT,
            NOTICE_READ,
            USER_READ
    );

    public boolean canLogin(UserProfileBO profile) {
        if (profile == null || profile.getStatus() != UserStatus.NORMAL) {
            return false;
        }
        return profile.getRole() == UserRole.OPR || profile.getRole() == UserRole.ADMIN;
    }

    public List<String> permissions(UserRole role) {
        if (role == UserRole.ADMIN) {
            return List.of(ALL);
        }
        if (role == UserRole.OPR) {
            return OPERATOR_PERMISSIONS;
        }
        return List.of();
    }

    public String roleCode(UserRole role) {
        return role == UserRole.ADMIN ? "admin" : "operator";
    }

    public AdminIdentityVO toIdentity(long userId, UserProfileBO profile) {
        String displayName = profile.getNickname();
        if (displayName == null || displayName.isBlank()) {
            displayName = profile.getRole() == UserRole.ADMIN ? "管理员" : "运营人员";
        }
        return AdminIdentityVO.builder()
                .id(Long.toString(userId))
                .displayName(displayName)
                .avatarText(displayName.substring(0, 1))
                .roleCode(roleCode(profile.getRole()))
                .roleLabel(profile.getRole().getDesc())
                .permissions(permissions(profile.getRole()))
                .build();
    }

    public cn.jualn.miniapp.module.admin.auth.bo.qrlogin.AdminQrIdentityBO toQrIdentity(long userId, UserProfileBO profile) {
        String name = profile.getNickname();
        if (name == null || name.isBlank()) name = profile.getRole() == UserRole.ADMIN ? "管理员" : "运营人员";
        return new cn.jualn.miniapp.module.admin.auth.bo.qrlogin.AdminQrIdentityBO(Long.toString(userId), name,
                name.substring(0, 1), roleCode(profile.getRole()), profile.getRole().getDesc(), permissions(profile.getRole()));
    }
}
