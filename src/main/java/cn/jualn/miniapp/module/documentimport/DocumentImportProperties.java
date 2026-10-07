package cn.jualn.miniapp.module.documentimport;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "document-import")
public class DocumentImportProperties {
    private Duration streamTimeout = Duration.ofMinutes(5);
    private Duration aiReadIdleTimeout = Duration.ofSeconds(60);

    public Duration getStreamTimeout() {
        return streamTimeout;
    }

    public void setStreamTimeout(Duration streamTimeout) {
        this.streamTimeout = positive(streamTimeout, "stream-timeout");
    }

    public Duration getAiReadIdleTimeout() {
        return aiReadIdleTimeout;
    }

    public void setAiReadIdleTimeout(Duration aiReadIdleTimeout) {
        this.aiReadIdleTimeout = positive(aiReadIdleTimeout, "ai-read-idle-timeout");
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("document-import." + name + " must be positive");
        }
        return value;
    }
}
