package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper;
import cn.jualn.miniapp.module.media.service.impl.MediaServiceImpl;
import cn.jualn.miniapp.third.cos.client.CosClient;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Dedicated V74 schema: historical formal fixtures and V30 repair compatibility. New registration is tested by FileFoundationDatabaseTest. */
@EnabledIfSystemProperty(named = "event.test.jdbcUrl", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/media_attachment_lifecycle_test")
class MediaAttachmentLifecycleDatabaseTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MediaService media; cn.jualn.miniapp.module.media.service.MediaQueries queries; cn.jualn.miniapp.module.media.service.MediaAccess access;
    private MediaUploadRecordService uploads;
    private MediaUploadRecordMapper records;
    private TransactionTemplate transaction;
    private CosClient cos;
    private final String key = "activity/9/synthetic-file.pdf";
    private final String url = "https://cdn.example/" + key;

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        media = context.getBean(MediaService.class); queries=context.getBean(cn.jualn.miniapp.module.media.service.MediaQueries.class); access=context.getBean(cn.jualn.miniapp.module.media.service.MediaAccess.class); uploads = context.getBean(MediaUploadRecordService.class);
        records = context.getBean(MediaUploadRecordMapper.class); cos = context.getBean(CosClient.class);
        transaction = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        // This schema is exclusively owned by the test invocation, never a shared or business database.
        jdbc.update("DELETE FROM event_attachment_link"); jdbc.update("DELETE FROM media_upload_record");
        jdbc.update("DELETE FROM media_attachment");
        cn.jualn.miniapp.security.AdminContextFixture.open(9);
        pending(key, TargetType.ACTIVITY);
    }
    @AfterEach void close() {cn.jualn.miniapp.security.AdminContextFixture.close(); if (context != null) context.close(); }
    private void pending(String value, TargetType type) {
        uploads.recordPending(9L, type, List.of(value), LocalDateTime.now().plusHours(1));
        jdbc.update("UPDATE media_upload_record SET declared_kind='VERIFIED' WHERE object_key=?",value);
    }
    private int status() { return jdbc.queryForObject("SELECT status FROM media_upload_record WHERE object_key=?", Integer.class, key); }
    private void expire() { jdbc.update("UPDATE media_upload_record SET cleanup_after=DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE object_key=?", key); }
    private long register() { return transaction.execute(state -> {
        var upload=uploads.lockAttachmentUpload(9L,key);
        var entity=cn.jualn.miniapp.module.media.entity.MediaAttachment.builder().kind("PDF").type(3).registered(true).registeredBy(9L)
            .objectKey(key).url(url).originalName("historical").sortOrder(0).retention("FORMAL").deleted(false).build();
        context.getBean(MediaAttachmentMapper.class).insert(entity);
        if(upload.getBoundAttachmentId()==null) jdbc.update("UPDATE media_upload_record SET status=1,bound_attachment_id=? WHERE id=?",entity.getId(),upload.getId());
        return entity.getId();
    }); }
    private long id() { return jdbc.queryForObject("SELECT id FROM media_upload_record WHERE object_key=?", Long.class, key); }

    @Test void registrationOwnsFileBeforeAnyBusinessSaveAndSupportsDuplicates() {
        long first = register(); long second = register(); assertNotEquals(first, second);
        assertEquals(1, status());
        assertEquals(first, jdbc.queryForObject("SELECT bound_attachment_id FROM media_upload_record WHERE object_key=?", Long.class, key));
        assertNull(jdbc.queryForObject("SELECT bound_target_id FROM media_upload_record WHERE object_key=?", Long.class, key));
        assertEquals(key, jdbc.queryForObject("SELECT object_key FROM media_attachment WHERE id=?", String.class, first));
        expire(); assertEquals(0, uploads.cleanupExpiredBatch()); verify(cos, never()).deleteObject(anyString());
    }

    @Test void businessFailureRollsBackLinksButRegistrationSurvivesAndCanBeReused() {
        long attachment = register();
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(state -> {
            media.replaceAttachmentLinks(TargetType.ACTIVITY, 101L, List.of(new AttachmentLinkBO(attachment, 0)));
            throw new IllegalStateException("synthetic save failure");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM event_attachment_link", Integer.class)); assertEquals(1, status());
        media.replaceAttachmentLinks(TargetType.ACTIVITY, 101L, List.of(new AttachmentLinkBO(attachment, 0)));
        media.replaceAttachmentLinks(TargetType.EXAM, 102L, List.of(new AttachmentLinkBO(attachment, 0)));
        assertEquals(attachment, queries.listAttachments(TargetType.EXAM, 102L).get(0).getId());
        media.replaceAttachmentLinks(TargetType.ACTIVITY, 101L, List.of());
        media.replaceAttachmentLinks(TargetType.EXAM, 102L, List.of());
        expire(); assertEquals(0, uploads.cleanupExpiredBatch()); assertEquals(1, status());
    }

    @Test void registrationAndBindingRollbackTogether() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(state -> { register(); throw new IllegalStateException(); }));
        assertEquals(0, status()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment", Integer.class));
        register(); assertEquals(1, status());
    }

    @Test void cleanupWinsAndRecoveredCleaningCannotBeRegistered() {
        expire(); assertEquals(1, records.claimCleanup(id(), LocalDateTime.now(), List.of(url)));
        assertThrows(BusinessException.class, this::register);
        jdbc.update("UPDATE media_upload_record SET status=0,cleanup_after=DATE_ADD(NOW(), INTERVAL 1 HOUR) WHERE object_key=?", key);
        assertThrows(BusinessException.class, this::register);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment", Integer.class));
    }

    @Test void registrationHoldsRowUntilCommitSoCleanupLoses() throws Exception {
        var inserted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        long recordId = id();
        try {
            var registration = executor.submit(() -> transaction.execute(state -> {
                long attachment = register(); inserted.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                return attachment;
            }));
            assertTrue(inserted.await(5, TimeUnit.SECONDS));
            // A future evaluation time makes this a real state competition rather than an expiry-only rejection.
            var claim = executor.submit(() -> records.claimCleanup(recordId, LocalDateTime.now().plusHours(2), List.of(url)));
            release.countDown(); registration.get(5, TimeUnit.SECONDS); assertEquals(0, claim.get(5, TimeUnit.SECONDS));
            assertEquals(1, status());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void legacyUrlOnlyRegistrationIsProtectedWithoutBlindBinding() {
        jdbc.update("INSERT INTO media_attachment(type,kind,registered,registered_by,url,original_name) VALUES(3,'PDF',1,9,?,'legacy')", url);
        expire(); assertEquals(0, uploads.cleanupExpiredBatch()); assertEquals(0, status());
        verify(cos, never()).deleteObject(anyString());
    }

    @Test void unrelatedLegacyPostRegisteredFlagDoesNotDisableOrphanCleanup() {
        jdbc.update("INSERT INTO media_attachment(target_type,target_id,type,kind,registered,url,original_name) VALUES(1,55,3,'PDF',1,?,'legacy')", "https://cdn.example/post/9/other.pdf");
        expire(); assertEquals(1, uploads.cleanupExpiredBatch()); verify(cos).deleteObject(key);
    }

    @Test void actualLegacyBusinessAttachmentProtectsTheSameObjectEvenWhenUploadStateIsInconsistent() {
        jdbc.update("INSERT INTO media_attachment(target_type,target_id,type,kind,registered,object_key,url,original_name) VALUES(1,55,3,'PDF',1,?,?,'legacy')", key, url);
        expire(); assertEquals(0, uploads.cleanupExpiredBatch()); assertEquals(0, status());
        verify(cos, never()).deleteObject(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci"})
    void mixedObjectKeyCollationsPreserveProtectionAndExactAccess(String attachmentCollation) {
        String uploadCollation = attachmentCollation.equals("utf8mb4_unicode_ci")
                ? "utf8mb4_0900_ai_ci" : "utf8mb4_unicode_ci";
        String originalAttachmentCollation = objectKeyCollation("media_attachment");
        String originalUploadCollation = objectKeyCollation("media_upload_record");
        try {
            objectKeyCollation("media_attachment", attachmentCollation, "NULL");
            objectKeyCollation("media_upload_record", uploadCollation, "NOT NULL");
            // A different URL forces protection to depend on the cross-table object key comparison.
            jdbc.update("INSERT INTO media_attachment(type,kind,registered,registered_by,object_key,url,original_name) "
                    + "VALUES(3,'PDF',1,9,?,?,'mixed-collation')", key, "https://legacy.example/other.pdf");
            long attachment = jdbc.queryForObject("SELECT MAX(id) FROM media_attachment", Long.class);
            expire();
            assertEquals(0, records.claimCleanup(id(), LocalDateTime.now(), List.of(url)));
            jdbc.update("UPDATE media_upload_record SET status=1,bound_target_id=101 WHERE id=?", id());
            assertEquals(0, records.requestDeletion(key, TargetType.ACTIVITY.getCode(), 101L,
                    LocalDateTime.now(), List.of(url)));

            jdbc.update("UPDATE media_upload_record SET bound_target_id=NULL,bound_attachment_id=? WHERE id=?", attachment, id());
            assertEquals(TargetType.ACTIVITY.getCode(), records.selectRegisteredPurpose(attachment, 9L));
            var attachments = context.getBean(MediaAttachmentMapper.class);
            assertEquals(1, attachments.selectBatchAccess(List.of(attachment), 9L).size());
            jdbc.update("UPDATE media_attachment SET object_key=? WHERE id=?", key.toUpperCase(java.util.Locale.ROOT), attachment);
            assertNull(records.selectRegisteredPurpose(attachment, 9L));
            assertTrue(attachments.selectBatchAccess(List.of(attachment), 9L).isEmpty());
            jdbc.update("UPDATE media_upload_record SET status=0,bound_attachment_id=NULL WHERE id=?", id());
            assertEquals(1, records.claimCleanup(id(), LocalDateTime.now(), List.of(url)));
        } finally {
            objectKeyCollation("media_attachment", originalAttachmentCollation, "NULL");
            objectKeyCollation("media_upload_record", originalUploadCollation, "NOT NULL");
        }
    }

    private String objectKeyCollation(String table) {
        return jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='object_key'", String.class, table);
    }

    private void objectKeyCollation(String table, String collation, String nullability) {
        // Identifiers and collation names come only from this fixture or its schema metadata.
        jdbc.execute("ALTER TABLE " + table + " MODIFY object_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE "
                + collation + " " + nullability + " COMMENT 'COS object key'");
    }

    @Test void deletionIntentIsDurableAndRollsBackWithBusinessState() {
        transaction.executeWithoutResult(state -> uploads.bindPending(9L, TargetType.ACTIVITY, 101L, List.of(key)));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(state -> {
            media.deleteObjectsAfterCommit(List.of(key), TargetType.ACTIVITY, 101L); throw new IllegalStateException();
        })); assertEquals(1, status());
        transaction.executeWithoutResult(state -> media.deleteObjectsAfterCommit(List.of(key), TargetType.ACTIVITY, 101L));
        assertEquals(0, status()); verify(cos, never()).deleteObject(anyString());
        // No afterCommit callback is needed: a fresh worker can recover the committed intent.
        expire(); // Simulate the next scheduled run, after DATETIME rounding at the persistence boundary.
        assertEquals(1, new MediaUploadRecordService(records, context.getBean(CosService.class)).cleanupExpiredBatch());
        verify(cos).deleteObject(key);
    }

    @Test void unkeyedRegistrationCannotBypassTheNewProtocol() {
        assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
            ()->media.registerAttachment("LINK","external","https://outside.example/file",9L));
        assertEquals(0,status());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment",Integer.class));
    }

    @Test void failedCleanupKeepsDeletionHistoryAndRetriesWithoutAllowingRegistration() {
        expire(); doThrow(new IllegalStateException("synthetic provider failure")).when(cos).deleteObject(key);
        assertEquals(0, uploads.cleanupExpiredBatch()); assertEquals(0, status());
        assertNotNull(jdbc.queryForObject("SELECT cleanup_started_at FROM media_upload_record WHERE object_key=?", LocalDateTime.class, key));
        assertThrows(BusinessException.class, this::register);
        reset(cos); expire(); assertEquals(1, uploads.cleanupExpiredBatch()); verify(cos).deleteObject(key);
    }

    @Test void ordinaryPostCommentAndProfileBindingsRollbackWithFailedBusinessSave() {
        for (TargetType type : List.of(TargetType.POST, TargetType.COMMENT, TargetType.USER)) {
            String object = type.name().toLowerCase() + "/9/file";
            pending(object, type);
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(state -> {
                uploads.bindPending(9L, type, 77L, List.of(object)); throw new IllegalStateException();
            }));
            assertEquals(0, jdbc.queryForObject("SELECT status FROM media_upload_record WHERE object_key=?", Integer.class, object));
            transaction.executeWithoutResult(state -> uploads.bindPending(9L, type, 77L, List.of(object)));
            assertEquals(1, jdbc.queryForObject("SELECT status FROM media_upload_record WHERE object_key=?", Integer.class, object));
        }
    }

    @Test void reviewedRepairPreservesDuplicateIdsAndIsRepeatable() {
        long a = legacy(); long b = legacy();
        repair(List.of(a, b), false);
        assertEquals(1, status());
        assertEquals(a, jdbc.queryForObject("SELECT bound_attachment_id FROM media_upload_record WHERE object_key=?", Long.class, key));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment WHERE object_key=?", Integer.class, key));
        repair(List.of(a, b), false); assertEquals(1, status());
    }

    @Test void repairInvalidBatchAndDefaultRollbackDoNotPartiallyBind() {
        long a = legacy(); repair(List.of(a), true); assertEquals(0, status());
        repair(List.of(a, 99999999L), false); assertEquals(0, status());
        assertNull(jdbc.queryForObject("SELECT object_key FROM media_attachment WHERE id=?", String.class, a));
    }

    private long legacy() {
        jdbc.update("INSERT INTO media_attachment(type,kind,registered,registered_by,url,original_name) VALUES(3,'PDF',1,9,?,'legacy')", url);
        return jdbc.queryForObject("SELECT MAX(id) FROM media_attachment", Long.class);
    }
    private void repair(List<Long> attachmentIds, boolean rollback) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMPORARY TABLE media_attachment_verified_mapping(attachment_id BIGINT UNSIGNED PRIMARY KEY,object_key VARCHAR(512) NOT NULL,uploader_id BIGINT UNSIGNED NOT NULL,cos_exists TINYINT NOT NULL,evidence_ref VARCHAR(200) NOT NULL) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
                try (var insert = connection.prepareStatement("INSERT INTO media_attachment_verified_mapping VALUES(?,?,9,1,'synthetic-reviewed-evidence')")) {
                    for (long attachment : attachmentIds) { insert.setLong(1, attachment); insert.setString(2, key); insert.executeUpdate(); }
                }
                String script;
                try (var stream = new ClassPathResource("db/validation/media_attachment_lifecycle_repair.sql").getInputStream()) {
                    script = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                if (!rollback) script = script.replace("ROLLBACK;", "COMMIT;");
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ByteArrayResource(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
            return null;
        });
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean cn.jualn.miniapp.common.mapper.EnumConverter enums() { return new cn.jualn.miniapp.common.mapper.EnumConverter(); }
        @Bean MediaConverter converter() { return Mappers.getMapper(MediaConverter.class); }
        @Bean DataSource dataSource() { return new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", ""); }
        @Bean DataSourceTransactionManager transactions(DataSource data) { return new DataSourceTransactionManager(data); }
        @Bean SqlSessionFactory sqlSessions(DataSource data) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(data);
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true); factory.setConfiguration(configuration);
            var global = new GlobalConfig(); global.setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler());
            factory.setGlobalConfig(global);
            factory.setMapperLocations(new ClassPathResource("mapper/MediaUploadRecordMapper.xml"), new ClassPathResource("mapper/MediaAttachmentMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<MediaUploadRecordMapper> records(SqlSessionFactory sessions) {
            var factory = new MapperFactoryBean<>(MediaUploadRecordMapper.class); factory.setSqlSessionFactory(sessions); return factory;
        }
        @Bean MapperFactoryBean<MediaAttachmentMapper> attachments(SqlSessionFactory sessions) {
            var factory = new MapperFactoryBean<>(MediaAttachmentMapper.class); factory.setSqlSessionFactory(sessions); return factory;
        }
        @Bean CosClient cos() { var client = mock(CosClient.class); when(client.buildPublicUrl(anyString())).thenAnswer(c -> "https://cdn.example/" + c.getArgument(0)); return client; }
        @Bean CosService cosService(CosClient cos) { return new CosService(cos, new CosProperties("unused", "unused", "campus-123", "ap-test", "https://cdn.example", 600, "cdn.example"), new ObjectMapper()); }
        @Bean MediaUploadRecordService uploads(MediaUploadRecordMapper records, CosService cos) { return new MediaUploadRecordService(records, cos); }
        @Bean MediaService media(MediaAttachmentMapper attachments, CosService cos, MediaUploadRecordService uploads, MediaConverter converter) {
            return new MediaServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), converter, attachments, mock(SubjectExistenceService.class), cos, uploads, org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.ArticleMediaService.class));
        }
  @Bean cn.jualn.miniapp.module.media.service.MediaQueries mediaQueries(MediaConverter c,MediaAttachmentMapper a,
      org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.ArticleMediaService> article) {
    return new cn.jualn.miniapp.module.media.service.MediaQueriesService(c,a,
      org.mockito.Mockito.mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class),
      article.getIfAvailable(()->org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.ArticleMediaService.class)));
  }
  @Bean cn.jualn.miniapp.module.media.service.MediaAccess mediaAccess(MediaAttachmentMapper a,
      cn.jualn.miniapp.module.media.service.MediaQueries q,MediaUploadRecordService uploads,
      org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.authorization.service.CurrentAuthorization> auth,
      org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.ArticleMediaService> article) {
    return new cn.jualn.miniapp.module.media.service.MediaAccessService(auth.getIfAvailable(()->org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class)),a,q,uploads,
      article.getIfAvailable(()->org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.ArticleMediaService.class)));
  }

    }
}
