package cn.jualn.miniapp.module.admin.system.vo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class AdminSystemOverviewVO {

    private String environment;
    private String release;
    private OffsetDateTime checkedAt;
    private ApplicationHealthVO health;
    private List<ServiceHealthVO> services;
    private RuntimeMetricsVO runtimeMetrics;
    private AsyncRuntimeVO asyncRuntime;

    @Data
    @Builder
    public static class ApplicationHealthVO {
        private HealthStateVO liveness;
        private HealthStateVO readiness;
    }

    @Data
    @Builder
    public static class HealthStateVO {
        private String status;
        private String statusLabel;
        private String description;
    }

    @Data
    @Builder
    public static class ServiceHealthVO {
        private String key;
        private String name;
        private String description;
        private String status;
        private String statusLabel;
        private Long latencyMs;
        private OffsetDateTime checkedAt;
    }

    @Data
    @Builder
    public static class RuntimeMetricsVO {
        private String scope;
        private OffsetDateTime startedAt;
        private long uptimeSeconds;
        private HttpMetricsVO http;
        private ResourceMetricsVO resources;
        private AsyncEventMetricsVO asyncEvents;
    }

    @Data
    @Builder
    public static class HttpMetricsVO {
        private boolean available;
        private long requestCount;
        private long serverErrorCount;
        private Double averageDurationMs;
        private Double maxDurationMs;
        private List<HttpRouteMetricVO> routes;
    }

    @Data
    @Builder
    public static class HttpRouteMetricVO {
        private String method;
        private String route;
        private long requestCount;
        private long serverErrorCount;
        private double averageDurationMs;
        private double maxDurationMs;
    }

    @Data
    @Builder
    public static class ResourceMetricsVO {
        private Long heapUsedBytes;
        private Long heapMaxBytes;
        private Long liveThreads;
        private Double processCpuUsage;
        private Double systemCpuUsage;
        private Long hikariActive;
        private Long hikariIdle;
        private Long hikariPending;
        private Long hikariMax;
        private long gcPauseCount;
        private double gcPauseTotalMs;
        private double gcPauseMaxMs;
    }

    @Data
    @Builder
    public static class AsyncEventMetricsVO {
        private OutboxEventMetricsVO outbox;
        private JobEventMetricsVO jobs;
        private StreamEventMetricsVO stream;
    }

    @Data
    @Builder
    public static class OutboxEventMetricsVO {
        private long publishAttempts;
        private long successCount;
        private long failureCount;
        private long retryCount;
        private long deadCount;
    }

    @Data
    @Builder
    public static class JobEventMetricsVO {
        private long executionCount;
        private long successCount;
        private long retryCount;
        private long deadCount;
        private Double averageDurationMs;
        private Double maxDurationMs;
    }

    @Data
    @Builder
    public static class StreamEventMetricsVO {
        private long deliveryCount;
        private long successCount;
        private long retryCount;
        private long deadCount;
        private long reclaimCount;
        private Double averageDurationMs;
        private Double maxDurationMs;
    }

    @Data
    @Builder
    public static class AsyncRuntimeVO {
        private long sampleMaxAgeSeconds;
        private boolean databaseAvailable;
        private boolean streamAvailable;
        private OutboxStateVO outbox;
        private JobStateVO jobs;
        private StreamStateVO stream;
    }

    @Data
    @Builder
    public static class OutboxStateVO {
        private Long pending;
        private Long oldestPendingAgeSeconds;
    }

    @Data
    @Builder
    public static class JobStateVO {
        private Long ready;
        private Long running;
        private Long retryWaiting;
        private Long dead;
        private Long oldestOverdueAgeSeconds;
    }

    @Data
    @Builder
    public static class StreamStateVO {
        private Long length;
        private Long lag;
        private Long pending;
        private Long consumers;
        private Long oldestPendingAgeSeconds;
    }
}
