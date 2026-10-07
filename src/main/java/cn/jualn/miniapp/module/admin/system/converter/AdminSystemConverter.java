package cn.jualn.miniapp.module.admin.system.converter;

import cn.jualn.miniapp.module.admin.system.bo.AdminSystemOverviewBO;
import cn.jualn.miniapp.module.admin.system.vo.AdminSystemOverviewVO;
import org.springframework.stereotype.Component;

@Component
public class AdminSystemConverter {

    public AdminSystemOverviewVO toOverviewVO(AdminSystemOverviewBO overview) {
        return AdminSystemOverviewVO.builder()
                .environment(overview.getEnvironment())
                .release(overview.getRelease())
                .checkedAt(overview.getCheckedAt())
                .health(toHealthVO(overview.getHealth()))
                .services(overview.getServices().stream().map(this::toServiceVO).toList())
                .runtimeMetrics(toRuntimeMetricsVO(overview.getRuntimeMetrics()))
                .asyncRuntime(toAsyncRuntimeVO(overview.getAsyncRuntime()))
                .build();
    }

    private AdminSystemOverviewVO.ApplicationHealthVO toHealthVO(
            AdminSystemOverviewBO.ApplicationHealthBO health) {
        return AdminSystemOverviewVO.ApplicationHealthVO.builder()
                .liveness(toHealthStateVO(health.getLiveness()))
                .readiness(toHealthStateVO(health.getReadiness()))
                .build();
    }

    private AdminSystemOverviewVO.HealthStateVO toHealthStateVO(
            AdminSystemOverviewBO.HealthStateBO state) {
        return AdminSystemOverviewVO.HealthStateVO.builder()
                .status(state.getStatus())
                .statusLabel(state.getStatusLabel())
                .description(state.getDescription())
                .build();
    }

    private AdminSystemOverviewVO.ServiceHealthVO toServiceVO(
            AdminSystemOverviewBO.ServiceHealthBO service) {
        return AdminSystemOverviewVO.ServiceHealthVO.builder()
                .key(service.getKey())
                .name(service.getName())
                .description(service.getDescription())
                .status(service.getStatus())
                .statusLabel(service.getStatusLabel())
                .latencyMs(service.getLatencyMs())
                .checkedAt(service.getCheckedAt())
                .build();
    }

    private AdminSystemOverviewVO.RuntimeMetricsVO toRuntimeMetricsVO(
            AdminSystemOverviewBO.RuntimeMetricsBO metrics) {
        return AdminSystemOverviewVO.RuntimeMetricsVO.builder()
                .scope(metrics.getScope())
                .startedAt(metrics.getStartedAt())
                .uptimeSeconds(metrics.getUptimeSeconds())
                .http(AdminSystemOverviewVO.HttpMetricsVO.builder()
                        .available(metrics.getHttp().isAvailable())
                        .requestCount(metrics.getHttp().getRequestCount())
                        .serverErrorCount(metrics.getHttp().getServerErrorCount())
                        .averageDurationMs(metrics.getHttp().getAverageDurationMs())
                        .maxDurationMs(metrics.getHttp().getMaxDurationMs())
                        .routes(metrics.getHttp().getRoutes().stream()
                                .map(route -> AdminSystemOverviewVO.HttpRouteMetricVO.builder()
                                        .method(route.getMethod())
                                        .route(route.getRoute())
                                        .requestCount(route.getRequestCount())
                                        .serverErrorCount(route.getServerErrorCount())
                                        .averageDurationMs(route.getAverageDurationMs())
                                        .maxDurationMs(route.getMaxDurationMs())
                                        .build())
                                .toList())
                        .build())
                .resources(AdminSystemOverviewVO.ResourceMetricsVO.builder()
                        .heapUsedBytes(metrics.getResources().getHeapUsedBytes())
                        .heapMaxBytes(metrics.getResources().getHeapMaxBytes())
                        .liveThreads(metrics.getResources().getLiveThreads())
                        .processCpuUsage(metrics.getResources().getProcessCpuUsage())
                        .systemCpuUsage(metrics.getResources().getSystemCpuUsage())
                        .hikariActive(metrics.getResources().getHikariActive())
                        .hikariIdle(metrics.getResources().getHikariIdle())
                        .hikariPending(metrics.getResources().getHikariPending())
                        .hikariMax(metrics.getResources().getHikariMax())
                        .gcPauseCount(metrics.getResources().getGcPauseCount())
                        .gcPauseTotalMs(metrics.getResources().getGcPauseTotalMs())
                        .gcPauseMaxMs(metrics.getResources().getGcPauseMaxMs())
                        .build())
                .asyncEvents(AdminSystemOverviewVO.AsyncEventMetricsVO.builder()
                        .outbox(AdminSystemOverviewVO.OutboxEventMetricsVO.builder()
                                .publishAttempts(metrics.getAsyncEvents().getOutbox().getPublishAttempts())
                                .successCount(metrics.getAsyncEvents().getOutbox().getSuccessCount())
                                .failureCount(metrics.getAsyncEvents().getOutbox().getFailureCount())
                                .retryCount(metrics.getAsyncEvents().getOutbox().getRetryCount())
                                .deadCount(metrics.getAsyncEvents().getOutbox().getDeadCount())
                                .build())
                        .jobs(AdminSystemOverviewVO.JobEventMetricsVO.builder()
                                .executionCount(metrics.getAsyncEvents().getJobs().getExecutionCount())
                                .successCount(metrics.getAsyncEvents().getJobs().getSuccessCount())
                                .retryCount(metrics.getAsyncEvents().getJobs().getRetryCount())
                                .deadCount(metrics.getAsyncEvents().getJobs().getDeadCount())
                                .averageDurationMs(metrics.getAsyncEvents().getJobs().getAverageDurationMs())
                                .maxDurationMs(metrics.getAsyncEvents().getJobs().getMaxDurationMs())
                                .build())
                        .stream(AdminSystemOverviewVO.StreamEventMetricsVO.builder()
                                .deliveryCount(metrics.getAsyncEvents().getStream().getDeliveryCount())
                                .successCount(metrics.getAsyncEvents().getStream().getSuccessCount())
                                .retryCount(metrics.getAsyncEvents().getStream().getRetryCount())
                                .deadCount(metrics.getAsyncEvents().getStream().getDeadCount())
                                .reclaimCount(metrics.getAsyncEvents().getStream().getReclaimCount())
                                .averageDurationMs(metrics.getAsyncEvents().getStream().getAverageDurationMs())
                                .maxDurationMs(metrics.getAsyncEvents().getStream().getMaxDurationMs())
                                .build())
                        .build())
                .build();
    }

    private AdminSystemOverviewVO.AsyncRuntimeVO toAsyncRuntimeVO(
            AdminSystemOverviewBO.AsyncRuntimeBO runtime) {
        return AdminSystemOverviewVO.AsyncRuntimeVO.builder()
                .sampleMaxAgeSeconds(runtime.getSampleMaxAgeSeconds())
                .databaseAvailable(runtime.isDatabaseAvailable())
                .streamAvailable(runtime.isStreamAvailable())
                .outbox(AdminSystemOverviewVO.OutboxStateVO.builder()
                        .pending(runtime.getOutbox().getPending())
                        .oldestPendingAgeSeconds(runtime.getOutbox().getOldestPendingAgeSeconds())
                        .build())
                .jobs(AdminSystemOverviewVO.JobStateVO.builder()
                        .ready(runtime.getJobs().getReady())
                        .running(runtime.getJobs().getRunning())
                        .retryWaiting(runtime.getJobs().getRetryWaiting())
                        .dead(runtime.getJobs().getDead())
                        .oldestOverdueAgeSeconds(runtime.getJobs().getOldestOverdueAgeSeconds())
                        .build())
                .stream(AdminSystemOverviewVO.StreamStateVO.builder()
                        .length(runtime.getStream().getLength())
                        .lag(runtime.getStream().getLag())
                        .pending(runtime.getStream().getPending())
                        .consumers(runtime.getStream().getConsumers())
                        .oldestPendingAgeSeconds(runtime.getStream().getOldestPendingAgeSeconds())
                        .build())
                .build();
    }
}
