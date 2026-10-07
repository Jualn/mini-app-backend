package cn.jualn.miniapp.module.admin.auth.bo.qrlogin;

import cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status;

public record AdminQrSessionBO(String sessionId, AdminQrLoginV2Status status, long expiresAt,
        long pollIntervalMs, Long confirmedAt, Long consumedAt) {}
