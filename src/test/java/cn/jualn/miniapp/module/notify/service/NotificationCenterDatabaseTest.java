package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.converter.NotifyConverter;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.service.impl.NotificationCenterServiceImpl;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "event.test.jdbcUrl", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/notification_contract_.*")
class NotificationCenterDatabaseTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private NotificationMapper mapper;
    private NotificationInboxService inbox;
    private NotificationCenterService center;
    private NotificationStreamCursorCodec cursors;
    private TransactionTemplate tx;
    private final long user = 900_000_001L;
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        mapper = context.getBean(NotificationMapper.class);
        inbox = context.getBean(NotificationInboxService.class);
        center = context.getBean(NotificationCenterService.class);
        cursors = context.getBean(NotificationStreamCursorCodec.class);
        tx = context.getBean(TransactionTemplate.class);
        clear();
        UserContext.setUserId(user);
    }
    @AfterEach void close() {
        clear(); UserContext.clear(); context.close();
    }
    private void clear() {
        jdbc.update("DELETE FROM notification WHERE user_id IN (?,?)", user, user + 1);
        jdbc.update("DELETE FROM notification_inbox_counter WHERE user_id IN (?,?)", user, user + 1);
    }
    private Notification create(long owner, int type, boolean visible) {
        return tx.execute(status -> {
            jdbc.update("INSERT INTO notification(user_id,type,title,content,inbox_generation) VALUES (?,?,'frozen title','frozen body','CANONICAL')", owner, type);
            long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            Notification row = mapper.selectOwned(id, owner);
            if (visible) { inbox.enter(row); }
            return row;
        });
    }
    private NotificationCenterBO.Query query(String category, int size) {
        return new NotificationCenterBO.Query(null, size, null, category, null);
    }
    @Test void canonicalReadLegacyProjectionAndIdempotencyShareSameRow() {
        Notification row = create(user, 1, true);
        assertNull(center.get(row.getId().toString()).readAt());
        assertEquals(1, center.batchRead(List.of(row.getId().toString())).changedCount());
        String readAt = center.get(row.getId().toString()).readAt();
        assertNotNull(readAt);
        assertEquals(0, center.batchRead(List.of(row.getId().toString())).changedCount());
        assertEquals(readAt, center.get(row.getId().toString()).readAt());
        var old = Mappers.getMapper(NotifyConverter.class).toBO(mapper.selectOwned(row.getId(), user));
        assertTrue(old.getIsRead());
        assertEquals(0, mapper.countCanonicalUnread(user));
        assertEquals(row.getId(), mapper.selectLegacyInbox(user, null, 1, 1, 20).get(0).getId());
        assertEquals(row.getId(), mapper.selectCanonicalInbox(user, null, null, null, true, 20).get(0).getId());
        context.getBean(NotifyService.class).markAsRead(row.getId());
        assertEquals(readAt, center.get(row.getId().toString()).readAt());
    }
    @Test void externalOnlyRecoveryAndReadDoNotContributeToBadgeOrStream() {
        Notification visible = create(user, 8, true);
        Notification external = create(user, 8, false);
        assertNull(mapper.selectOwned(external.getId(), user).getInboxSeq());
        assertEquals(1, center.summary(null).unreadCount());
        assertEquals(external.getId().toString(), center.get(external.getId().toString()).id());
        var result = center.batchRead(List.of(external.getId().toString()));
        assertEquals(1, result.changedCount()); assertEquals(1, result.unreadCount());
        assertEquals(visible.getId().toString(), center.list(query(null, 20)).items().get(0).id());
        UserContext.setUserId(user + 1);
        assertThrows(ContractProblemException.class, () -> center.get(external.getId().toString()));
    }
    @Test void mixedOwnershipBatchHasZeroWritesAndDuplicateIdsCountOnce() {
        Notification owned = create(user, 1, true);
        Notification other = create(user + 1, 1, true);
        assertThrows(ContractProblemException.class, () -> center.batchRead(List.of(owned.getId().toString(), other.getId().toString())));
        assertNull(mapper.selectOwned(owned.getId(), user).getReadAt());
        Notification owned2 = create(user, 1, true);
        assertEquals(2, center.batchRead(List.of(owned.getId().toString(), owned2.getId().toString(), owned.getId().toString())).changedCount());
        assertEquals(0, center.batchRead(List.of(owned.getId().toString(), owned2.getId().toString())).changedCount());
    }
    @Test void filteredListUsesGlobalHeadAndSummaryBaselineDoesNotReplayUnread() {
        create(user, 1, true); create(user, 8, true); create(user, 7, true);
        var page = center.list(query("INTERACTION", 20));
        assertEquals(1, page.items().size()); assertEquals(3, cursors.decodeHead(user, page.headCursor()));
        var baseline = center.summary(null);
        assertEquals(3, baseline.unreadCount()); assertEquals(0, baseline.newCount()); assertNull(baseline.latestNewNotification());
        Notification newest = create(user, 8, true);
        center.batchRead(List.of(newest.getId().toString()));
        var incremental = center.summary(baseline.headCursor());
        assertEquals(1, incremental.newCount()); assertEquals(newest.getId().toString(), incremental.latestNewNotification().id());
        assertEquals(3, incremental.unreadCount());
    }
    @Test void throughHeadCannotReadFutureOrExternalRowsEvenWithOlderCreatedAt() {
        Notification old = create(user, 8, true);
        String boundary = center.list(query("SYSTEM", 20)).headCursor();
        Notification late = create(user, 1, true);
        jdbc.update("UPDATE notification SET created_at='2000-01-01 00:00:00' WHERE id=?", late.getId());
        Notification external = create(user, 8, false);
        assertEquals(1, center.readThrough(boundary).changedCount());
        assertEquals(0, center.readThrough(boundary).changedCount());
        assertNotNull(mapper.selectOwned(old.getId(), user).getReadAt());
        assertNull(mapper.selectOwned(late.getId(), user).getReadAt());
        assertNull(mapper.selectOwned(external.getId(), user).getReadAt());
        assertEquals(late.getId().toString(), center.list(query(null, 20)).items().get(0).id());
        var page = center.list(query(null, 1));
        assertTrue(page.hasMore());
        var second = center.list(new NotificationCenterBO.Query(page.nextCursor(), 1, null, null, null));
        assertEquals(old.getId().toString(), second.items().get(0).id()); assertFalse(second.hasMore());
        assertThrows(ContractProblemException.class, () -> center.readThrough(page.nextCursor()));
    }
    @Test void duplicateVisibilityEntryDoesNotReallocateSequenceAndRollbackDoesNotAdvanceHead() {
        Notification row = create(user, 8, true);
        tx.executeWithoutResult(status -> inbox.enter(row));
        assertEquals(1, mapper.selectInboxHead(user));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            create(user, 8, true); throw new IllegalStateException("rollback");
        }));
        assertEquals(1, mapper.selectInboxHead(user));
    }
    @Test void sequenceAllocationSerializesSameUserCommitButNotOtherUsers() throws Exception {
        create(user, 8, true);
        CountDownLatch allocated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> tx.execute(status -> {
                Notification row = create(user, 8, true); allocated.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return row;
            }));
            assertTrue(allocated.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> create(user, 1, true));
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            assertEquals(1, mapper.selectInboxHead(user));
            Notification other = create(user + 1, 8, true);
            assertEquals(1, other.getInboxSeq());
            String oldHead = center.summary(null).headCursor();
            release.countDown();
            assertEquals(2, first.get(5, TimeUnit.SECONDS).getInboxSeq());
            assertEquals(3, second.get(5, TimeUnit.SECONDS).getInboxSeq());
            assertEquals(1, center.readThrough(oldHead).changedCount());
            assertEquals(2, center.summary(oldHead).newCount());
            assertEquals(2, center.summary(null).unreadCount());
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }
    @Test void historicalSnapshotIsReadableWithoutCurrentTargetAndNeverReconstructsBefore() throws Exception {
        Notification row = create(user, 13, true);
        var snapshot = new NotificationCenterBO.Snapshot(new NotificationCenterBO.Presentation("时间改变", null, "创建时标题", null,
                List.of(new NotificationCenterBO.Change("开始时间", "15:00", "17:00"))), null,
                new NotificationCenterBO.Subject("ACTIVITY", "deleted-activity"),
                new NotificationCenterBO.Target("ACTIVITY_DETAIL", null, null, "deleted-activity", null));
        ObjectMapper json = context.getBean(ObjectMapper.class);
        jdbc.update("UPDATE notification SET content_payload=?,content_schema_version=1 WHERE id=?", json.writeValueAsString(snapshot), row.getId());
        var result = center.get(row.getId().toString());
        assertEquals("15:00", result.presentation().changes().get(0).before());
        assertEquals("17:00", result.presentation().changes().get(0).after());
        assertEquals("deleted-activity", result.target().activityId());
    }

    @Test void oldCacheRebuildCannotOverwriteReadTruthAndRedisLossRebuildsFromMysql() {
        var cache = context.getBean(RedisService.class);
        java.util.Map<String, Long> values = new java.util.HashMap<>();
        org.mockito.Mockito.when(cache.getLong(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> values.get(call.getArgument(0)));
        org.mockito.Mockito.when(cache.setCacheProjection(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> { values.put(call.getArgument(0), call.getArgument(1)); return true; });
        var notifications = context.getBean(NotifyService.class);
        Notification row = create(user, 8, true);
        assertEquals(1, notifications.countCurrentUnreadNotifications());
        String oldKey = values.keySet().iterator().next();
        center.batchRead(List.of(row.getId().toString()));
        values.put(oldKey, 1L); // Delayed pre-read projection write after invalidation.
        assertEquals(0, notifications.countCurrentUnreadNotifications());
        values.clear();
        assertEquals(0, notifications.countCurrentUnreadNotifications());
        assertEquals(1, values.size());
    }

    @Test void reminderPlanRebuildAndOffsetChangeKeepOneNotificationButNewAnchorCreatesAnother() {
        var plans = context.getBean(cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper.class);
        var enrollment = context.getBean(cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService.class);
        var plan = cn.jualn.miniapp.module.notify.entity.NotifyPlan.builder().id(51L).sourceType(1).sourceId(42L)
                .notifyType(8).timelineId(17L).generation(1L).recipientScope("SUBSCRIBERS").scope(0).status(3)
                .title("synthetic reminder").content("synthetic content")
                .subjectStartsAt(java.time.LocalDateTime.of(2030, 1, 1, 15, 0))
                .sendAt(java.time.LocalDateTime.of(2030, 1, 1, 14, 0)).build();
        org.mockito.Mockito.when(plans.selectById(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> plan);
        org.mockito.Mockito.when(enrollment.listEnrolledUserIds(42L, 0L, 100)).thenReturn(List.of(user));
        var notifications = context.getBean(NotifyService.class);
        notifications.broadcastPlanFanOut(51L);
        String firstId = center.list(query(null, 20)).items().get(0).id();
        plan.setId(52L); plan.setSendAt(plan.getSendAt().minusMinutes(30));
        notifications.broadcastPlanFanOut(52L);
        assertEquals(1, center.list(query(null, 20)).items().size());
        assertEquals(firstId, center.list(query(null, 20)).items().get(0).id());
        assertEquals(1, mapper.selectInboxHead(user));
        jdbc.update("UPDATE notification SET source_key=? WHERE id=?", "plan:50:user:" + user, Long.parseLong(firstId));
        plan.setId(53L);
        notifications.broadcastPlanFanOut(53L);
        assertEquals(1, center.list(query(null, 20)).items().size()); // Reliable pre-switch frozen anchor is deduped too.
        plan.setId(54L); plan.setSubjectStartsAt(plan.getSubjectStartsAt().plusHours(2));
        notifications.broadcastPlanFanOut(54L);
        assertEquals(2, center.list(query(null, 20)).items().size());
        assertEquals(2, mapper.selectInboxHead(user));
    }

    @Test void explicitPresentationAndActorPersistAndListRecoveryUseTheSameFrozenSnapshot() {
        var snapshot = new NotificationCenterBO.Snapshot(
                new NotificationCenterBO.Presentation("reply", "new reply", "original context", null, null, "old post title", "original comment"),
                new NotificationCenterBO.Actor("7", "old actor", "https://example.com/a.png"),
                new NotificationCenterBO.Subject("COMMENT", "18"), NotificationSnapshotProjection.post("42", "19"));
        context.getBean(NotifyService.class).processNotificationPayload(cn.jualn.miniapp.module.notify.payload.NotifyPayload.builder()
                .receiverId(user).senderId(7L).type(cn.jualn.miniapp.common.enums.NotifyType.COMMENT_REPLIED)
                .title("reply").content("new reply").sourceKey("synthetic:snapshot:user:" + user).snapshot(snapshot).build());
        var item = center.list(query(null, 20)).items().get(0);
        assertEquals(item, center.get(item.id()));
        assertEquals("old post title", item.presentation().subjectTitle());
        assertEquals("original comment", item.presentation().quote());
        assertEquals("new reply", item.presentation().body()); assertEquals("original context", item.presentation().context());
        assertEquals("https://example.com/a.png", item.actor().avatarUrl());
        var stored = jdbc.queryForObject("SELECT content_payload FROM notification WHERE id=?", String.class, item.id());
        assertTrue(stored.contains("subjectTitle")); assertTrue(stored.contains("quote"));
        Notification historical = create(user, 13, true);
        var old = center.get(historical.getId().toString());
        assertEquals("SYSTEM", old.type()); assertEquals("ACTIVITY", old.category());
        assertNull(old.presentation().subjectTitle()); assertNull(old.presentation().quote()); assertNull(old.actor());
    }

    @Test void ambiguousHistoricalTypesNeverGuessNewTypeOrTargetFromText() {
        Notification row = create(user, 99, true);
        jdbc.update("UPDATE notification SET title='帖子评论活动取消',target_type=6,target_id=42 WHERE id=?", row.getId());
        var item = center.get(row.getId().toString());
        assertEquals("SYSTEM", item.type()); assertEquals("SYSTEM", item.category());
        assertNull(item.target()); assertEquals("帖子评论活动取消", item.presentation().title());
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class TestConfig {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", ""); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TransactionTemplate transactions(DataSourceTransactionManager tm) { return new TransactionTemplate(tm); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            var global = new com.baomidou.mybatisplus.core.config.GlobalConfig();
            global.setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler());
            factory.setGlobalConfig(global);
            factory.setMapperLocations(new ClassPathResource("mapper/NotificationMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<NotificationMapper> mapper(SqlSessionFactory sf) {
            var factory = new MapperFactoryBean<>(NotificationMapper.class); factory.setSqlSessionFactory(sf); return factory;
        }
        @Bean NotificationInboxService inbox(NotificationMapper mapper) { return new NotificationInboxService(mapper); }
        @Bean NotificationStreamCursorCodec cursors() { return new NotificationStreamCursorCodec("notification-integration-test-secret"); }
        @Bean ObjectMapper objectMapper() { return new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder()); }
        @Bean RedisService redisService() { return mock(RedisService.class); }
        @Bean cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper plans() { return mock(cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper.class); }
        @Bean cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService enrollment() { return mock(cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService.class); }
        @Bean NotifyService notifications(NotificationMapper mapper, RedisService redis, TransactionTemplate tx, NotificationInboxService inbox,
                cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper plans,
                cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService enrollment, ObjectMapper json) {
            var validator = mock(cn.jualn.miniapp.infrastructure.validator.TargetValidator.class);
            org.mockito.Mockito.when(validator.exists(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
            var status = mock(cn.jualn.miniapp.module.notify.reminder.ReminderSubjectStatus.class);
            org.mockito.Mockito.when(status.sourceType()).thenReturn(1);
            org.mockito.Mockito.when(status.isCurrentAndEligible(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
            var settings = mock(cn.jualn.miniapp.module.setting.service.SettingService.class);
            org.mockito.Mockito.when(settings.getSettingByUserId(org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(cn.jualn.miniapp.module.setting.bo.UserSettingBO.builder().notifyReply(false).build());
            return new cn.jualn.miniapp.module.notify.service.impl.NotifyServiceImpl(mapper,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), inbox, plans, enrollment,
                    mock(cn.jualn.miniapp.module.exam.service.ExamSubscriptionService.class),
                    mock(cn.jualn.miniapp.module.user.service.UserService.class), settings,
                    redis, validator, Mappers.getMapper(NotifyConverter.class), mock(cn.jualn.miniapp.infrastructure.async.job.JobService.class), tx,
                    mock(NotificationDeliveryService.class), json, List.of(status), mock(cn.jualn.miniapp.module.notify.reminder.ReminderReconcileService.class),
                    mock(cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper.class), mock(cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceOwnerMapper.class),
                    mock(cn.jualn.miniapp.module.user.mapper.UserProfileMapper.class));
        }
        @Bean NotificationSnapshotProjection projection(ObjectMapper json) { return new NotificationSnapshotProjection(json,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()); }
        @Bean NotificationCenterService center(NotificationMapper mapper, NotificationStreamCursorCodec cursors,
                NotificationSnapshotProjection projection, TransactionTemplate tx, RedisService redis) {
            return new NotificationCenterServiceImpl(mapper, cursors, projection, redis, tx,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }
    }
}
