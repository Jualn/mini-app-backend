package cn.jualn.miniapp.module.admin.auth.vo;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class AdminQrSessionCreateVO {
    private String sessionId;
    private String pollSecret;
    private String qrPayload;
    private OffsetDateTime expiresAt;
    private long pollIntervalMs;
}
