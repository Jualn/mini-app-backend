package cn.jualn.miniapp.module.admin.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 管理端认证配置。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "admin-auth")
public class AdminAuthProperties {

    private Duration qrSessionTtl = Duration.ofMinutes(2);
    private Duration expiredRetention = Duration.ofMinutes(1);
    private Duration tokenTtl = Duration.ofHours(8);
    private Duration rateLimitWindow = Duration.ofMinutes(1);
    private int createLimitPerWindow = 10;
    private int confirmLimitPerWindow = 10;
    private long pollIntervalMs = 1500;
    private String qrPayloadPrefix = "jualn-admin-login:";
}
