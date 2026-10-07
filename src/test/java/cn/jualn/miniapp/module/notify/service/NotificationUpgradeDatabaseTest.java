package cn.jualn.miniapp.module.notify.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "event.test.jdbcUrl", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/notification_contract_upgrade.*")
class NotificationUpgradeDatabaseTest {
    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", ""));
    }
    @Test void historicalReadUsesConfirmationTimeAndVisibilityIsBackfilledWithoutInventedDelivery() {
        JdbcTemplate jdbc = jdbc();
        assertTrue(jdbc.queryForObject("SELECT read_at > created_at AND YEAR(read_at) >= 2026 FROM notification WHERE id=91001", Boolean.class));
        assertNull(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=91002", java.sql.Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT inbox_seq FROM notification WHERE id=91003", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT inbox_seq FROM notification WHERE id=91001", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT inbox_seq FROM notification WHERE id=91002", Long.class));
        assertEquals(3, jdbc.queryForObject("SELECT inbox_seq FROM notification WHERE id=91004", Long.class));
        assertEquals(3, jdbc.queryForObject("SELECT head_seq FROM notification_inbox_counter WHERE user_id=900000011", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery", Integer.class));
    }
    @Test void onlyCurrentPendingPlansReceiveReliableBusinessAnchors() {
        JdbcTemplate jdbc = jdbc();
        for (long id : new long[] {94001,94002,94003}) {
            assertNotNull(jdbc.queryForObject("SELECT subject_starts_at FROM notify_plan WHERE id=?", java.sql.Timestamp.class, id));
        }
        for (long id : new long[] {94004,94005}) {
            assertNull(jdbc.queryForObject("SELECT subject_starts_at FROM notify_plan WHERE id=?", java.sql.Timestamp.class, id));
        }
    }
}
