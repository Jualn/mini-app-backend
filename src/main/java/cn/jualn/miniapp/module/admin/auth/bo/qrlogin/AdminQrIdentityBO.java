package cn.jualn.miniapp.module.admin.auth.bo.qrlogin;

import java.util.List;

public record AdminQrIdentityBO(String id, String displayName, String avatarText, String roleCode,
        String roleLabel, List<String> permissions) {
    public AdminQrIdentityBO { permissions = List.copyOf(permissions); }
}
