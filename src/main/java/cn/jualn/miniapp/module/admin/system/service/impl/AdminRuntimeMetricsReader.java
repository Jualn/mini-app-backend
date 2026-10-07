package cn.jualn.miniapp.module.admin.system.service.impl;

import cn.jualn.miniapp.infrastructure.async.AsyncMetricNames;
import cn.jualn.miniapp.module.admin.system.bo.AdminSystemOverviewBO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
class AdminRuntimeMetricsReader {

    private static final int MAX_HTTP_ROUTES = 12;
    private final MeterRegistry meterRegistry;

    AdminSystemOverviewBO.RuntimeMetricsBO snapshot() {
        long startedAtMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
        OffsetDateTime startedAt = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(startedAtMillis), ZoneId.systemDefault());
        long uptimeSeconds = Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
        return AdminSystemOverviewBO.RuntimeMetricsBO.builder()
                .scope("CURRENT_PROCESS")
                .startedAt(startedAt)
                .uptimeSeconds(uptimeSeconds)
                .http(httpMetrics())
                .resources(resourceMetrics())
                .asyncEvents(asyncEventMetrics())
                .build();
    }

    private AdminSystemOverviewBO.HttpMetricsBO httpMetrics() {
        Collection<Timer> timers = meterRegistry.find("http.server.requests").timers();
        TimerAggregate total = new TimerAggregate();
        Map<RouteKey, TimerAggregate> routeAggregates = new LinkedHashMap<>();
        for (Timer timer : timers) {
            total.add(timer, isServerError(timer));
            String method = timer.getId().getTag("method");
            String route = timer.getId().getTag("uri");
            if (StringUtils.hasText(method) && StringUtils.hasText(route)) {
                routeAggregates.computeIfAbsent(new RouteKey(method, route), ignored -> new TimerAggregate())
                        .add(timer, isServerError(timer));
            }
        }
        List<AdminSystemOverviewBO.HttpRouteMetricBO> routes = routeAggregates.entrySet().stream()
                .filter(entry -> entry.getValue().count > 0L)
                .sorted(Map.Entry.<RouteKey, TimerAggregate>comparingByValue(
                        Comparator.comparingDouble(aggregate -> aggregate.totalMilliseconds)).reversed())
                .limit(MAX_HTTP_ROUTES)
                .map(entry -> AdminSystemOverviewBO.HttpRouteMetricBO.builder()
                        .method(entry.getKey().method)
                        .route(entry.getKey().route)
                        .requestCount(entry.getValue().count)
                        .serverErrorCount(entry.getValue().serverErrorCount)
                        .averageDurationMs(round(entry.getValue().averageMilliseconds()))
                        .maxDurationMs(round(entry.getValue().maxMilliseconds))
                        .build())
                .toList();
        return AdminSystemOverviewBO.HttpMetricsBO.builder()
                .available(total.count > 0L)
                .requestCount(total.count)
                .serverErrorCount(total.serverErrorCount)
                .averageDurationMs(total.count == 0L ? null : round(total.averageMilliseconds()))
                .maxDurationMs(total.count == 0L ? null : round(total.maxMilliseconds))
                .routes(routes)
                .build();
    }

    private AdminSystemOverviewBO.ResourceMetricsBO resourceMetrics() {
        TimerAggregate gcPause = aggregateTimers("jvm.gc.pause");
        return AdminSystemOverviewBO.ResourceMetricsBO.builder()
                .heapUsedBytes(sumGauges("jvm.memory.used", gauge -> "heap".equals(gauge.getId().getTag("area"))))
                .heapMaxBytes(sumGauges("jvm.memory.max", gauge -> "heap".equals(gauge.getId().getTag("area"))))
                .liveThreads(sumGauges("jvm.threads.live", gauge -> true))
                .processCpuUsage(firstGauge("process.cpu.usage"))
                .systemCpuUsage(firstGauge("system.cpu.usage"))
                .hikariActive(sumGauges("hikaricp.connections.active", gauge -> true))
                .hikariIdle(sumGauges("hikaricp.connections.idle", gauge -> true))
                .hikariPending(sumGauges("hikaricp.connections.pending", gauge -> true))
                .hikariMax(sumGauges("hikaricp.connections.max", gauge -> true))
                .gcPauseCount(gcPause.count)
                .gcPauseTotalMs(round(gcPause.totalMilliseconds))
                .gcPauseMaxMs(round(gcPause.maxMilliseconds))
                .build();
    }

    private AdminSystemOverviewBO.AsyncEventMetricsBO asyncEventMetrics() {
        TimerAggregate jobDuration = aggregateTimers(AsyncMetricNames.JOB_DURATION);
        TimerAggregate streamDuration = aggregateTimers(AsyncMetricNames.STREAM_DURATION);
        return AdminSystemOverviewBO.AsyncEventMetricsBO.builder()
                .outbox(AdminSystemOverviewBO.OutboxEventMetricsBO.builder()
                        .publishAttempts(counterCount(AsyncMetricNames.OUTBOX_PUBLISH, counter -> true))
                        .successCount(counterCount(AsyncMetricNames.OUTBOX_PUBLISH,
                                counter -> hasTag(counter, "result", "success")))
                        .failureCount(counterCount(AsyncMetricNames.OUTBOX_PUBLISH,
                                counter -> hasTag(counter, "result", "failure")))
                        .retryCount(counterCount(AsyncMetricNames.OUTBOX_RETRY, counter -> true))
                        .deadCount(counterCount(AsyncMetricNames.OUTBOX_DEAD, counter -> true))
                        .build())
                .jobs(AdminSystemOverviewBO.JobEventMetricsBO.builder()
                        .executionCount(counterCount(AsyncMetricNames.JOB_EXECUTION, counter -> true))
                        .successCount(counterCount(AsyncMetricNames.JOB_EXECUTION,
                                counter -> hasTag(counter, "result", "success")))
                        .retryCount(counterCount(AsyncMetricNames.JOB_RETRY, counter -> true))
                        .deadCount(counterCount(AsyncMetricNames.JOB_DEAD, counter -> true))
                        .averageDurationMs(nullableAverage(jobDuration))
                        .maxDurationMs(nullableMax(jobDuration))
                        .build())
                .stream(AdminSystemOverviewBO.StreamEventMetricsBO.builder()
                        .deliveryCount(counterCount(AsyncMetricNames.STREAM_DELIVERY, counter -> true))
                        .successCount(counterCount(AsyncMetricNames.STREAM_DELIVERY,
                                counter -> hasTag(counter, "result", "success")))
                        .retryCount(counterCount(AsyncMetricNames.STREAM_DELIVERY,
                                counter -> hasTag(counter, "result", "retry")))
                        .deadCount(counterCount(AsyncMetricNames.STREAM_DELIVERY,
                                counter -> hasTag(counter, "result", "dead")))
                        .reclaimCount(counterCount(AsyncMetricNames.STREAM_RECLAIM, counter -> true))
                        .averageDurationMs(nullableAverage(streamDuration))
                        .maxDurationMs(nullableMax(streamDuration))
                        .build())
                .build();
    }

    private TimerAggregate aggregateTimers(String meterName) {
        TimerAggregate aggregate = new TimerAggregate();
        for (Timer timer : meterRegistry.find(meterName).timers()) {
            aggregate.add(timer, false);
        }
        return aggregate;
    }

    private Long sumGauges(String meterName, Predicate<Gauge> filter) {
        boolean found = false;
        double total = 0D;
        for (Gauge gauge : meterRegistry.find(meterName).gauges()) {
            double value = gauge.value();
            if (filter.test(gauge) && Double.isFinite(value) && value >= 0D) {
                found = true;
                total += value;
            }
        }
        return found ? Math.round(total) : null;
    }

    private Double firstGauge(String meterName) {
        Gauge gauge = meterRegistry.find(meterName).gauge();
        if (gauge == null) return null;
        double value = gauge.value();
        return Double.isFinite(value) && value >= 0D ? round(Math.min(value, 1D)) : null;
    }

    private long counterCount(String meterName, Predicate<Counter> filter) {
        double total = 0D;
        for (Counter counter : meterRegistry.find(meterName).counters()) {
            if (filter.test(counter)) total += counter.count();
        }
        return Math.max(0L, Math.round(total));
    }

    private boolean hasTag(Counter counter, String key, String value) {
        return value.equals(counter.getId().getTag(key));
    }

    private boolean isServerError(Timer timer) {
        String status = timer.getId().getTag("status");
        return status != null && status.startsWith("5");
    }

    private Double nullableAverage(TimerAggregate aggregate) {
        return aggregate.count == 0L ? null : round(aggregate.averageMilliseconds());
    }

    private Double nullableMax(TimerAggregate aggregate) {
        return aggregate.count == 0L ? null : round(aggregate.maxMilliseconds);
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private record RouteKey(String method, String route) {
    }

    private static final class TimerAggregate {
        private long count;
        private long serverErrorCount;
        private double totalMilliseconds;
        private double maxMilliseconds;

        private void add(Timer timer, boolean serverError) {
            long timerCount = timer.count();
            count += timerCount;
            if (serverError) serverErrorCount += timerCount;
            totalMilliseconds += timer.totalTime(TimeUnit.MILLISECONDS);
            maxMilliseconds = Math.max(maxMilliseconds, timer.max(TimeUnit.MILLISECONDS));
        }

        private double averageMilliseconds() {
            return count == 0L ? 0D : totalMilliseconds / count;
        }
    }
}
