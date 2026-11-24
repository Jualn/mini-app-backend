package cn.jualn.miniapp.module.authorization.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.env.StandardEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Commits only in a new disposable loopback schema, never application credentials. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/rbac_initial_test")
class RbacInitialMigrationTest {
    static String url;
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    RbacInitialMigration tool;
    @BeforeAll static void migrate() {
        url=System.getProperty("event.test.jdbcUrl");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url.substring(0,url.lastIndexOf('/'))+"/mysql","root",""));
        admin.execute("CREATE DATABASE rbac_initial_test");
        ds=new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","root","");
        jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("44").load().migrate();
        jdbc.update("INSERT INTO user_profile(id,openid,role,status) VALUES(99,'preserved-before-v45',1,1)");
        var before=jdbc.queryForMap("SELECT * FROM user_profile WHERE id=99");
        Flyway.configure().dataSource(ds).load().migrate();
        Flyway.configure().dataSource(ds).load().validate();
        before.put("session_version", 0L); // V78 initializes credential generation without changing prior facts.
        assertEquals(before,jdbc.queryForMap("SELECT * FROM user_profile WHERE id=99"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment",Integer.class));
    }
    @BeforeEach void reset() throws Exception {
        for(String table:List.of("rbac_offline_effect","rbac_offline_operation","rbac_authorization_change","rbac_user_role_assignment","rbac_authorization_authority","user_profile"))jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE rbac_role SET lifecycle='REGISTERED',readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED'");
        try(var input=new org.springframework.core.io.ClassPathResource("rbac/rbac-v1.json").getInputStream()) {
            for(var p:RbacJson.mapper().readTree(input).get("permissions"))jdbc.update("UPDATE rbac_permission_definition SET lifecycle=?,readiness='NOT_READY',grantable=0,unavailable_reason=? WHERE code=?",p.get("lifecycle").asText(),p.get("unavailableReason").asText(),p.get("code").asText());
        }
        for(long id=1;id<=3;id++) {
            jdbc.update("INSERT INTO user_profile(id,openid,role,status) VALUES(?,?,1,1)",id,"initial-synthetic-"+id);
            jdbc.update("INSERT INTO rbac_authorization_authority VALUES(?,'LEGACY',?,UTC_TIMESTAMP(6))",id,UUID.randomUUID().toString());
        }
        tool=new RbacInitialMigration(url,ds);
    }
    RbacInitialMigration.Plan plan() {return tool.inspect("synthetic-initial",1,"synthetic-operator","synthetic-user-approval","a".repeat(64));}
    @Test void dryRunDoesNotActivateOrGrant() {
        var p=plan(); tool.dryRun(p);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_role WHERE grantable=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_offline_operation",Integer.class));
        assertEquals("LEGACY",jdbc.queryForObject("SELECT source FROM rbac_authorization_authority WHERE user_id=1",String.class));
    }
    @Test void singleRootMigrationPreservesOthersAndReplaysWithoutRegranting() {
        var p=plan(); var users=jdbc.queryForList("SELECT * FROM user_profile ORDER BY id");
        var other=jdbc.queryForList("SELECT * FROM rbac_authorization_authority WHERE user_id IN (2,3) ORDER BY user_id");
        var result=tool.execute(p); assertEquals(result,tool.execute(p));
        assertEquals(users,jdbc.queryForList("SELECT * FROM user_profile ORDER BY id"));
        assertEquals(2,other.size());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_authority WHERE user_id IN (2,3) AND source='ASSIGNMENT'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id IN (2,3)",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment",Integer.class));
        assertEquals("ROOT",jdbc.queryForObject("SELECT role_code FROM rbac_user_role_assignment",String.class));
        assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_permission_definition WHERE grantable=1",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_role WHERE grantable=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
        assertThrows(IllegalStateException.class,this::plan);
    }
    @Test void auditFailureRollsBackCatalogAuthorityAndGrant() {
        var p=plan(); jdbc.execute("CREATE TRIGGER initial_audit_failure BEFORE INSERT ON rbac_offline_effect FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit rejection'");
        try { assertThrows(RuntimeException.class,()->tool.execute(p)); }
        finally {jdbc.execute("DROP TRIGGER initial_audit_failure");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_offline_operation",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_role WHERE grantable=1",Integer.class));
        assertEquals("LEGACY",jdbc.queryForObject("SELECT source FROM rbac_authorization_authority WHERE user_id=1",String.class));
        tool.execute(p);
    }
    @Test void changedAccountOrAuthorityRefusesEntireMigration() {
        var p=plan(); jdbc.update("UPDATE user_profile SET status=3 WHERE id=1");
        assertThrows(IllegalStateException.class,()->tool.execute(p));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment",Integer.class));
    }
    @Test void catalogChangeRefusesEntireMigration() {
        var p=plan(); jdbc.update("UPDATE rbac_role SET readiness='READY' WHERE code='LEARNING_MANAGER'");
        assertThrows(IllegalStateException.class,()->tool.execute(p));
        assertEquals("LEGACY",jdbc.queryForObject("SELECT source FROM rbac_authorization_authority WHERE user_id=1",String.class));
    }
    @Test void missingAuditCannotReplayAsSuccess() {
        var p=plan(); tool.execute(p); jdbc.update("DELETE FROM rbac_offline_effect");
        assertThrows(RuntimeException.class,()->tool.execute(p));
    }
    @Test void changedApprovalDoesNotRestoreAsSuccess() {
        var p=plan();tool.execute(p);
        jdbc.update("UPDATE rbac_offline_operation SET approvals_json=JSON_ARRAY(JSON_OBJECT('method','not-the-approved-method'))");
        assertThrows(IllegalStateException.class,()->tool.execute(p));
    }
    @Test void laterRevocationDoesNotCauseReplayToRegrant() {
        var p=plan(); var original=tool.execute(p);
        jdbc.update("UPDATE rbac_user_role_assignment SET revoked_at=UTC_TIMESTAMP(6),revoke_change_id=UUID(),current_slot=NULL,version=UUID()");
        assertEquals(original,tool.execute(p));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE revoked_at IS NULL",Integer.class));
    }
    @Test void expiryAndWrongEnvironmentRefuseBeforeAnyWrite() throws Exception {
        var p=plan(); var json=RbacJson.mapper().valueToTree(p);
        ((com.fasterxml.jackson.databind.node.ObjectNode)json).put("approvedUntil","2000-01-01T00:00:00Z");
        var expired=RbacJson.mapper().treeToValue(json,RbacInitialMigration.Plan.class);
        assertThrows(IllegalStateException.class,()->tool.execute(expired));
        ((com.fasterxml.jackson.databind.node.ObjectNode)json).put("serverUuid",UUID.randomUUID().toString());
        var wrong=RbacJson.mapper().treeToValue(json,RbacInitialMigration.Plan.class);
        assertThrows(IllegalStateException.class,()->tool.execute(wrong));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_offline_operation",Integer.class));
    }
    @Test void v2CannotBeUsedForRecoveryOrTwoApprovalFacts() {
        var p=plan();tool.execute(p);
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE rbac_offline_operation SET kind='RECOVERY'"));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE rbac_offline_operation SET approvals_json=JSON_ARRAY(JSON_OBJECT(),JSON_OBJECT())"));
    }
    @Test void explicitlyCorrectMistakenRootAndGrantLearningRoleAtomically() throws Exception {
        var initial=plan();var original=tool.execute(initial);var correction=new RbacInitialCorrection(url,ds);
        var p=correction.inspect("synthetic-correction",1,2,"synthetic-operator","synthetic-corrected-human-instruction","b".repeat(64));
        var result=correction.execute(p);assertEquals(result,correction.execute(p));
        assertEquals(original,tool.execute(initial)); // Historical replay must not reactivate wrong ROOT.
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE role_code='ROOT' AND user_id=1 AND revoked_at IS NOT NULL",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=2 AND revoked_at IS NULL",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=3",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_offline_operation",Integer.class));
        int query=0;
        try(var input=new org.springframework.core.io.ClassPathResource("db/validation/rbac_offline_preflight.sql").getInputStream()) {
            for(String statement:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split(";")) {
                String sql=statement.replaceAll("(?m)^--[^\\r\\n]*","").trim();if(sql.isEmpty())continue;
                var rows=jdbc.queryForList(sql);if(query++>=2)assertTrue(rows.isEmpty(),"Offline preflight anomaly");
            }
        }
        assertThrows(IllegalStateException.class,()->correction.inspect("synthetic-correction",2,3,"operator","different-target","c".repeat(64)));
    }
    @Test void correctionAuditFailureRollsBackBothGrantsAndRevocation() throws Exception {
        tool.execute(plan());var correction=new RbacInitialCorrection(url,ds);
        var p=correction.inspect("synthetic-correction",1,2,"synthetic-operator","synthetic-corrected-human-instruction","b".repeat(64));
        jdbc.execute("CREATE TRIGGER correction_audit_failure BEFORE INSERT ON rbac_offline_operation FOR EACH ROW BEGIN IF NEW.kind='INITIAL_ROLE_GRANT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic correction audit rejection'; END IF; END");
        try {assertThrows(RuntimeException.class,()->correction.execute(p));}
        finally {jdbc.execute("DROP TRIGGER correction_audit_failure");}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=1 AND revoked_at IS NULL",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=2",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_offline_operation",Integer.class));
        correction.execute(p);
    }
    @Test void correctionRefusesAccountChangedAfterApproval() throws Exception {
        tool.execute(plan());var correction=new RbacInitialCorrection(url,ds);
        var p=correction.inspect("synthetic-correction",1,2,"synthetic-operator","synthetic-corrected-human-instruction","b".repeat(64));
        jdbc.update("UPDATE user_profile SET status=3 WHERE id=2");
        assertThrows(IllegalStateException.class,()->correction.execute(p));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=1 AND revoked_at IS NULL",Integer.class));
    }
    @Test void implementedReleaseSupportDoesNotGrantUnknownCapabilities() {
        var catalog = new RbacCatalog();
        assertTrue(catalog.supports("AUTHORIZATION",Set.of("authorization:read")));
        assertTrue(catalog.supports("LEARNING_TASK",Set.of("learning-task:edit")));
        assertTrue(catalog.supports("ARTICLE",Set.of("article:read")));
        assertFalse(catalog.supports("POST",Set.of("post:restore")));
    }
}
