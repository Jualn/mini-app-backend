package cn.jualn.miniapp.module.eventcontent.service;

import org.junit.jupiter.api.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in test against a dedicated, disposable database with V1..V5 already applied. */
class EventInformationDatabaseTest {
    Connection connection;
    @BeforeEach void connect() throws Exception {
        String url = System.getProperty("event.test.jdbcUrl");
        Assumptions.assumeTrue(url != null, "Dedicated event test database required");
        connection = DriverManager.getConnection(url, "root", "");
        connection.setAutoCommit(false);
    }
    @AfterEach void close() throws Exception {
        if (connection != null && !connection.isClosed()) { connection.rollback(); connection.close(); }
    }
    long insert(String sql) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet rs = s.getGeneratedKeys()) { assertTrue(rs.next()); return rs.getLong(1); }
        }
    }
    void execute(String sql) throws SQLException {
        try (Statement s = connection.createStatement()) { s.executeUpdate(sql); }
    }
    String scalar(String sql) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next()); return rs.getString(1);
        }
    }
    @Test void unknownMainTimeCanBeStored() throws Exception {
        long id = insert("INSERT INTO activity(user_id,title,content,start_time,end_time,time_description) VALUES(1,'test pending time','test content',NULL,NULL,'TBD')");
        assertNull(scalar("SELECT start_time FROM activity WHERE id="+id));
        assertEquals("0", scalar("SELECT publish_status FROM activity WHERE id="+id));
    }
    @Test void coverReferenceClearsWhenAttachmentDeleted() throws Exception {
        long id = insert("INSERT INTO activity(user_id,title,content) VALUES(1,'test cover','test')");
        long media = insert("INSERT INTO media_attachment(target_type,target_id,type,url) VALUES(2,"+id+",2,'https://example.org/test-cover.png')");
        execute("UPDATE activity SET cover_attachment_id="+media+" WHERE id="+id);
        execute("DELETE FROM media_attachment WHERE id="+media);
        assertNull(scalar("SELECT cover_attachment_id FROM activity WHERE id="+id));
    }
    @Test void actionPreventsDanglingAttachmentAndCanBeReplacedAtomically() throws Exception {
        long id = insert("INSERT INTO activity(user_id,title,content) VALUES(1,'test action','test')");
        long media = insert("INSERT INTO media_attachment(target_type,target_id,type,url) VALUES(2,"+id+",2,'https://example.org/test-qr.png')");
        long action = insert("INSERT INTO event_action(target_type,target_id,action_type,label,attachment_id) VALUES(2,"+id+",5,'test group',"+media+")");
        assertThrows(SQLException.class, () -> execute("DELETE FROM media_attachment WHERE id="+media));
        execute("DELETE FROM event_action WHERE id="+action);
        execute("DELETE FROM media_attachment WHERE id="+media);
        assertEquals("0", scalar("SELECT COUNT(*) FROM media_attachment WHERE id="+media));
    }
    @Test void expandedBodySupportsMoreThanLegacyTextBytes() throws Exception {
        String body = "中文段落".repeat(8000);
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO activity(user_id,title,content) VALUES(1,'test long body',?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1,body); s.executeUpdate();
            try (ResultSet rs = s.getGeneratedKeys()) {
                assertTrue(rs.next());
                assertEquals(body, scalar("SELECT content FROM activity WHERE id="+rs.getLong(1)));
            }
        }
    }

    org.apache.ibatis.session.SqlSession mapperSession() throws Exception {
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new org.apache.ibatis.mapping.Environment("event-test",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                new org.apache.ibatis.datasource.unpooled.UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                        System.getProperty("event.test.jdbcUrl"), "root", "")));
        try (var input = getClass().getClassLoader().getResourceAsStream("mapper/ActivityMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, config, "mapper/ActivityMapper.xml", config.getSqlFragments()).parse();
        }
        return new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config).openSession(connection);
    }
    @Test void mappedQueriesRespectCalendarEndAndExplicitEarlyEnd() throws Exception {
        long id = insert("INSERT INTO activity(user_id,title,content,status,publish_status,audit_status,start_time,end_time,start_precision,end_precision) VALUES(1,'test calendar','test',2,1,1,DATE_SUB(CURDATE(),INTERVAL 1 DAY),CURDATE(),1,1)");
        try (var session = mapperSession()) {
            var mapper = session.getMapper(cn.jualn.miniapp.module.activity.mapper.ActivityMapper.class);
            assertEquals(3, mapper.selectAdminActivityById(id).getStatus());
            assertTrue(mapper.selectPageActivities(3,null,null,null,100).stream().anyMatch(a -> a.getId() == id));
            assertEquals(1, mapper.endAdminActivityEarly(id));
            assertEquals(2, mapper.selectAdminActivityById(id).getEndPrecision());
            assertEquals(4, mapper.selectAdminActivityById(id).getStatus());
            session.rollback();
        }
        connection = null; // SqlSession closes the supplied connection.
    }
    @Test void mappedQueriesHideDraftAndDoNotInterpretUnknownPrecision() throws Exception {
        long draft = insert("INSERT INTO activity(user_id,title,content) VALUES(1,'test hidden','test')");
        long published = insert("INSERT INTO activity(user_id,title,content,status,publish_status,audit_status,start_time,end_time) VALUES(1,'test unknown','test',2,1,1,'2020-01-01','2020-01-02')");
        try (var session = mapperSession()) {
            var mapper = session.getMapper(cn.jualn.miniapp.module.activity.mapper.ActivityMapper.class);
            assertEquals(2, mapper.selectAdminActivityById(published).getStatus());
            var page = mapper.selectPageActivities(null,null,null,null,100);
            assertTrue(page.stream().noneMatch(a -> a.getId() == draft));
            assertTrue(page.stream().anyMatch(a -> a.getId() == published));
            mapper.selectAdminActivitySummary();
            mapper.selectAdminActivityPage(null,null,null,null,null,"latest",null,20);
            session.rollback();
        }
        connection = null;
    }
}
