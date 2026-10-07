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

    private Duration tokenTtl = Duration.ofHours(8);
}
