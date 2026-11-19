package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.web.RbacHttpConverter;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.support.RbacTime;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/** Real disposable MySQL, Flyway, Mapper and Spring transaction proxies. No RBAC HTTP claim. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/rbac_foundation_test(?:_[a-z0-9]+)?")
class RbacDatabaseTest {
    static String url;
    static final String OPTIONS="?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    RbacMapper mapper;
    RbacAuthorizationReader reader;
    HistoricalRbacAssignmentWriter governance;

    @BeforeAll static void migrate() {
        url=System.getProperty("event.test.jdbcUrl");
        var admin=new JdbcTemplate(ds(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        String suffix=url.substring(url.lastIndexOf('/')+1).substring("rbac_foundation_test".length());
        for (String name:List.of("rbac_foundation_test","rbac_foundation_upgrade","rbac_foundation_baseline")) admin.execute("CREATE DATABASE "+name+suffix);
        var fresh=Flyway.configure().dataSource(ds(url)).load();fresh.migrate();fresh.validate();
        var initialized=new JdbcTemplate(ds(url));
        assertEquals(List.of("HOME_BANNER_MANAGER_V1","MENTOR_REGISTRAR","POINTS_ACCEPTANCE_STEWARD_V1"),initialized.queryForList("SELECT code FROM rbac_role WHERE grantable=1 ORDER BY code",String.class));
        assertEquals(List.of("home-banner:manage","mentor:qualification-manage","points:acceptance-manage"),initialized.queryForList("SELECT code FROM rbac_permission_definition WHERE grantable=1 ORDER BY code",String.class));
        try (var input=new ClassPathResource("rbac/rbac-v1.json").getInputStream()) {
            var artifact=new ObjectMapper().readTree(input);
            for(var permission:artifact.get("permissions")) {
                var actual=initialized.queryForMap("SELECT description,lifecycle,readiness,grantable,unavailable_reason FROM rbac_permission_definition WHERE code=?",permission.get("code").asText());
                assertEquals(permission.get("description").asText(),actual.get("description"));
                boolean published=Set.of("home-banner:manage","mentor:qualification-manage","points:acceptance-manage").contains(permission.get("code").asText());
                assertEquals(published?"ENABLED":permission.get("lifecycle").asText(),actual.get("lifecycle"));
                assertEquals(published?"READY":permission.get("readiness").asText(),actual.get("readiness"));
                assertEquals(published?null:permission.get("unavailableReason").asText(),actual.get("unavailable_reason"));
            }
        } catch(java.io.IOException e) {throw new IllegalStateException(e);}
        String upgrade=url.replace("rbac_foundation_test","rbac_foundation_upgrade");
        Flyway.configure().dataSource(ds(upgrade)).target("40").load().migrate();
        var old=new JdbcTemplate(ds(upgrade));old.update("INSERT INTO user_profile(id,openid,role,status) VALUES(900,'rbac-legacy',3,1)");
        Map<String,Object> before=old.queryForMap("SELECT * FROM user_profile WHERE id=900");
        var next=Flyway.configure().dataSource(ds(upgrade)).load();next.migrate();next.validate();
        before.put("session_version", 0L); // V78 initializes credential generation without changing prior facts.
        assertEquals(before,old.queryForMap("SELECT * FROM user_profile WHERE id=900"));
        assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM rbac_authorization_authority",Integer.class));
        assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment",Integer.class));
        // Production-style V1 baseline adoption, on a synthetic disposable copy only.
        String baseline=url.replace("rbac_foundation_test","rbac_foundation_baseline");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__initial_production_schema.sql")).execute(ds(baseline));
        var adopted=Flyway.configure().dataSource(ds(baseline)).baselineOnMigrate(true).baselineVersion("1").load();
        adopted.migrate();adopted.validate();
        assertEquals(66,new JdbcTemplate(ds(baseline)).queryForObject("SELECT COUNT(*) FROM rbac_permission_definition",Integer.class));
    }
    static DataSource ds(String connection) {return new DriverManagerDataSource(connection+OPTIONS,"root","");}
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));mapper=context.getBean(RbacMapper.class);
        reader=context.getBean(RbacAuthorizationReader.class);governance=context.getBean(HistoricalRbacAssignmentWriter.class);
        jdbc.update("DELETE FROM rbac_authorization_change");jdbc.update("DELETE FROM rbac_user_role_assignment");jdbc.update("DELETE FROM rbac_authorization_authority");
        jdbc.update("DELETE FROM user_profile");
        // Restore the controlled baseline before enabling only the synthetic test roles below.
        try(var input=new ClassPathResource("rbac/rbac-v1.json").getInputStream()) {
            for(var p:new ObjectMapper().readTree(input).get("permissions")) jdbc.update(
                    "UPDATE rbac_permission_definition SET lifecycle=?,readiness='NOT_READY',grantable=0,unavailable_reason=? WHERE code=?",
                    p.get("lifecycle").asText(),p.get("unavailableReason").asText(),p.get("code").asText());
        } catch(java.io.IOException e) {throw new IllegalStateException(e);}
        jdbc.update("UPDATE rbac_role SET lifecycle='REGISTERED',readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED'");
        for(long id=1;id<=5;id++) {
            jdbc.update("INSERT INTO user_profile(id,openid,role,status) VALUES(?,?,?,1)",id,"rbac-synthetic-"+id,id==5?3:1);
            jdbc.update("INSERT INTO rbac_authorization_authority VALUES(?,'ASSIGNMENT',?,UTC_TIMESTAMP(6))",id,UUID.randomUUID().toString());
        }
        enable("ROOT");enable("NOTICE_MONITOR");
        fixture(1,"ROOT","AUTHORIZATION",null);fixture(2,"ROOT","AUTHORIZATION",null);
    }
    @AfterEach void close() { if(context!=null) context.close(); }
    void enable(String role) {
        jdbc.update("UPDATE rbac_permission_definition p JOIN rbac_role_permission m ON p.code=m.permission_code SET p.lifecycle='ENABLED',p.readiness='READY',p.grantable=1,p.unavailable_reason=NULL WHERE m.role_code=?",role);
        jdbc.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=?",role);
    }
    String fixture(long user,String role,String space,LocalDateTime expires) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,expires_at,version,grant_change_id) VALUES(?,?,?,?,'GLOBAL',UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,?,?,?)",id,user,role,space,expires,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        return id;
    }
    Command grant(long actor,long target,String key,String role,String space,LocalDateTime expires) {
        return new Command(actor,target,key,"synthetic approved purpose",mapper.subject(target).getVersion(),null,null,role,space,"GLOBAL",expires);
    }
    Command revoke(long actor,long target,String key,String id) {
        Assignment a=mapper.assignments(target).stream().filter(x->x.getId().equals(id)).findFirst().orElseThrow();
        return new Command(actor,target,key,"synthetic duty ended",mapper.subject(target).getVersion(),id,a.getVersion(),null,null,null,null);
    }
    void problem(String type,org.junit.jupiter.api.function.Executable action) {
        assertEquals("/problems/"+type,assertThrows(ContractProblemException.class,action).getType());
    }
    @Test void v52CatalogUpgradePreservesExistingAuthorityAssignmentsAndAvailability() {
        String upgrade=url.replace("rbac_foundation_test","rbac_owner_catalog_upgrade");
        var admin=new JdbcTemplate(ds(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        admin.execute("CREATE DATABASE "+upgrade.substring(upgrade.lastIndexOf('/')+1));
        Flyway.configure().dataSource(ds(upgrade)).target("51").load().migrate();
        var previous=new JdbcTemplate(ds(upgrade));
        previous.update("INSERT INTO user_profile(id,openid,role,status) VALUES(51,'owner-upgrade',1,1)");
        previous.update("INSERT INTO rbac_authorization_authority VALUES(51,'ASSIGNMENT','keep-version',UTC_TIMESTAMP(6))");
        previous.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='user:read'");
        // A release publication must preserve an explicit environment disable.
        previous.update("UPDATE rbac_permission_definition SET lifecycle='DISABLED' WHERE code='mentor:qualification-manage'");
        previous.update("UPDATE rbac_role SET lifecycle='DISABLED' WHERE code='MENTOR_REGISTRAR'");
        previous.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,expires_at,version,grant_change_id) VALUES(?,51,'ROOT','AUTHORIZATION','GLOBAL',UTC_TIMESTAMP(6),NULL,'keep-assignment-version',?)",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var authorities=previous.queryForList("SELECT * FROM rbac_authorization_authority");
        var assignments=previous.queryForList("SELECT * FROM rbac_user_role_assignment");
        var states=previous.queryForList("SELECT code,lifecycle,readiness,grantable,unavailable_reason FROM rbac_permission_definition ORDER BY code");
        var roles=previous.queryForList("SELECT code,lifecycle,readiness,grantable,unavailable_reason FROM rbac_role ORDER BY code");
        var next=Flyway.configure().dataSource(ds(upgrade)).load();next.migrate();next.validate();
        assertEquals(authorities,previous.queryForList("SELECT * FROM rbac_authorization_authority"));
        var afterAssignments=previous.queryForList("SELECT * FROM rbac_user_role_assignment");
        afterAssignments.forEach(row->assertEquals("STANDALONE",row.remove("origin_kind")));
        assertEquals(assignments,afterAssignments);
        // V67 adds one published Points owner definition; every pre-existing state stays exact.
        assertEquals(states,previous.queryForList("SELECT code,lifecycle,readiness,grantable,unavailable_reason FROM rbac_permission_definition WHERE code<>'points:acceptance-manage' ORDER BY code"));
        assertEquals(1,previous.queryForObject("SELECT COUNT(*) FROM rbac_permission_definition WHERE code='points:acceptance-manage' AND lifecycle='ENABLED' AND readiness='READY' AND grantable=1 AND unavailable_reason IS NULL",Integer.class));
        assertEquals(1,previous.queryForObject("SELECT COUNT(*) FROM rbac_role WHERE code='POINTS_ACCEPTANCE_STEWARD_V1' AND lifecycle='ENABLED' AND readiness='READY' AND grantable=1 AND unavailable_reason IS NULL",Integer.class));
        assertEquals(roles,previous.queryForList("SELECT code,lifecycle,readiness,grantable,unavailable_reason FROM rbac_role WHERE code NOT IN ('POINTS_READER_V1','ARTICLE_CATEGORY_STEWARD_V1','POST_DICTIONARY_STEWARD_V1','POST_MODERATOR_V1','POINTS_ACCEPTANCE_STEWARD_V1') ORDER BY code"));
        assertEquals(List.of("points:read"),previous.queryForList("SELECT permission_code FROM rbac_role_permission WHERE role_code='POINTS_READER_V1'",String.class));
        assertEquals(1,previous.queryForObject("SELECT COUNT(*) FROM rbac_role WHERE code='POINTS_READER_V1' AND lifecycle='REGISTERED' AND readiness='NOT_READY' AND grantable=0",Integer.class));
        assertEquals("平台所有者 ROOT",previous.queryForObject("SELECT name FROM rbac_role WHERE code='ROOT'",String.class));
        assertEquals(new RbacCatalog().digest(),previous.queryForObject("SELECT catalog_digest FROM rbac_role WHERE code='ROOT'",String.class));
    }

    @Test void rootOwnerUsesOnlyReadySupportedCapabilitiesAndRevocationRemovesTheProjection() {
        Decision owner=reader.read(1);
        assertEquals(1,owner.grants().size());
        assertEquals("AUTHORIZATION",owner.grants().get(0).namespace());
        assertTrue(owner.allows("notice:read","NOTIFICATION"));
        assertTrue(owner.allows("system:read","SYSTEM"));
        assertFalse(owner.allows("user:read","USER")); // Provider not in this release support list.
        assertFalse(owner.allows("system:read","NOTIFICATION"));
        assertTrue(owner.ownerCapabilities().stream().allMatch(c -> c.rootAssignmentId().equals(owner.grants().get(0).assignmentId())));
        assertTrue(reader.read(3).ownerCapabilities().isEmpty());
        var wire=new RbacHttpConverter().capabilities(new RbacQueryService.CapabilitiesBO(owner,owner.evaluatedAt().plusSeconds(60)));
        assertEquals(owner.grants().get(0).assignmentId(),wire.ownerCapabilities().get(0).rootAssignmentId());
        assertEquals(owner.ownerCapabilities().size(),wire.ownerCapabilities().size());
        String version=owner.catalogVersion();
        jdbc.update("UPDATE rbac_permission_definition SET lifecycle='DISABLED',grantable=0,unavailable_reason='DISABLED' WHERE code='system:read'");
        Decision disabled=reader.read(1);
        assertNotEquals(version,disabled.catalogVersion());
        assertTrue(disabled.allows("notice:read","NOTIFICATION"));
        assertFalse(disabled.allows("system:read","SYSTEM"));
        jdbc.update("UPDATE rbac_role SET lifecycle='DISABLED',grantable=0,unavailable_reason='DISABLED' WHERE code='ROOT'");
        assertTrue(reader.read(1).ownerCapabilities().isEmpty());
        enable("ROOT");
        jdbc.update("UPDATE user_profile SET status=3 WHERE id=1");
        assertTrue(reader.read(1).ownerCapabilities().isEmpty());
        jdbc.update("UPDATE user_profile SET status=1 WHERE id=1");
        String assignment=owner.grants().get(0).assignmentId();
        governance.revoke(revoke(2,1,UUID.randomUUID().toString(),assignment));
        Decision revoked=reader.read(1);
        assertTrue(revoked.grants().isEmpty());
        assertTrue(revoked.ownerCapabilities().isEmpty());
        assertFalse(revoked.allows("notice:read","NOTIFICATION"));
    }

    @Test void controlledCatalogIsCompleteImmutableAndProductionSupportIsEmpty() {
        var catalog=new RbacCatalog(Set.of());catalog.verify(mapper.permissions(),mapper.roles(),mapper.members());
        assertEquals(79,mapper.permissions().size());assertEquals(28,mapper.roles().size());
        var production=new RbacAuthorizationReader(mapper,catalog,new AdminPermissionPolicy());
        assertFalse(production.readCurrent(1,mapper.now()).canUseAdmin());
        jdbc.update("DELETE FROM rbac_role_permission WHERE role_code='ROOT' AND permission_code='audit-log:read'");
        try {problem("authorization-source-unavailable",()->reader.read(1));}
        finally {jdbc.update("INSERT INTO rbac_role_permission VALUES('ROOT','audit-log:read')");}
    }
    @Test void sourcesAreExclusiveAndMissingAuthorityNeverFallsBack() {
        fixture(5,"NOTICE_MONITOR","NOTIFICATION",null);
        jdbc.update("UPDATE rbac_authorization_authority SET source='LEGACY' WHERE user_id=5");
        Decision legacy=reader.read(5);assertEquals(List.of("*"),legacy.legacyPermissions());assertTrue(legacy.grants().isEmpty());
        assertFalse(legacy.allows("notice:read","NOTIFICATION"));
        jdbc.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT' WHERE user_id=5");
        assertTrue(reader.read(5).allows("notice:read","NOTIFICATION"));assertTrue(reader.read(5).legacyPermissions().isEmpty());
        jdbc.update("DELETE FROM rbac_authorization_authority WHERE user_id=5");
        problem("authorization-source-unavailable",()->reader.read(5));
        assertFalse(reader.read(3).canUseAdmin());
    }
    @Test void explicitLegacySqlBackfillIsRepeatableAndNeverMigratesPermissions() {
        jdbc.update("DELETE FROM rbac_authorization_authority WHERE user_id=5");
        jdbc.update("INSERT INTO user_profile(id,openid,role,status,deleted_at) VALUES(6,'d07-muted',2,2,NULL),(7,'d07-deleted',1,1,UTC_TIMESTAMP())");
        var users=jdbc.queryForList("SELECT * FROM user_profile ORDER BY id");
        var assignments=jdbc.queryForList("SELECT * FROM rbac_user_role_assignment ORDER BY id");
        var receipts=jdbc.queryForList("SELECT * FROM rbac_authorization_change ORDER BY id");
        var existing=jdbc.queryForList("SELECT * FROM rbac_authorization_authority ORDER BY user_id");
        var script=new ResourceDatabasePopulator(new ClassPathResource("db/validation/rbac_authority_legacy_backfill.sql"));
        script.execute(context.getBean(DataSource.class));
        var authorities=jdbc.queryForList("SELECT * FROM rbac_authorization_authority ORDER BY user_id");
        assertEquals(7,authorities.size());assertEquals(existing,authorities.subList(0,4));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_authority WHERE source='LEGACY'",Integer.class));
        script.execute(context.getBean(DataSource.class));
        assertEquals(authorities,jdbc.queryForList("SELECT * FROM rbac_authorization_authority ORDER BY user_id"));
        assertEquals(users,jdbc.queryForList("SELECT * FROM user_profile ORDER BY id"));
        assertEquals(assignments,jdbc.queryForList("SELECT * FROM rbac_user_role_assignment ORDER BY id"));
        assertEquals(receipts,jdbc.queryForList("SELECT * FROM rbac_authorization_change ORDER BY id"));
        assertEquals(List.of("*"),reader.read(5).legacyPermissions());
        assertFalse(reader.read(6).canUseAdmin());assertFalse(reader.readLegacyEntry(6).canUseAdmin());
        assertFalse(reader.readLegacyEntry(7).canUseAdmin());
    }
    @Test void namespaceGlobalAndIndependentAssignmentWitnesses() {
        fixture(3,"NOTICE_MONITOR","NOTIFICATION",null);fixture(3,"NOTICE_MONITOR","SYSTEM",null);
        Decision d=reader.read(3);assertTrue(d.allows("notice:read","NOTIFICATION"));assertTrue(d.allows("system:read","SYSTEM"));
        assertFalse(d.allows("notice:read","SYSTEM"));assertFalse(d.allows("system:read","NOTIFICATION"));
        assertFalse(d.allows("authorization:root-manage","AUTHORIZATION"));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE rbac_user_role_assignment SET scope_type='ORGANIZATION' WHERE user_id=3"));
        problem("authorization-grant-forbidden",()->governance.grant(grant(1,4,"rbac-test-invalidspace","NOTICE_MONITOR","USER",null)));
    }
    @Test void revokeExpiryDisabledRoleAndCurrentAccountAreChecked() {
        var first=governance.grant(grant(1,3,"rbac-test-grant-current","NOTICE_MONITOR","NOTIFICATION",null));
        assertTrue(reader.read(3).canUseAdmin());
        governance.revoke(revoke(2,3,"rbac-test-revoke-current",first.assignment().getId()));
        assertFalse(reader.read(3).canUseAdmin());
        fixture(3,"NOTICE_MONITOR","NOTIFICATION",mapper.now().minusNanos(1000));assertFalse(reader.read(3).canUseAdmin());
        fixture(3,"NOTICE_MONITOR","SYSTEM",null);assertTrue(reader.read(3).canUseAdmin());
        jdbc.update("UPDATE rbac_role SET lifecycle='DISABLED',grantable=0 WHERE code='NOTICE_MONITOR'");assertFalse(reader.read(3).canUseAdmin());
        enable("NOTICE_MONITOR");jdbc.update("UPDATE user_profile SET status=3 WHERE id=3");assertFalse(reader.read(3).canUseAdmin());
        jdbc.update("UPDATE user_profile SET ban_expire_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=3");assertTrue(reader.read(3).canUseAdmin());
        jdbc.update("UPDATE user_profile SET deleted_at=UTC_TIMESTAMP() WHERE id=3");assertFalse(reader.read(3).canUseAdmin());
    }
    @Test void successfulReplayReturnsOriginalFactAndRejectsDifferentMeaning() {
        var command=grant(1,3,"rbac-test-fixed-receipt","NOTICE_MONITOR","NOTIFICATION",mapper.now().plusMinutes(5));
        var first=governance.grant(command);var second=governance.grant(command);assertEquals(first,second);
        governance.revoke(revoke(2,3,"rbac-test-fixed-revoke",first.assignment().getId()));
        assertEquals(first,governance.grant(command));assertFalse(reader.read(3).canUseAdmin());
        problem("idempotency-key-conflict",()->governance.grant(grant(1,4,command.idempotencyKey(),"NOTICE_MONITOR","NOTIFICATION",null)));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
        jdbc.update("UPDATE user_profile SET status=3 WHERE id=1");problem("authorization-management-forbidden",()->governance.grant(command));
    }
    @Test void normalizedExpiryRoundTripsUtcMicrosecondsAndEnforcesTheExactBoundary() {
        var expiry=RbacTime.expiry("2099-11-15T10:00:00.123456+08:00");
        var c=grant(1,3,"rbac-final-d06-micro-0001","NOTICE_MONITOR","NOTIFICATION",expiry);
        var first=governance.grant(c);
        var stored=mapper.assignments(3).get(0);
        assertEquals(expiry,stored.getExpiresAt());
        assertEquals("2099-11-15 02:00:00.123456",jdbc.queryForObject(
                "SELECT DATE_FORMAT(expires_at,'%Y-%m-%d %H:%i:%s.%f') FROM rbac_user_role_assignment WHERE id=?",String.class,stored.getId()));
        assertTrue(reader.readCurrent(3,expiry.minusNanos(1000)).allows("notice:read","NOTIFICATION"));
        assertFalse(reader.readCurrent(3,expiry).canUseAdmin());
        assertFalse(reader.readCurrent(3,expiry.plusNanos(1000)).canUseAdmin());
        assertEquals("EXPIRED",stored.status(expiry));
        var equivalent=new Command(c.actorUserId(),c.targetUserId(),c.idempotencyKey(),c.reason(),c.expectedAuthorizationVersion(),
                null,null,c.roleCode(),c.namespace(),c.scopeType(),RbacTime.expiry("2099-11-14T21:00:00.123456-05:00"));
        assertEquals(first,governance.grant(equivalent));
        var different=new Command(c.actorUserId(),c.targetUserId(),c.idempotencyKey(),c.reason(),c.expectedAuthorizationVersion(),
                null,null,c.roleCode(),c.namespace(),c.scopeType(),expiry.plusNanos(1000));
        problem("idempotency-key-conflict",()->governance.grant(different));
        governance.revoke(revoke(2,3,"rbac-final-d06-revoke-0001",stored.getId()));
        assertEquals(first,governance.grant(equivalent));
        assertFalse(reader.read(3).canUseAdmin());
        assertEquals(1,mapper.assignments(3).size());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
    }
    @Test void duplicatesVersionAndRevokedOrExpiredConflictsKeepHistory() {
        var stale=grant(1,3,"rbac-test-stale-version","NOTICE_MONITOR","SYSTEM",null);
        var receipt=governance.grant(grant(1,3,"rbac-test-first-slot","NOTICE_MONITOR","NOTIFICATION",null));
        problem("authorization-version-conflict",()->governance.grant(stale));
        problem("rbac-assignment-already-active",()->governance.grant(grant(1,3,"rbac-test-repeat-slot","NOTICE_MONITOR","NOTIFICATION",null)));
        var revoke=revoke(1,3,"rbac-test-first-revoke",receipt.assignment().getId());var original=governance.revoke(revoke);assertEquals(original,governance.revoke(revoke));
        problem("rbac-assignment-revoked",()->governance.revoke(revoke(1,3,"rbac-test-new-revoke",receipt.assignment().getId())));
        var expired=fixture(3,"NOTICE_MONITOR","SYSTEM",mapper.now().minusNanos(1000));
        problem("rbac-assignment-expired",()->governance.revoke(revoke(1,3,"rbac-test-expired-revoke",expired)));
        governance.grant(grant(1,3,"rbac-test-after-expiry","NOTICE_MONITOR","SYSTEM",null));
        assertEquals(3,mapper.assignments(3).size());
    }
    @Test void auditFailureRollsBackAssignmentVersionAndSlot() {
        var c=grant(1,3,"rbac-test-audit-rollback","NOTICE_MONITOR","NOTIFICATION",null);
        jdbc.execute("CREATE TRIGGER rbac_test_reject_audit BEFORE INSERT ON rbac_authorization_change FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit failure'");
        try {problem("authorization-source-unavailable",()->governance.grant(c));}
        finally {jdbc.execute("DROP TRIGGER rbac_test_reject_audit");}
        assertTrue(mapper.assignments(3).isEmpty());assertEquals(c.expectedAuthorizationVersion(),mapper.subject(3).getVersion());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
        governance.grant(c);assertEquals(1,mapper.assignments(3).size());
    }
    @Test void exactComparisonConstraintsAndRootNoExpiry() {
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO rbac_authorization_authority VALUES(99999,'LEGACY','version',UTC_TIMESTAMP(6))"));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE rbac_authorization_authority SET source='legacy' WHERE user_id=3"));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE rbac_user_role_assignment SET expires_at=UTC_TIMESTAMP()+INTERVAL 1 DAY WHERE role_code='ROOT'"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_role WHERE code='ROOT '",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_role WHERE code='root'",Integer.class));
        var a=governance.grant(grant(1,3,"Rbac-case-sensitive-key","NOTICE_MONITOR","SYSTEM",null));
        governance.grant(grant(1,4,"rbac-case-sensitive-key","NOTICE_MONITOR","SYSTEM",null));
        assertNotNull(a);assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
        problem("authorization-self-change-forbidden",()->governance.grant(grant(1,1,"rbac-test-root-selfgrant","NOTICE_MONITOR","SYSTEM",null)));
    }
    @Test void simultaneousRequestsCommitOnceAndVersionsPreventOverwrite() throws Exception {
        var command=grant(1,3,"rbac-test-concurrent-key","NOTICE_MONITOR","NOTIFICATION",null);
        var results=race(()->governance.grant(command),()->governance.grant(command));assertEquals(results.get(0),results.get(1));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
        var c1=grant(1,4,"rbac-test-concurrent-one","NOTICE_MONITOR","NOTIFICATION",null);
        var c2=grant(2,4,"rbac-test-concurrent-two","NOTICE_MONITOR","SYSTEM",null);
        var conflicts=race(()->outcome(()->governance.grant(c1)),()->outcome(()->governance.grant(c2)));
        assertEquals(1,conflicts.stream().filter("authorization-version-conflict"::equals).count());
        assertEquals(1,mapper.assignments(4).size());
    }
    @Test void twoRootsCannotConcurrentlyRemoveEachOtherOrSelf() throws Exception {
        var r1=revoke(1,2,"rbac-test-root-revoke-two",mapper.assignments(2).get(0).getId());
        var r2=revoke(2,1,"rbac-test-root-revoke-one",mapper.assignments(1).get(0).getId());
        var outcomes=race(()->outcome(()->governance.revoke(r1)),()->outcome(()->governance.revoke(r2)));
        assertEquals(1,outcomes.stream().filter("authorization-management-forbidden"::equals).count());
        long remaining=reader.read(1).canUseAdmin()?1:2;
        assertEquals(1,(reader.read(1).canUseAdmin()?1:0)+(reader.read(2).canUseAdmin()?1:0));
        problem("authorization-self-change-forbidden",()->governance.revoke(revoke(remaining,remaining,"rbac-test-last-root-self",mapper.assignments(remaining).get(0).getId())));
    }
    @Test void accountGuardParticipatesInCallerRollbackAndNeverCommitsAlone() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->governance.protectAccountChange(1,2));
        var transaction=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->transaction.executeWithoutResult(s->governance.protectAccountChange(1,2)));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(s->{governance.protectAccountChange(1,2);jdbc.update("UPDATE user_profile SET status=3 WHERE id=2");s.setRollbackOnly();});
        assertTrue(reader.read(2).canUseAdmin());
        transaction.executeWithoutResult(s->{governance.protectAccountChange(1,2);jdbc.update("UPDATE user_profile SET status=3 WHERE id=2");});
        assertFalse(reader.read(2).canUseAdmin());
        problem("authorization-self-change-forbidden",()->transaction.executeWithoutResult(s->governance.protectAccountChange(1,1)));
    }
    @Test void databaseFailureIsClosedAndNeverReturnsLegacyAllow() {
        jdbc.execute("RENAME TABLE rbac_authorization_authority TO rbac_test_unavailable_authority");
        try {problem("authorization-source-unavailable",()->reader.read(5));}
        finally {jdbc.execute("RENAME TABLE rbac_test_unavailable_authority TO rbac_authorization_authority");}
    }
    @Test void newReaderEscapesAnOuterOldMvccSnapshot() throws Exception {
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(s->{
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=3",Integer.class));
            var pool=Executors.newSingleThreadExecutor();
            try {pool.submit(()->governance.grant(grant(1,3,"rbac-test-fresh-main-db","NOTICE_MONITOR","SYSTEM",null))).get(10,TimeUnit.SECONDS);}
            catch(Exception e){throw new IllegalStateException(e);} finally{pool.shutdownNow();}
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=3",Integer.class));
            assertTrue(reader.read(3).allows("system:read","SYSTEM"));
        });
    }
    @Test void permissionDisableInvalidatesWholeRoleAndCatalogVersion() {
        fixture(3,"NOTICE_MONITOR","SYSTEM",null);Decision before=reader.read(3);
        jdbc.update("UPDATE rbac_permission_definition SET lifecycle='DISABLED',grantable=0 WHERE code='notice:read'");
        Decision after=reader.read(3);assertFalse(after.canUseAdmin());assertNotEquals(before.catalogVersion(),after.catalogVersion());
        assertEquals(before.authorizationVersion(),after.authorizationVersion());
    }
    @Test void unavailableUnregisteredAndLegacyTargetsDoNotBecomeSuccessfulGrants() {
        problem("authorization-capability-not-ready",()->governance.grant(grant(1,3,"rbac-test-unready-role","CONTENT_EDITOR","ARTICLE",null)));
        problem("rbac-role-not-grantable",()->governance.grant(grant(1,3,"rbac-test-unknown-role","NOT_A_ROLE","SYSTEM",null)));
        jdbc.update("UPDATE rbac_authorization_authority SET source='LEGACY' WHERE user_id=3");
        problem("authorization-source-migration-required",()->governance.grant(grant(1,3,"rbac-test-legacy-target","NOTICE_MONITOR","SYSTEM",null)));
        var invalidTarget=new Command(5,9999,"rbac-test-private-target","purpose","original",null,null,"NOTICE_MONITOR","SYSTEM","GLOBAL",null);
        problem("authorization-management-forbidden",()->governance.grant(invalidTarget));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
    }
    @Test void corruptedNamespaceAndAccountStatusFailClosed() {
        String a=fixture(3,"NOTICE_MONITOR","SYSTEM",null);
        jdbc.update("UPDATE rbac_user_role_assignment SET namespace='USER' WHERE id=?",a);
        problem("authorization-source-unavailable",()->reader.read(3));
        jdbc.update("UPDATE rbac_user_role_assignment SET namespace='SYSTEM' WHERE id=?",a);
        jdbc.update("UPDATE user_profile SET status=99 WHERE id=3");
        problem("authorization-source-unavailable",()->reader.read(3));
    }
    @Test void revokeAuditFailureRollsBackAndUniqueSlotsAreDatabaseConstraints() {
        var grant=governance.grant(grant(1,3,"rbac-test-grant-for-revoke","NOTICE_MONITOR","SYSTEM",null));
        assertThrows(DataAccessException.class,()->fixture(3,"NOTICE_MONITOR","SYSTEM",null));
        var revoke=revoke(1,3,"rbac-test-revoke-rollback",grant.assignment().getId());
        jdbc.execute("CREATE TRIGGER rbac_test_reject_audit BEFORE INSERT ON rbac_authorization_change FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit failure'");
        try{problem("authorization-source-unavailable",()->governance.revoke(revoke));}
        finally{jdbc.execute("DROP TRIGGER rbac_test_reject_audit");}
        assertTrue(reader.read(3).canUseAdmin());assertEquals(revoke.expectedAuthorizationVersion(),mapper.subject(3).getVersion());
        assertEquals("ACTIVE",mapper.assignments(3).get(0).status(mapper.now()));
        governance.revoke(revoke);assertFalse(reader.read(3).canUseAdmin());
    }
    @Test void parallelAccountChangesUsingGuardPreserveOneCurrentRoot() throws Exception {
        var a=race(()->outcome(()->accountTransaction().executeWithoutResult(s->{governance.protectAccountChange(1,2);jdbc.update("UPDATE user_profile SET status=3 WHERE id=2");})),
            ()->outcome(()->accountTransaction().executeWithoutResult(s->{governance.protectAccountChange(2,1);jdbc.update("UPDATE user_profile SET status=3 WHERE id=1");})));
        assertEquals(1,a.stream().filter("authorization-management-forbidden"::equals).count());
        assertEquals(1,(reader.read(1).canUseAdmin()?1:0)+(reader.read(2).canUseAdmin()?1:0));
    }
    TransactionTemplate accountTransaction() {
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;
    }
    @Test void legacyRestrictionTimeUsesTheUserOwnersLocalTimeDomain() {
        fixture(3,"NOTICE_MONITOR","SYSTEM",null);
        jdbc.update("UPDATE user_profile SET status=3,ban_expire_at=? WHERE id=3",LocalDateTime.now().plusMinutes(1));
        assertFalse(reader.read(3).canUseAdmin());
        jdbc.update("UPDATE user_profile SET ban_expire_at=? WHERE id=3",LocalDateTime.now().minusMinutes(1));
        assertTrue(reader.read(3).canUseAdmin());
    }
    @Test void scopeAndAvailabilityErrorsPreserveContractDistinctions() {
        var base=grant(1,3,"rbac-test-scope-validation","NOTICE_MONITOR","SYSTEM",null);
        problem("validation-error",()->governance.grant(new Command(1,3,base.idempotencyKey(),base.reason(),base.expectedAuthorizationVersion(),null,null,"NOTICE_MONITOR","SYSTEM",null,null)));
        problem("rbac-scope-unsupported",()->governance.grant(new Command(1,3,base.idempotencyKey(),base.reason(),base.expectedAuthorizationVersion(),null,null,"NOTICE_MONITOR","SYSTEM","ORGANIZATION",null)));
        problem("validation-error",()->governance.grant(new Command(1,3,base.idempotencyKey(),base.reason(),base.expectedAuthorizationVersion(),null,null,"NOTICE_MONITOR","SYSTEM","UNKNOWN",null)));
        jdbc.update("UPDATE rbac_permission_definition SET lifecycle='DISABLED',grantable=0 WHERE code='notice:read'");
        problem("rbac-role-not-grantable",()->governance.grant(base));
    }
    @Test void reasonUsesCodePointsAndIdempotencyKeysHaveExactBounds() {
        var base=grant(1,3,"K".repeat(128),"NOTICE_MONITOR","SYSTEM",null);
        var command=new Command(1,3,base.idempotencyKey(),"\uD83D\uDE00".repeat(500),base.expectedAuthorizationVersion(),null,null,"NOTICE_MONITOR","SYSTEM","GLOBAL",null);
        var receipt=governance.grant(command);assertNotNull(receipt);
        assertEquals(500,jdbc.queryForObject("SELECT CHAR_LENGTH(reason) FROM rbac_authorization_change",Integer.class));
        var invalid=new Command(1,4,"K".repeat(129),"purpose",mapper.subject(4).getVersion(),null,null,"NOTICE_MONITOR","SYSTEM","GLOBAL",null);
        problem("validation-error",()->governance.grant(invalid));
    }
    @Test void reasonWhitespaceMatchesContractAndFingerprintPreservesExactInput() {
        var base=grant(1,3,"rbac-final-reason-0001","NOTICE_MONITOR","SYSTEM",null);
        for(String reason:List.of("", " \t\n", "\u00a0", "\ufeff", "\u202f", "\u3000", "\uD83D\uDE00".repeat(501))) {
            problem("validation-error",()->governance.grant(new Command(1,3,base.idempotencyKey(),reason,base.expectedAuthorizationVersion(),null,null,"NOTICE_MONITOR","SYSTEM","GLOBAL",null)));
        }
        String reason="\u00a0 approved \ufeff";
        var command=new Command(1,3,base.idempotencyKey(),reason,base.expectedAuthorizationVersion(),null,null,"NOTICE_MONITOR","SYSTEM","GLOBAL",null);
        var receipt=governance.grant(command);
        assertEquals(reason,jdbc.queryForObject("SELECT reason FROM rbac_authorization_change WHERE id=?",String.class,receipt.changeId()));
        problem("idempotency-key-conflict",()->governance.grant(new Command(1,3,base.idempotencyKey(),"approved",base.expectedAuthorizationVersion(),null,null,"NOTICE_MONITOR","SYSTEM","GLOBAL",null)));
        assertEquals(receipt,governance.grant(command));
    }
    @Test void keysAreBinaryExactAndExpiryIsMicrosecondUtcWithoutTruncation() {
        var lower=grant(1,3,"rbac-final-key-aaaa","NOTICE_MONITOR","SYSTEM",mapper.now().plusMinutes(5).withNano(123456000));
        var receipt=governance.grant(lower);
        assertEquals(lower.expiresAt(),mapper.assignments(3).get(0).getExpiresAt());
        var upper=grant(1,4,"RBAC-final-key-aaaa","NOTICE_MONITOR","SYSTEM",null);
        assertNotNull(governance.grant(upper));
        for(String key:List.of("short", "-rbac-final-key-0001", "rbac-final-key-0001 ", "rbac-final-key-非ascii")) {
            problem("validation-error",()->governance.grant(grant(1,4,key,"NOTICE_MONITOR","NOTIFICATION",null)));
        }
        problem("validation-error",()->governance.grant(grant(1,4,"rbac-final-nanos-001","NOTICE_MONITOR","NOTIFICATION",mapper.now().plusMinutes(5).withNano(123456789))));
        assertEquals(receipt,governance.grant(lower));
    }
    record OldIntent(String action,Command command) {}
    @Test void existingV1KeysRecoverTheirStoredReceiptWithoutAcceptingLostExpiryPrecision() throws Exception {
        var command=grant(1,3,"legacy-codec-replay-0001","NOTICE_MONITOR","SYSTEM",mapper.now().plusMinutes(5).withNano(123456000));
        var original=governance.grant(command);
        String oldReceipt=RbacJson.legacyMapper().writeValueAsString(original);
        String oldHash=RbacCatalog.hash(RbacJson.legacyMapper().writeValueAsBytes(new OldIntent("GRANT",command)));
        jdbc.update("UPDATE rbac_authorization_change SET request_fingerprint=?,receipt=? WHERE id=?",oldHash,oldReceipt,original.changeId());
        var expected=RbacJson.mapper().readValue(oldReceipt,Receipt.class);
        assertEquals(expected,governance.grant(command));
        var different=new Command(command.actorUserId(),command.targetUserId(),command.idempotencyKey(),command.reason(),command.expectedAuthorizationVersion(),
                null,null,command.roleCode(),command.namespace(),command.scopeType(),command.expiresAt().plusNanos(1000));
        problem("idempotency-key-conflict",()->governance.grant(different));
        assertEquals(expected,governance.grant(command));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
    }
    @Test void corruptReceiptCannotBeReturnedOrReexecuted() throws Exception {
        var command=grant(1,3,"rbac-test-receipt-integrity","NOTICE_MONITOR","SYSTEM",null);
        var receipt=governance.grant(command);
        receipt.assignment().setUserId(4L);
        jdbc.update("UPDATE rbac_authorization_change SET receipt=? WHERE id=?",context.getBean(ObjectMapper.class).writeValueAsString(receipt),receipt.changeId());
        problem("authorization-source-unavailable",()->governance.grant(command));
        assertEquals(1,mapper.assignments(3).size());assertTrue(mapper.assignments(4).isEmpty());
        receipt.assignment().setUserId(3L);
        var incomplete=new Receipt(receipt.changeId(),receipt.operationId(),receipt.action(),receipt.committedAt(),null,receipt.assignment());
        jdbc.update("UPDATE rbac_authorization_change SET receipt=? WHERE id=?",RbacJson.mapper().writeValueAsString(incomplete),receipt.changeId());
        problem("authorization-source-unavailable",()->governance.grant(command));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
    }
    String outcome(Runnable action) {try {action.run();return "SUCCESS";}catch(ContractProblemException e){return e.getType().substring("/problems/".length());}}
    <T> List<T> race(Callable<T> a,Callable<T> b) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {var x=pool.submit(()->{start.await();return a.call();});var y=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));}
        finally {pool.shutdownNow();}
    }
    @Configuration @EnableTransactionManagement
    @Import({cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,RbacAuthorizationReader.class,HistoricalRbacAssignmentWriter.class,AdminPermissionPolicy.class})
    static class Config {
        @Bean DataSource dataSource(){return ds(url);}
        @Bean DataSourceTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
        @Bean RbacCatalog catalog(){return new RbacCatalog(Set.of("audit-log:read","authorization:read","authorization:assignment-manage","authorization:root-manage","notice:read","system:read"));}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.setCacheEnabled(false);factory.setConfiguration(config);factory.setMapperLocations(new ClassPathResource("mapper/RbacMapper.xml"));return factory.getObject();
        }
        @Bean MapperFactoryBean<RbacMapper> mapper(SqlSessionFactory factory){var bean=new MapperFactoryBean<>(RbacMapper.class);bean.setSqlSessionFactory(factory);return bean;}
    }
}

