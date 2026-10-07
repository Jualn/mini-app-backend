package cn.jualn.miniapp.module.admin.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "admin-auth.qr-login-v2")
public class AdminQrLoginProperties {
    public static final String PAGE = "subpkg_setting/pages/admin-login-confirm/index";
    private boolean enabled;
    private String envVersion;
    private boolean checkPath = true;
    private Duration sessionTtl = Duration.ofMinutes(2);
    private Duration retention = Duration.ofMinutes(5);
    private long pollIntervalMs = 1500;
    private int createIpLimit = 10;
    private int createGlobalLimit = 60;
    private int generationConcurrency = 4;

    @PostConstruct
    public void validate() {
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()
                || sessionTtl.compareTo(Duration.ofMinutes(5)) > 0 || retention == null
                || retention.compareTo(Duration.ofMinutes(5)) < 0 || pollIntervalMs < 1000 || pollIntervalMs > Integer.MAX_VALUE
                || createIpLimit < 1 || createGlobalLimit < 1 || generationConcurrency < 1) {
            throw new IllegalArgumentException("Invalid admin QR login v2 limits");
        }
        if (enabled && !Set.of("release", "trial", "develop").contains(envVersion == null ? "" : envVersion)) {
            throw new IllegalArgumentException("Enabled admin QR login v2 requires env-version");
        }
    }
}
