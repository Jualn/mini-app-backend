package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.audit.bo.AuditMediaCheckBO;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO.Declaration;
import cn.jualn.miniapp.module.media.mapper.PointMaterialSafetyMapper;
import cn.jualn.miniapp.module.media.mapper.PointMaterialMapper;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Reuses the explicitly named owned schema. Real MySQL/Flyway/MyBatis/transactions; WeChat/COS are doubles. */
@EnabledIfSystemProperty(named="managed.person.test.jdbcUrl", matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/independent_person_test_[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WechatPointMaterialSafetyDatabaseTest {
    JdbcTemplate db;
    WechatPointMaterialSafety safety;
    PointMaterialService materials;
    AuditService audits = mock(AuditService.class);
    UserWechatIdentityService users = mock(UserWechatIdentityService.class);
    COSClient cos = mock(COSClient.class);
    ContentAuditLogMapper logs;
    long owner;
    String retained;
    static final Declaration IMAGE = new Declaration("proof.png", "IMAGE", "image/png", 67);

    @BeforeAll void start() throws Exception {
        String url = System.getProperty("managed.person.test.jdbcUrl");
        DataSource source = new DriverManagerDataSource(url + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true",
                Objects.requireNonNull(System.getenv("MANAGED_PERSON_TEST_DB_USER")),
                Objects.requireNonNull(System.getenv("MANAGED_PERSON_TEST_DB_PASSWORD")));
        db = new JdbcTemplate(source);
        assertEquals(System.getProperty("managed.person.test.serverUuid"), db.queryForObject("SELECT @@server_uuid", String.class));
        assertEquals(url.substring(url.lastIndexOf('/') + 1), db.queryForObject("SELECT DATABASE()", String.class));
        var flyway = Flyway.configure().dataSource(source).load();
        flyway.migrate();
        flyway.validate();
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PointMaterialSafetyMapper.class);
        config.addMapper(PointMaterialMapper.class);
        config.addMapper(ContentAuditLogMapper.class);
        factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/PointMaterialMapper.xml"));
        var sessions = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        logs = sessions.getMapper(ContentAuditLogMapper.class);
        safety = new WechatPointMaterialSafety(sessions.getMapper(PointMaterialSafetyMapper.class),
                new cn.jualn.miniapp.module.audit.service.AuditLogService(logs), audits, users, cos,
                new DataSourceTransactionManager(source), "synthetic-private-1250000000", 0);
        var provider = mock(PointMaterialProvider.class);
        doAnswer(invocation -> {
            safety.requireSafe(invocation.getArgument(2), invocation.getArgument(1), null, invocation.getArgument(3));
            return null;
        }).when(provider).retainAndInspect(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        materials = new PointMaterialService(sessions.getMapper(PointMaterialMapper.class), provider,
                new DataSourceTransactionManager(source), Clock.systemUTC());
        var beans = new StaticListableBeanFactory();
        beans.addBean("admission", mock(PointMaterialAdmission.class));
        materials.admission(beans.getBeanProvider(PointMaterialAdmission.class));
    }

    @BeforeEach void fixture() throws Exception {
        reset(audits, users, cos);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO user_profile(openid) VALUES(?)", "synthetic-points-" + id);
        owner = Objects.requireNonNull(db.queryForObject("SELECT id FROM user_profile WHERE openid=?", Long.class, "synthetic-points-" + id));
        retained = "points/retained/" + id;
        db.update("INSERT INTO media_point_upload(upload_id,owner_user_id,request_key,fingerprint,expires_at) VALUES(?,?,? ,?,UTC_TIMESTAMP()+INTERVAL 15 MINUTE)", id, owner, id, "a".repeat(64));
        db.update("INSERT INTO media_point_file(file_id,upload_id,file_index,owner_user_id,file_name,kind,content_type,size_bytes,source_key,retained_key,state,upload_expires_at,cleanup_after) VALUES(?,?,0,?,'proof.png','IMAGE','image/png',67,?,?,'UPLOADING',UTC_TIMESTAMP()+INTERVAL 15 MINUTE,UTC_TIMESTAMP()+INTERVAL 1 DAY)", id, id, owner, "points/upload/" + id, retained);
        when(users.getMiniOpenid(owner)).thenReturn("synthetic-uploader-openid");
        when(cos.generatePresignedUrl(eq("synthetic-private-1250000000"), eq(retained), any(java.util.Date.class), eq(HttpMethodName.GET)))
                .thenReturn(URI.create("https://example.invalid/transient-signed-image").toURL());
        doAnswer(invocation -> {
            AuditMediaCheckBO request = invocation.getArgument(0);
            assertEquals("synthetic-uploader-openid", request.getOpenid());
            assertEquals(owner, request.getTargetId());
            assertEquals(12, request.getAuditScene().getCode());
            db.update("UPDATE content_audit_log SET wx_trace_id=? WHERE id=?", "synthetic-trace-" + request.getAuditLogId(), request.getAuditLogId());
            return null;
        }).when(audits).doMediaCheck(any());
    }

    Long auditId() { return db.queryForObject("SELECT safety_audit_log_id FROM media_point_file WHERE retained_key=?", Long.class, retained); }
    void check() { safety.requireSafe(owner, retained, null, IMAGE); }

    @ParameterizedTest @ValueSource(strings={"UTC", "America/Los_Angeles", "Asia/Shanghai"})
    @ResourceLock("default-time-zone")
    void auditBusinessTimeAndUtcFileExpiryRemainDistinctUnderOtherHostZones(String hostZone) {
        var original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(hostZone));
            var expiry = db.queryForObject("SELECT upload_expires_at FROM media_point_file WHERE retained_key=?",
                    java.time.LocalDateTime.class, retained);
            var before = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusSeconds(1);
            assertThrows(ContractProblemException.class, this::check);
            var created = logs.selectById(auditId()).getCreatedAt();
            // This legacy audit column has second precision; MySQL may round subsecond input upward.
            org.junit.jupiter.api.Assertions.assertTrue(!created.isBefore(before)
                    && !created.isAfter(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusSeconds(1)),
                    () -> "Persisted audit time " + created + "; Beijing lower bound " + before);
            assertEquals(expiry, db.queryForObject("SELECT upload_expires_at FROM media_point_file WHERE retained_key=?",
                    java.time.LocalDateTime.class, retained));
        } finally { java.util.TimeZone.setDefault(original); }
    }

    @Test void pendingThenCallbackPassReusesOneCheckWithoutRegisteringFile() {
        assertEquals(503, assertThrows(ContractProblemException.class, this::check).getStatus().value());
        Long id = auditId();
        assertNotNull(id);
        assertThrows(ContractProblemException.class, this::check);
        db.update("UPDATE content_audit_log SET final_result=1 WHERE id=?", id);
        check();
        verify(audits, times(1)).doMediaCheck(any());
        assertEquals("UPLOADING", db.queryForObject("SELECT state FROM media_point_file WHERE retained_key=?", String.class, retained));
    }

    @Test void rejectionAndWrongOwnerCannotAuthorizeRegistration() {
        assertThrows(ContractProblemException.class, this::check);
        db.update("UPDATE content_audit_log SET final_result=2 WHERE id=?", auditId());
        assertEquals("/problems/points-file-unavailable", assertThrows(ContractProblemException.class, this::check).getType());
        when(users.getMiniOpenid(owner + 1)).thenReturn("synthetic-other-openid");
        assertThrows(ContractProblemException.class, () -> safety.requireSafe(owner + 1, retained, null, IMAGE));
        verify(audits, times(1)).doMediaCheck(any());
    }

    @Test void unknownRemoteSubmissionStaysClaimedAcrossRetries() {
        doAnswer(invocation -> { throw new IllegalStateException("synthetic network outcome unknown"); }).when(audits).doMediaCheck(any());
        assertThrows(ContractProblemException.class, this::check);
        Long original = auditId();
        assertNotNull(original);
        assertThrows(ContractProblemException.class, this::check);
        assertEquals(original, auditId());
        verify(audits, times(1)).doMediaCheck(any());
    }

    @Test void concurrentRegistrationSubmitsOneExternalCheck() throws Exception {
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) results.add(workers.submit(() -> {
                start.await();
                try { check(); return 200; } catch (ContractProblemException problem) { return problem.getStatus().value(); }
            }));
            start.countDown();
            for (var result : results) assertEquals(503, result.get(10, TimeUnit.SECONDS));
            verify(audits, times(1)).doMediaCheck(any());
        } finally { workers.shutdownNow(); }
    }

    @Test void documentsAreExplicitlyExemptAndUnsupportedKindsFailClosed() {
        safety.requireSafe(owner, retained, null, new Declaration("proof.pdf", "PDF", "application/pdf", 67));
        safety.requireSafe(owner, retained, null, new Declaration("proof.docx", "WORD", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 67));
        safety.requireSafe(owner, retained, null, new Declaration("clip.mp4", "VIDEO", "video/mp4", 67));
        assertThrows(ContractProblemException.class, () -> safety.requireSafe(owner, retained, null, new Declaration("link", "LINK", "text/plain", 67)));
        verifyNoInteractions(audits, users, cos);
        assertEquals(null, auditId());
    }

    @Test void imageRegistrationWaitsForPassAndThenReplaysTheOriginalReceipt() {
        String upload = retained.substring("points/retained/".length());
        String key = UUID.randomUUID().toString();
        assertEquals(503, assertThrows(ContractProblemException.class, () -> materials.register(owner, upload, 0, key)).getStatus().value());
        assertEquals("UPLOADING", db.queryForObject("SELECT state FROM media_point_file WHERE retained_key=?", String.class, retained));
        db.update("UPDATE content_audit_log SET final_result=1 WHERE id=?", auditId());
        var file = materials.register(owner, upload, 0, key);
        assertEquals(upload, file.fileId());
        assertEquals(file, materials.register(owner, upload, 0, key));
        assertEquals("REGISTERED", db.queryForObject("SELECT state FROM media_point_file WHERE retained_key=?", String.class, retained));
        verify(audits, times(1)).doMediaCheck(any());
    }

    @Test void documentRegistrationUsesTheExistingPrivateFileLifecycleWithoutModeration() {
        String upload = retained.substring("points/retained/".length());
        db.update("UPDATE media_point_file SET kind='PDF',content_type='application/pdf',file_name='proof.pdf' WHERE retained_key=?", retained);
        var file = materials.register(owner, upload, 0, UUID.randomUUID().toString());
        assertEquals("PDF", file.kind());
        assertEquals("REGISTERED", db.queryForObject("SELECT state FROM media_point_file WHERE retained_key=?", String.class, retained));
        assertEquals(null, auditId());
        verifyNoInteractions(audits, users, cos);
    }

    @Test void videoRegistrationPreservesOwnershipAndReceiptsWithoutContentModeration() {
        String upload = retained.substring("points/retained/".length());
        db.update("UPDATE media_point_file SET kind='VIDEO',content_type='video/mp4',file_name='proof.mp4' WHERE retained_key=?", retained);
        String key = UUID.randomUUID().toString();
        assertEquals(404, assertThrows(ContractProblemException.class, () -> materials.register(owner + 1, upload, 0, key)).getStatus().value());
        var file = materials.register(owner, upload, 0, key);
        assertEquals("VIDEO", file.kind());
        assertEquals(file, materials.register(owner, upload, 0, key));
        assertEquals(null, auditId());
        verifyNoInteractions(audits, users, cos);
    }
}
