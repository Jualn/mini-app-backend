package cn.jualn.miniapp.third.cos.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * COS 临时凭证 DTO（来自 STS 响应）。
 */
@Data
@Builder
public class CosTemporaryCredentialDTO {

    private String tmpSecretId;

    private String tmpSecretKey;

    private String sessionToken;

    /** ExpiredTime 字段，单位为 epoch seconds */
    private long expiredTime;

    /** 解析后的过期时间（系统时区） */
    private LocalDateTime expireAt;
}

