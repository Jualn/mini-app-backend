package cn.jualn.miniapp.third.cos.config;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** CI video policy is explicit; disabled installations never call the paid API. */
@Getter @Setter @Component @Validated
@ConfigurationProperties(prefix = "cos.video-audit")
public class CosVideoAuditProperties {
    private boolean enabled;
    @Pattern(regexp="[A-Za-z0-9_-]{0,128}") private String bizType = "";
    @Pattern(regexp="[A-Za-z0-9_.-]{1,64}") private String policyRevision = "1";
    @Min(1) @Max(60) private int snapshotIntervalSeconds = 1;
    @Min(5) @Max(300) private int pollIntervalSeconds = 30;
    @Min(10) @Max(1440) private int deadlineMinutes = 120;
    @AssertTrue(message="cos.video-audit.biz-type is required when enabled")
    public boolean isPolicyConfigured() { return !enabled || !bizType.isBlank(); }
}
