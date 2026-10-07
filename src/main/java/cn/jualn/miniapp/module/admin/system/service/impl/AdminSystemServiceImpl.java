package cn.jualn.miniapp.module.admin.system.service.impl;

import cn.jualn.miniapp.infrastructure.async.AsyncMetricNames;
import cn.jualn.miniapp.module.admin.system.bo.AdminSystemOverviewBO;
import cn.jualn.miniapp.module.admin.system.service.AdminSystemService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminSystemServiceImpl implements AdminSystemService {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final Environment environment;
    private final ApplicationAvailability applicationAvailability;
    private final MeterRegistry meterRegistry;
    private final AdminRuntimeMetricsReader runtimeMetricsReader;

    @Override
    public AdminSystemOverviewBO getOverview() {
        OffsetDateTime checkedAt = OffsetDateTime.now();
        List<AdminSystemOverviewBO.ServiceHealthBO> services = new ArrayList<>();
        services.add(healthy(
                "admin-api",
                "管理端 API",
                "当前请求已通过管理端认证与权限校验",
                0L,
                checkedAt));
        AdminSystemOverviewBO.ServiceHealthBO mysql = checkMySql(checkedAt);
        AdminSystemOverviewBO.ServiceHealthBO redis = checkRedis(checkedAt);
        services.add(mysql);
        services.add(redis);
        services.add(configurationState(
                "cos",
                "腾讯云 COS",
                "对象存储仅检查必要配置，不主动访问外部服务",
                checkedAt,
                "cos.bucket",
                "cos.region",
                "cos.public-url-prefix"));
        services.add(configurationState(
                "wechat",
                "微信开放能力",
                "微信 AppID 仅做配置检查，不代表接口调用可用",
                checkedAt,
                "wx.ma.app-id"));

        return AdminSystemOverviewBO.builder()
                .environment(String.join(",", environment.getActiveProfiles()))
                .release(resolveRelease())
                .checkedAt(checkedAt)
                .health(applicationHealth(mysql, redis))
                .services(services)
                .runtimeMetrics(runtimeMetricsReader.snapshot())
                .asyncRuntime(asyncRuntime())
                .build();
    }

    private AdminSystemOverviewBO.ApplicationHealthBO applicationHealth(
            AdminSystemOverviewBO.ServiceHealthBO mysql,
            AdminSystemOverviewBO.ServiceHealthBO redis) {
        boolean live = applicationAvailability.getLivenessState() == LivenessState.CORRECT;
        boolean ready = applicationAvailability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC
                && isHealthy(mysql) && isHealthy(redis);
        return AdminSystemOverviewBO.ApplicationHealthBO.builder()
                .liveness(healthState(live,
                        "Spring 应用生命周期状态；依赖短暂失败不会触发进程重启"))
                .readiness(healthState(ready,
                        "接流量状态，并纳入当前核心依赖 MySQL 与 Redis"))
                .build();
    }

    private AdminSystemOverviewBO.HealthStateBO healthState(boolean up, String description) {
        return AdminSystemOverviewBO.HealthStateBO.builder()
                .status(up ? "UP" : "DOWN")
                .statusLabel(up ? "正常" : "不可用")
                .description(description)
                .build();
    }

    private boolean isHealthy(AdminSystemOverviewBO.ServiceHealthBO service) {
        return "healthy".equals(service.getStatus());
    }

    private AdminSystemOverviewBO.AsyncRuntimeBO asyncRuntime() {
        long refreshIntervalMs = environment.getProperty(
                "async-processing.metrics.refresh-interval", Long.class, 30_000L);
        return AdminSystemOverviewBO.AsyncRuntimeBO.builder()
                .sampleMaxAgeSeconds(Math.max(1L, (refreshIntervalMs + 999L) / 1000L))
                .databaseAvailable(isAvailable(AsyncMetricNames.DATABASE_STATE_AVAILABLE))
                .streamAvailable(isAvailable(AsyncMetricNames.STREAM_AVAILABLE))
                .outbox(AdminSystemOverviewBO.OutboxStateBO.builder()
                        .pending(gaugeValue(AsyncMetricNames.OUTBOX_PENDING))
                        .oldestPendingAgeSeconds(gaugeValue(AsyncMetricNames.OUTBOX_OLDEST_PENDING_AGE))
                        .build())
                .jobs(AdminSystemOverviewBO.JobStateBO.builder()
                        .ready(gaugeValue(AsyncMetricNames.JOB_READY))
                        .running(gaugeValue(AsyncMetricNames.JOB_RUNNING))
                        .retryWaiting(gaugeValue(AsyncMetricNames.JOB_RETRY_WAITING))
                        .dead(gaugeValue(AsyncMetricNames.JOB_DEAD_CURRENT))
                        .oldestOverdueAgeSeconds(gaugeValue(AsyncMetricNames.JOB_OLDEST_OVERDUE_AGE))
                        .build())
                .stream(AdminSystemOverviewBO.StreamStateBO.builder()
                        .length(gaugeValue(AsyncMetricNames.STREAM_LENGTH))
                        .lag(gaugeValue(AsyncMetricNames.STREAM_LAG))
                        .pending(gaugeValue(AsyncMetricNames.STREAM_PENDING))
                        .consumers(gaugeValue(AsyncMetricNames.STREAM_CONSUMERS))
                        .oldestPendingAgeSeconds(gaugeValue(AsyncMetricNames.STREAM_OLDEST_PENDING_AGE))
                        .build())
                .build();
    }

    private boolean isAvailable(String meterName) {
        Long value = gaugeValue(meterName);
        return value != null && value == 1L;
    }

    private Long gaugeValue(String meterName) {
        Gauge gauge = meterRegistry.find(meterName).gauge();
        if (gauge == null) return null;
        double value = gauge.value();
        return Double.isFinite(value) && value >= 0D ? Math.round(value) : null;
    }

    private AdminSystemOverviewBO.ServiceHealthBO checkMySql(OffsetDateTime checkedAt) {
        long startedAt = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return valid
                    ? healthy("mysql", "MySQL", "核心业务数据事实来源", elapsedMs(startedAt), checkedAt)
                    : degraded("mysql", "MySQL", "连接校验未通过", elapsedMs(startedAt), checkedAt);
        } catch (Exception ignored) {
            return degraded("mysql", "MySQL", "连接探测失败", elapsedMs(startedAt), checkedAt);
        }
    }

    private AdminSystemOverviewBO.ServiceHealthBO checkRedis(OffsetDateTime checkedAt) {
        long startedAt = System.nanoTime();
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            String pong = connection.ping();
            return "PONG".equalsIgnoreCase(pong)
                    ? healthy("redis", "Redis", "缓存、会话与延迟任务基础设施", elapsedMs(startedAt), checkedAt)
                    : degraded("redis", "Redis", "连接响应异常", elapsedMs(startedAt), checkedAt);
        } catch (Exception ignored) {
            return degraded("redis", "Redis", "连接探测失败", elapsedMs(startedAt), checkedAt);
        }
    }

    private AdminSystemOverviewBO.ServiceHealthBO configurationState(
            String key,
            String name,
            String description,
            OffsetDateTime checkedAt,
            String... propertyNames) {
        boolean configured = true;
        for (String propertyName : propertyNames) {
            if (!StringUtils.hasText(environment.getProperty(propertyName))) {
                configured = false;
                break;
            }
        }
        return AdminSystemOverviewBO.ServiceHealthBO.builder()
                .key(key)
                .name(name)
                .description(description)
                .status(configured ? "degraded" : "not-configured")
                .statusLabel(configured ? "已配置，未探测" : "未完整配置")
                .latencyMs(null)
                .checkedAt(checkedAt)
                .build();
    }

    private AdminSystemOverviewBO.ServiceHealthBO healthy(
            String key,
            String name,
            String description,
            Long latencyMs,
            OffsetDateTime checkedAt) {
        return AdminSystemOverviewBO.ServiceHealthBO.builder()
                .key(key)
                .name(name)
                .description(description)
                .status("healthy")
                .statusLabel("正常")
                .latencyMs(latencyMs)
                .checkedAt(checkedAt)
                .build();
    }

    private AdminSystemOverviewBO.ServiceHealthBO degraded(
            String key,
            String name,
            String description,
            Long latencyMs,
            OffsetDateTime checkedAt) {
        return AdminSystemOverviewBO.ServiceHealthBO.builder()
                .key(key)
                .name(name)
                .description(description)
                .status("degraded")
                .statusLabel("探测失败")
                .latencyMs(latencyMs)
                .checkedAt(checkedAt)
                .build();
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private String resolveRelease() {
        String version = AdminSystemServiceImpl.class.getPackage().getImplementationVersion();
        return StringUtils.hasText(version) ? version : "development";
    }
}
