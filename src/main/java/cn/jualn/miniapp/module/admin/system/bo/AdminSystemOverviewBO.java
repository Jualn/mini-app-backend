package cn.jualn.miniapp.module.admin.system.bo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class AdminSystemOverviewBO {

    private String environment;
    private String release;
    private OffsetDateTime checkedAt;
    private ApplicationHealthBO health;
    private List<ServiceHealthBO> services;
    private RuntimeMetricsBO runtimeMetrics;
    private AsyncRuntimeBO asyncRuntime;

    @Data
    @Builder
    public static class ApplicationHealthBO {
        private HealthStateBO liveness;
        private HealthStateBO readiness;
    }

    @Data
    @Builder
    public static class HealthStateBO {
        private String status;
        private String statusLabel;
        private String description;
    }

    @Data
    @Builder
    public static class ServiceHealthBO {
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
    public static class RuntimeMetricsBO {
        private String scope;
        private OffsetDateTime startedAt;
        private long uptimeSeconds;
        private HttpMetricsBO http;
        private ResourceMetricsBO resources;
        private AsyncEventMetricsBO asyncEvents;
    }

    @Data
    @Builder
    public static class HttpMetricsBO {
        private boolean available;
        private long requestCount;
        private long serverErrorCount;
        private Double averageDurationMs;
        private Double maxDurationMs;
        private List<HttpRouteMetricBO> routes;
    }

    @Data
    @Builder
    public static class HttpRouteMetricBO {
        private String method;
        private String route;
        private long requestCount;
        private long serverErrorCount;
        private double averageDurationMs;
        private double maxDurationMs;
    }

    @Data
    @Builder
    public static class ResourceMetricsBO {
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
    public static class AsyncEventMetricsBO {
        private OutboxEventMetricsBO outbox;
        private JobEventMetricsBO jobs;
        private StreamEventMetricsBO stream;
    }

    @Data
    @Builder
    public static class OutboxEventMetricsBO {
        private long publishAttempts;
        private long successCount;
        private long failureCount;
        private long retryCount;
        private long deadCount;
    }

    @Data
    @Builder
    public static class JobEventMetricsBO {
        private long executionCount;
        private long successCount;
        private long retryCount;
        private long deadCount;
        private Double averageDurationMs;
        private Double maxDurationMs;
    }

    @Data
    @Builder
    public static class StreamEventMetricsBO {
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
    public static class AsyncRuntimeBO {
        private long sampleMaxAgeSeconds;
        private boolean databaseAvailable;
        private boolean streamAvailable;
        private OutboxStateBO outbox;
        private JobStateBO jobs;
        private StreamStateBO stream;
    }

    @Data
    @Builder
    public static class OutboxStateBO {
        private Long pending;
        private Long oldestPendingAgeSeconds;
    }

    @Data
    @Builder
    public static class JobStateBO {
        private Long ready;
        private Long running;
        private Long retryWaiting;
        private Long dead;
        private Long oldestOverdueAgeSeconds;
    }

    @Data
    @Builder
    public static class StreamStateBO {
        private Long length;
        private Long lag;
        private Long pending;
        private Long consumers;
        private Long oldestPendingAgeSeconds;
    }
}
