package cn.jualn.miniapp.infrastructure.async;

import cn.jualn.miniapp.module.notify.mapper.NotificationDeliveryMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "async.it", matches = "true")
class AsyncInfrastructureIntegrationTest {

    @Test
    @EnabledIfSystemProperty(named = "mysql.it", matches = "true")
    void mysqlMigrationTransactionsClaimsAndPlansUseRealEngine() throws Exception {
        String host = System.getProperty("mysql.it.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("mysql.it.port", "3306"));
        String user = System.getProperty("mysql.it.user", "root");
        String password = System.getProperty("mysql.it.password", "");
        String schema = "jualn_async_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String serverUrl = "jdbc:mysql://" + host + ":" + port
                + "/?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        String schemaUrl = "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection server = DriverManager.getConnection(serverUrl, user, password)) {
            server.createStatement().execute("CREATE DATABASE `" + schema + "`");
            try (Connection connection = DriverManager.getConnection(schemaUrl, user, password)) {
                connection.createStatement().execute("CREATE TABLE notification (id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT, user_id BIGINT UNSIGNED NOT NULL, type TINYINT NOT NULL, title VARCHAR(128) NOT NULL, content VARCHAR(512) NOT NULL, target_type TINYINT NULL, target_id BIGINT UNSIGNED NULL, sender_id BIGINT UNSIGNED NULL, is_read TINYINT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
                connection.createStatement().execute("CREATE TABLE notify_plan (id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT, source_type TINYINT NOT NULL, source_id BIGINT UNSIGNED NULL, notify_type TINYINT NOT NULL, title VARCHAR(128) NOT NULL, content VARCHAR(512) NOT NULL, scope TINYINT NOT NULL DEFAULT 0, scene VARCHAR(64) NULL, send_at DATETIME NOT NULL, status TINYINT NOT NULL, created_by BIGINT UNSIGNED NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
                connection.createStatement().execute("INSERT INTO notify_plan(source_type,source_id,notify_type,title,content,scope,send_at,status) VALUES (1,10,4,'t','c',0,NOW(),0),(1,10,4,'t','c',0,NOW(),1)");
                applySql(connection, Path.of("src/main/resources/db/migration/V17__add_async_processing_foundation.sql"));
                applySql(connection, Path.of("src/main/resources/db/migration/V18__backfill_pending_notification_jobs.sql"));
                applySql(connection, Path.of("src/main/resources/db/migration/V19__add_async_dead_message_evidence.sql"));
                applySql(connection, Path.of("src/main/resources/db/migration/V20__expand_reminder_notification_delivery.sql"));
                assertEquals(1, scalar(connection,
                        "SELECT COUNT(*) FROM async_job WHERE job_type='notification.plan.fanout' AND dedupe_key='plan:1:fanout'"));
                seedForPlans(connection);

                assertEquals("idx_outbox_due", explainKey(connection,
                        "SELECT * FROM outbox_event WHERE status='PENDING' AND next_attempt_at <= NOW(3) AND (lease_until IS NULL OR lease_until < NOW(3)) ORDER BY next_attempt_at,id LIMIT 20 FOR UPDATE SKIP LOCKED"));
                assertEquals("idx_job_due", explainKey(connection,
                        "SELECT * FROM async_job WHERE status='PENDING' AND next_run_at <= NOW(3) ORDER BY next_run_at,id LIMIT 2 FOR UPDATE SKIP LOCKED"));
                assertEquals("idx_job_lease", explainKey(connection,
                        "SELECT * FROM async_job WHERE status='RUNNING' AND lease_until < NOW(3) ORDER BY lease_until,id LIMIT 20 FOR UPDATE SKIP LOCKED"));
                assertEquals("idx_job_finished", explainKey(connection,
                        "SELECT id FROM async_job WHERE status='SUCCEEDED' AND finished_at < NOW(3) ORDER BY finished_at,id LIMIT 200"));
                connection.createStatement().execute("INSERT INTO async_dead_message(stream_key,group_name,record_id,message_id,topic,error_category,error_detail,delivery_count) VALUES ('jualn:async:events:v1','backend-events-v1','1-0','11111111111111111111111111111111','sample','validation','invalid envelope',1)");
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM async_dead_message WHERE message_id='11111111111111111111111111111111'"));
                connection.createStatement().execute("INSERT INTO notification(user_id,type,title,content,is_read,source_key,content_schema_version,content_payload) VALUES (1,4,'t','c',0,'it:notification',1,JSON_OBJECT('subjectTitle','t'))");
                connection.createStatement().execute("INSERT INTO notification_delivery(notification_id,channel,status) VALUES (LAST_INSERT_ID(),'WECHAT_OFFICIAL_ACCOUNT','PENDING')");
                assertThrows(java.sql.SQLIntegrityConstraintViolationException.class, () -> connection.createStatement().execute(
                        "INSERT INTO notification_delivery(notification_id,channel,status) SELECT notification_id,channel,status FROM notification_delivery LIMIT 1"));
                verifyDeadNotificationDeliveryRepairUsesBoundedCandidates(connection);

                verifyRollbackKeepsBusinessAndOutboxAtomic(connection);
                verifyExpiredJobLeaseRecoversAndStaleOwnerCannotComplete(connection);
                verifyDuplicateConsumptionCannotRepeatEffect(connection);
            }
            verifySkipLockedClaimsDifferentRows(schemaUrl, user, password);
        } finally {
            try (Connection server = DriverManager.getConnection(serverUrl, user, password)) {
                server.createStatement().execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "redis.it", matches = "true")
    void redisConsumerGroupPendingClaimAndAckUseRealServer() {
        String key = "jualn:async:it:" + UUID.randomUUID().toString().replace("-", "");
        String group = "it-group";
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        factory.start();
        RedisTemplate<String, Object> redis = new RedisTemplate<>();
        redis.setConnectionFactory(factory);
        redis.setKeySerializer(StringRedisSerializer.UTF_8);
        redis.setHashKeySerializer(StringRedisSerializer.UTF_8);
        redis.setHashValueSerializer(StringRedisSerializer.UTF_8);
        redis.afterPropertiesSet();
        try {
            redis.opsForStream().createGroup(key, ReadOffset.from("0-0"), group);
            assertThrows(RuntimeException.class,
                    () -> redis.opsForStream().createGroup(key, ReadOffset.from("0-0"), group));
            RecordId id = redis.opsForStream().add(StreamRecords.newRecord().in(key)
                    .ofMap(Map.of("messageId", "m1", "topic", "sample", "schemaVersion", "1", "payload", "{}")));
            List<MapRecord<String, Object, Object>> delivered = redis.opsForStream().read(
                    Consumer.from(group, "first"), StreamReadOptions.empty().count(1),
                    StreamOffset.create(key, ReadOffset.lastConsumed()));
            assertEquals(id, delivered.get(0).getId());
            PendingMessages pending = redis.opsForStream().pending(key, group, Range.unbounded(), 10);
            assertEquals(1, pending.size());

            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                    key, group, "second", Duration.ZERO, id);
            assertEquals(id, claimed.get(0).getId());
            assertEquals(1L, redis.opsForStream().acknowledge(key, group, id));
            assertEquals(0L, redis.opsForStream().pending(key, group).getTotalPendingMessages());
        } finally {
            redis.delete(key);
            factory.destroy();
        }
    }

    private void verifyRollbackKeepsBusinessAndOutboxAtomic(Connection connection) throws Exception {
        connection.createStatement().execute("CREATE TABLE business_fact (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        connection.setAutoCommit(false);
        connection.createStatement().execute("INSERT INTO business_fact(id) VALUES (1)");
        connection.createStatement().execute("INSERT INTO outbox_event(message_id,topic,schema_version,payload,status,next_attempt_at) VALUES ('ffffffffffffffffffffffffffffffff','sample',1,JSON_OBJECT(),'PENDING',NOW(3))");
        connection.rollback();
        connection.setAutoCommit(true);
        assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM business_fact"));
        assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM outbox_event WHERE message_id='ffffffffffffffffffffffffffffffff'"));
    }

    private void verifyExpiredJobLeaseRecoversAndStaleOwnerCannotComplete(Connection connection) throws Exception {
        connection.createStatement().execute("INSERT INTO async_job(job_type,schema_version,payload,status,next_run_at,attempt,max_attempts,lease_owner,lease_until) "
                + "VALUES ('lease-test',1,JSON_OBJECT(),'RUNNING',NOW(3),1,4,'lost-owner',DATE_SUB(NOW(3),INTERVAL 1 SECOND))");
        long id = scalar(connection, "SELECT MAX(id) FROM async_job");
        int recovered = connection.createStatement().executeUpdate("UPDATE async_job SET status='PENDING',next_run_at=NOW(3),"
                + "lease_owner=NULL,lease_until=NULL,last_error_category='internal',last_error='worker_lost' "
                + "WHERE id=" + id + " AND status='RUNNING' AND lease_owner='lost-owner'");
        assertEquals(1, recovered);
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM async_job WHERE id=" + id
                + " AND status='PENDING' AND lease_owner IS NULL AND last_error='worker_lost'"));
        assertEquals(0, connection.createStatement().executeUpdate("UPDATE async_job SET status='SUCCEEDED' "
                + "WHERE id=" + id + " AND status='RUNNING' AND lease_owner='lost-owner'"));
    }

    private void verifyDuplicateConsumptionCannotRepeatEffect(Connection connection) throws Exception {
        connection.createStatement().execute("CREATE TABLE event_effect (id BIGINT PRIMARY KEY AUTO_INCREMENT, value VARCHAR(32) NOT NULL) ENGINE=InnoDB");
        connection.setAutoCommit(false);
        connection.createStatement().execute("INSERT INTO async_event_consumption(consumer_name,message_id) "
                + "VALUES ('verification','eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee')");
        connection.createStatement().execute("INSERT INTO event_effect(value) VALUES ('once')");
        connection.commit();
        connection.setAutoCommit(true);
        assertThrows(java.sql.SQLIntegrityConstraintViolationException.class, () -> connection.createStatement().execute(
                "INSERT INTO async_event_consumption(consumer_name,message_id) "
                        + "VALUES ('verification','eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee')"));
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM event_effect"));
    }

    private void verifyDeadNotificationDeliveryRepairUsesBoundedCandidates(Connection connection) throws Exception {
        long processingDeliveryId = scalar(connection, "SELECT MAX(id) FROM notification_delivery");
        connection.createStatement().executeUpdate("UPDATE notification_delivery SET status='PROCESSING' WHERE id="
                + processingDeliveryId);
        connection.createStatement().execute("INSERT INTO notification(user_id,type,title,content,is_read,source_key) "
                + "VALUES (1,4,'pending','pending',0,'it:notification:pending')");
        connection.createStatement().execute("INSERT INTO notification_delivery(notification_id,channel,status) "
                + "VALUES (LAST_INSERT_ID(),'WECHAT_OFFICIAL_ACCOUNT','PENDING')");
        long pendingDeliveryId = scalar(connection, "SELECT MAX(id) FROM notification_delivery");
        connection.createStatement().execute("INSERT INTO async_job(job_type,schema_version,dedupe_key,subject_type,subject_id,payload,status,next_run_at,attempt,max_attempts,dead_at) VALUES "
                + "('notification.wechat-deliver',2,'it:delivery:processing','notification-delivery','" + processingDeliveryId + "',JSON_OBJECT(),'DEAD',NOW(3),4,4,DATE_SUB(NOW(3),INTERVAL 2 SECOND)),"
                + "('notification.wechat-deliver',2,'it:delivery:pending','notification-delivery','" + pendingDeliveryId + "',JSON_OBJECT(),'DEAD',NOW(3),4,4,DATE_SUB(NOW(3),INTERVAL 1 SECOND)),"
                + "('notification.wechat-deliver',1,'it:delivery:legacy','notification','" + pendingDeliveryId + "',JSON_OBJECT(),'DEAD',NOW(3),4,4,DATE_SUB(NOW(3),INTERVAL 3 SECOND))");

        Update annotation = NotificationDeliveryMapper.class
                .getMethod("repairDeadJobResults", int.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", annotation.value()).replace("#{limit}", "?");
        try (PreparedStatement repair = connection.prepareStatement(sql)) {
            repair.setInt(1, 1);
            assertEquals(1, repair.executeUpdate());
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM notification_delivery WHERE id="
                    + processingDeliveryId + " AND status='UNKNOWN' AND result_category='remote' AND provider_error_code='job_dead'"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM notification_delivery WHERE id="
                    + pendingDeliveryId + " AND status='PENDING'"));

            repair.setInt(1, 50);
            assertEquals(1, repair.executeUpdate());
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM notification_delivery WHERE id="
                    + pendingDeliveryId + " AND status='FAILED' AND result_category='internal' AND provider_error_code='job_dead'"));
        }
    }

    private void verifySkipLockedClaimsDifferentRows(String url, String user, String password) throws Exception {
        try (Connection first = DriverManager.getConnection(url, user, password);
             Connection second = DriverManager.getConnection(url, user, password)) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            long firstId = scalar(first, "SELECT id FROM async_job WHERE status='PENDING' AND next_run_at<=NOW(3) ORDER BY next_run_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
            long secondId = scalar(second, "SELECT id FROM async_job WHERE status='PENDING' AND next_run_at<=NOW(3) ORDER BY next_run_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
            assertNotEquals(firstId, secondId);
            first.rollback();
            second.rollback();
        }
    }

    private void seedForPlans(Connection connection) throws Exception {
        try (PreparedStatement outbox = connection.prepareStatement(
                "INSERT INTO outbox_event(message_id,topic,schema_version,payload,status,next_attempt_at,lease_until) VALUES (?,?,1,JSON_OBJECT(),'PENDING',?,NULL)");
             PreparedStatement job = connection.prepareStatement(
                     "INSERT INTO async_job(job_type,schema_version,payload,status,next_run_at,attempt,max_attempts,lease_until,finished_at) VALUES ('sample',1,JSON_OBJECT(),?,?,?,?,?,?)")) {
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < 1200; i++) {
                outbox.setString(1, String.format("%032d", i));
                outbox.setString(2, "sample");
                outbox.setObject(3, now.minusSeconds(i + 1));
                outbox.addBatch();

                String status = i % 4 == 0 ? "RUNNING" : i % 4 == 1 ? "SUCCEEDED" : "PENDING";
                job.setString(1, status);
                job.setObject(2, now.minusSeconds(i + 1));
                job.setInt(3, status.equals("RUNNING") ? 1 : 0);
                job.setInt(4, 4);
                job.setObject(5, status.equals("RUNNING") ? now.minusSeconds(1) : null);
                job.setObject(6, status.equals("SUCCEEDED") ? now.minusDays(20) : null);
                job.addBatch();
            }
            outbox.executeBatch();
            job.executeBatch();
        }
        connection.createStatement().execute(
                "UPDATE outbox_event SET status='PUBLISHED',published_at=NOW(3) WHERE MOD(id,12)<>0");
        connection.createStatement().execute("ANALYZE TABLE outbox_event, async_job");
    }

    private void applySql(Connection connection, Path path) throws Exception {
        String sql = Files.readString(path);
        for (String statement : sql.split(";\\s*(?:\\R|$)")) {
            if (!statement.isBlank()) connection.createStatement().execute(statement);
        }
    }

    private String explainKey(Connection connection, String sql) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery("EXPLAIN " + sql)) {
            assertTrue(result.next());
            return result.getString("key");
        }
    }

    private long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

}
