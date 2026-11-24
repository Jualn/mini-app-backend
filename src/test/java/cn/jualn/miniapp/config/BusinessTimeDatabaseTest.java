package cn.jualn.miniapp.config;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper;
import cn.jualn.miniapp.module.media.service.MediaUploadRecordService;
import cn.jualn.miniapp.module.user.mapper.UserAuthRow;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fresh owned schema only. Uses the application's real connection settings and MySQL/MyBatis. */
@EnabledIfSystemProperty(named="managed.person.test.jdbcUrl", matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/independent_person_test_[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock("default-time-zone")
class BusinessTimeDatabaseTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime LEGACY_TIME = LocalDateTime.of(2026, 10, 28, 10, 15, 15);
    private HikariDataSource source;
    private JdbcTemplate db;
    private Flyway flyway;
    private MediaUploadRecordMapper uploads;
    private long migratedAudit;
    private long ordinaryAudit;
    private long manualAudit;

    @BeforeAll void start() throws Exception {
        String url = System.getProperty("managed.person.test.jdbcUrl");
        String schema = url.substring(url.lastIndexOf('/') + 1);
        String user = Objects.requireNonNull(System.getenv("MANAGED_PERSON_TEST_DB_USER"));
        String password = Objects.requireNonNull(System.getenv("MANAGED_PERSON_TEST_DB_PASSWORD"));
        var server = new JdbcTemplate(new DriverManagerDataSource(url.substring(0, url.lastIndexOf('/') + 1)
                + "?allowPublicKeyRetrieval=true&sslMode=DISABLED", user, password));
        assertEquals(System.getProperty("managed.person.test.serverUuid"), server.queryForObject("SELECT @@server_uuid", String.class));
        assertEquals(0, server.queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?", Integer.class, schema));
        server.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        var properties = Objects.requireNonNull(yaml.getObject());
        String configuredUrl = properties.getProperty("spring.datasource.url");
        var productionYaml = new YamlPropertiesFactoryBean();
        productionYaml.setResources(new ClassPathResource("application-prod.yaml"));
        String productionUrl = Objects.requireNonNull(productionYaml.getObject()).getProperty("spring.datasource.url");
        assertEquals(configuredUrl.substring(configuredUrl.indexOf('?')), productionUrl.substring(productionUrl.indexOf('?')));
        var config = new HikariConfig();
        config.setJdbcUrl(url + configuredUrl.substring(configuredUrl.indexOf('?')));
        config.setUsername(user); config.setPassword(password);
        config.setConnectionInitSql(properties.getProperty("spring.datasource.hikari.connection-init-sql"));
        config.setMinimumIdle(0); config.setMaximumPoolSize(2);
        source = new HikariDataSource(config);
        db = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("72").load().migrate();
        migratedAudit = seedAudit(12, 1);
        ordinaryAudit = seedAudit(1, 1);
        manualAudit = seedAudit(12, 2);
        flyway = Flyway.configure().dataSource(source).load();
        flyway.migrate(); flyway.validate();

        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
        var mybatis = new MybatisConfiguration(); mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(MediaUploadRecordMapper.class); factory.setConfiguration(mybatis);
        factory.setMapperLocations(new ClassPathResource("mapper/MediaUploadRecordMapper.xml"));
        uploads = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(MediaUploadRecordMapper.class);
    }

    private long seedAudit(int scene, int auditSource) {
        db.update("INSERT INTO content_audit_log(target_type,target_id,audit_source,final_result,created_at) VALUES(?,9,?,0,?)",
                scene, auditSource, LEGACY_TIME);
        return Objects.requireNonNull(db.queryForObject("SELECT MAX(id) FROM content_audit_log", Long.class));
    }

    @AfterAll void close() { if (source != null) source.close(); }

    @Test void migrationConvertsOnlyProvenLegacyImageWritesAndDoesNotShiftAgain() {
        assertEquals(LEGACY_TIME.plusHours(8), createdAt(migratedAudit));
        assertEquals(LEGACY_TIME, createdAt(ordinaryAudit));
        assertEquals(LEGACY_TIME, createdAt(manualAudit));
        flyway.migrate();
        assertEquals(LEGACY_TIME.plusHours(8), createdAt(migratedAudit));
        // New code already writes Beijing time and must not be shifted by a subsequent migrate.
        long newAudit = seedAudit(12, 1);
        flyway.migrate();
        assertEquals(LEGACY_TIME, createdAt(newAudit));
    }

    private LocalDateTime createdAt(long id) {
        return db.queryForObject("SELECT created_at FROM content_audit_log WHERE id=?", LocalDateTime.class, id);
    }

    @ParameterizedTest @ValueSource(strings={"UTC", "America/Los_Angeles", "Asia/Shanghai"})
    void connectionRoundTripsSqlClockMidnightExpiryAndUserRestrictionsIgnoreHostZone(String hostZone) {
        var original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(hostZone));
            assertEquals("+08:00", db.queryForObject("SELECT @@session.time_zone", String.class));
            var sqlNow = db.queryForObject("SELECT NOW(6)", LocalDateTime.class);
            assertTrue(Duration.between(sqlNow, LocalDateTime.now(BUSINESS_ZONE)).abs().compareTo(Duration.ofSeconds(5)) < 0);
            assertTrue(Duration.between(db.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class),
                    LocalDateTime.now(ZoneOffset.UTC)).abs().compareTo(Duration.ofSeconds(5)) < 0);
            db.execute("CREATE TABLE IF NOT EXISTS business_time_probe(id INT PRIMARY KEY, local_value DATETIME(6), instant_value TIMESTAMP(6))");
            db.update("DELETE FROM business_time_probe");
            var midnight = LocalDateTime.of(2026, 10, 29, 0, 0);
            var precise = midnight.plusNanos(123456000);
            db.update("INSERT INTO business_time_probe VALUES(1,?,?),(2,?,?),(3,?,?)",
                    midnight.minusNanos(1000), midnight.minusNanos(1000), precise, precise,
                    midnight.plusDays(1), midnight.plusDays(1));
            db.query("SELECT local_value,instant_value FROM business_time_probe WHERE id=2", rs -> {
                assertEquals(precise, rs.getObject("local_value", LocalDateTime.class));
                assertEquals(precise.atZone(BUSINESS_ZONE).toInstant(), rs.getTimestamp("instant_value").toInstant());
            });
            var start = Instant.parse("2026-10-28T16:00:00Z").atZone(BUSINESS_ZONE).toLocalDateTime();
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM business_time_probe WHERE local_value>=? AND local_value<?",
                    Integer.class, start, start.plusDays(1)));

            // The real scheduled-cleanup entry reads candidates using Beijing time under every JVM zone.
            db.update("DELETE FROM media_upload_record");
            var now = LocalDateTime.now(BUSINESS_ZONE);
            db.update("INSERT INTO media_upload_record(object_key,user_id,target_type,cleanup_after) VALUES('time/expired',9,1,?),('time/future',9,1,?)",
                    now.minusMinutes(1), now.plusMinutes(1));
            var cos = mock(CosService.class);
            when(cos.managedPublicUrls(anyString())).thenReturn(List.of());
            assertEquals(1, new MediaUploadRecordService(uploads, cos).cleanupExpiredBatch());
            verify(cos).deleteObject("time/expired");
            verify(cos, never()).deleteObject("time/future");
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM media_upload_record", Integer.class));

            var users = mock(UserProfileMapper.class);
            var row = new UserAuthRow(); row.setStatus(3);
            row.setBanExpireAt(midnight);
            when(users.selectCurrentLoginForShare(9L)).thenReturn(row);
            var login = new UserCurrentLoginAccess(users);
            when(users.selectCurrentUtcTime()).thenReturn(LocalDateTime.of(2026, 10, 28, 15, 59, 59));
            assertThrows(BusinessException.class, () -> login.require(9L));
            when(users.selectCurrentUtcTime()).thenReturn(LocalDateTime.of(2026, 10, 28, 16, 0));
            assertDoesNotThrow(() -> login.require(9L));
        } finally { TimeZone.setDefault(original); }
    }
}
