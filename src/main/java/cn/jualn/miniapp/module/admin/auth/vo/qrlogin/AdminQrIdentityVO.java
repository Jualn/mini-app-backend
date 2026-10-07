package cn.jualn.miniapp.module.admin.auth.vo.qrlogin;

import java.util.List;

public record AdminQrIdentityVO(String id, String displayName, String avatarText, String roleCode,
        String roleLabel, List<String> permissions) {}
