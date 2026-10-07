package cn.jualn.miniapp.module.admin.auth.vo.qrlogin;

import cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminQrSessionVO(String sessionId, AdminQrLoginV2Status status, Instant expiresAt,
        int pollIntervalMs, Instant confirmedAt, Instant consumedAt) {}
