package cn.jualn.miniapp.module.documentimport;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "document-import")
public class DocumentImportProperties {
    private Duration streamTimeout = Duration.ofMinutes(5);
    private Duration aiReadIdleTimeout = Duration.ofSeconds(60);
    private boolean diagnosticsEnabled;
    private String diagnosticsDirectory = "target/document-import-samples";

    public boolean isDiagnosticsEnabled() { return diagnosticsEnabled; }
    public void setDiagnosticsEnabled(boolean value) { diagnosticsEnabled = value; }
    public String getDiagnosticsDirectory() { return diagnosticsDirectory; }
    public void setDiagnosticsDirectory(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("diagnostics-directory is required");
        diagnosticsDirectory = value;
    }

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
