package cn.jualn.miniapp.module.admin.system.service.impl;

import cn.jualn.miniapp.module.admin.system.bo.AdminSystemOverviewBO;
import cn.jualn.miniapp.module.admin.system.service.AdminSystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminSystemServiceImpl implements AdminSystemService {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final Environment environment;

    @Override
    public AdminSystemOverviewBO getOverview() {
        LocalDateTime checkedAt = LocalDateTime.now();
        List<AdminSystemOverviewBO.ServiceHealthBO> services = new ArrayList<>();
        services.add(healthy(
                "admin-api",
                "管理端 API",
                "当前请求已通过管理端认证与权限校验",
                0L,
                checkedAt));
        services.add(checkMySql(checkedAt));
        services.add(checkRedis(checkedAt));
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
                .services(services)
                .build();
    }

    private AdminSystemOverviewBO.ServiceHealthBO checkMySql(LocalDateTime checkedAt) {
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

    private AdminSystemOverviewBO.ServiceHealthBO checkRedis(LocalDateTime checkedAt) {
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
            LocalDateTime checkedAt,
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
            LocalDateTime checkedAt) {
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
            LocalDateTime checkedAt) {
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
