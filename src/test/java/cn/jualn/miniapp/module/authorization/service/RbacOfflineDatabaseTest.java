package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;

import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static cn.jualn.miniapp.module.authorization.service.RbacOfflineTool.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Flyway/MySQL/Mapper/Spring commits. Never uses application credentials or schema. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/rbac_offline_test")
class RbacOfflineDatabaseTest {
    static String url;
    static KeyPair first, second;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    RbacMapper mapper;
    RbacOfflineTool tool;
    HistoricalRbacAssignmentWriter governance;
    RbacAuthorizationReader reader;
    RbacCatalog catalog;

    @BeforeAll static void migrate() throws Exception {
        url = System.getProperty("event.test.jdbcUrl");
        requireSyntheticUrl(url);
        var admin = new JdbcTemplate(ds(url.substring(0,url.lastIndexOf('/')) + "/mysql"));
        admin.execute("CREATE DATABASE rbac_offline_test");
        admin.execute("CREATE DATABASE rbac_offline_upgrade");
        var flyway = Flyway.configure().dataSource(ds(url)).load(); flyway.migrate(); flyway.validate();
        String upgrade = url.replace("rbac_offline_test","rbac_offline_upgrade");
        Flyway.configure().dataSource(ds(upgrade)).target("42").load().migrate();
        var old = new JdbcTemplate(ds(upgrade));
        old.update("INSERT INTO user_profile(id,openid,role,status) VALUES(900,'offline-upgrade-synthetic',3,1)");
        old.update("INSERT INTO rbac_authorization_authority VALUES(900,'LEGACY','preserved-version',UTC_TIMESTAMP(6))");
        Map<String,Object> before = old.queryForMap("SELECT * FROM user_profile WHERE id=900");
        Flyway.configure().dataSource(ds(upgrade)).load().migrate();
        Flyway.configure().dataSource(ds(upgrade)).load().validate();
        before.put("session_version", 0L); // V78 initializes credential generation without changing prior facts.
        assertEquals(before,old.queryForMap("SELECT * FROM user_profile WHERE id=900"));
        assertEquals("preserved-version",old.queryForObject("SELECT version FROM rbac_authorization_authority WHERE user_id=900",String.class));
        assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM rbac_offline_operation",Integer.class));
        assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM rbac_role WHERE grantable=1",Integer.class));
        first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
    static DataSource ds(String connection) {
        return new DriverManagerDataSource(connection + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","root","");
    }
    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class)); mapper = context.getBean(RbacMapper.class);
        governance = context.getBean(HistoricalRbacAssignmentWriter.class); reader = context.getBean(RbacAuthorizationReader.class);
        catalog = context.getBean(RbacCatalog.class);
        tool = RbacOfflineTool.create(url,context.getBean(DataSource.class),context.getBean(DataSourceTransactionManager.class),catalog);
        jdbc.execute("DROP TABLE IF EXISTS rbac_offline_test_guard");
        jdbc.execute("CREATE TABLE rbac_offline_test_guard(id INT PRIMARY KEY,environment_id VARCHAR(96),executor_ref VARCHAR(128),approver_one VARCHAR(128),key_one_sha256 CHAR(64),approver_two VARCHAR(128),key_two_sha256 CHAR(64))");
        jdbc.update("INSERT INTO rbac_offline_test_guard VALUES(1,'synthetic-p0','synthetic-executor','approver-one',?,'approver-two',?)",RbacCatalog.hash(first.getPublic().getEncoded()),RbacCatalog.hash(second.getPublic().getEncoded()));
        for (String table : List.of("rbac_offline_effect","rbac_offline_operation","rbac_authorization_change","rbac_user_role_assignment","rbac_authorization_authority","user_profile")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE rbac_role SET lifecycle='REGISTERED',readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED'");
        try (var input = new ClassPathResource("rbac/rbac-v1.json").getInputStream()) {
            for (var p : RbacJson.mapper().readTree(input).get("permissions")) jdbc.update(
                    "UPDATE rbac_permission_definition SET lifecycle=?,readiness='NOT_READY',grantable=0,unavailable_reason=? WHERE code=?",
                    p.get("lifecycle").asText(),p.get("unavailableReason").asText(),p.get("code").asText());
        }
        // Explicit isolated release plan; production constructor remains empty.
        enable("ROOT"); enable("NOTICE_MONITOR");
        for (long id=1; id<=8; id++) {
            jdbc.update("INSERT INTO user_profile(id,openid,role,status) VALUES(?,?,?,1)",id,"offline-synthetic-"+id,id==3?3:1);
            jdbc.update("INSERT INTO rbac_authorization_authority VALUES(?,'LEGACY',?,UTC_TIMESTAMP(6))",id,UUID.randomUUID().toString());
        }
    }
    @AfterEach void close() { if (context != null) context.close(); }
    void enable(String role) {
        jdbc.update("UPDATE rbac_permission_definition p JOIN rbac_role_permission m ON p.code=m.permission_code SET p.lifecycle='ENABLED',p.readiness='READY',p.grantable=1,p.unavailable_reason=NULL WHERE m.role_code=?",role);
        jdbc.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=?",role);
    }
    Request request(Kind kind, Long sponsor, Long... users) {
        var inspection = tool.inspect(Arrays.asList(users));
        return new Request(1,UUID.randomUUID().toString(),inspection.environmentId(),kind,"synthetic-executor",
                "synthetic-approval/evidence",RbacCatalog.hash("synthetic-approved-evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Instant.now().plusSeconds(600).toString(),inspection.catalogVersion(),sponsor,
                sponsor == null ? null : mapper.subject(sponsor).getVersion(),inspection.targets());
    }
    SignedRequest signed(Request request) throws Exception {
        return new SignedRequest(request,List.of(approve(request,"approver-one",first),approve(request,"approver-two",second)));
    }
    Approval approve(Request request,String name,KeyPair pair) throws Exception {
        Signature s = Signature.getInstance("Ed25519"); s.initSign(pair.getPrivate()); s.update(signingBytes(request));
        return new Approval(name,Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),Base64.getEncoder().encodeToString(s.sign()));
    }
    Result bootstrap() throws Exception { return tool.execute(signed(request(Kind.BOOTSTRAP,null,1L,2L))); }
    void switchUser(long id) throws Exception { tool.execute(signed(request(Kind.SOURCE_SWITCH,1L,id))); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table,Integer.class); }
    Command grant(long actor,long target,String role,String namespace) {
        return new Command(actor,target,UUID.randomUUID().toString(),"synthetic normal governance",mapper.subject(target).getVersion(),null,null,role,namespace,"GLOBAL",null);
    }
    void revoke(long actor,Assignment a) {
        governance.revoke(new Command(actor,a.getUserId(),UUID.randomUUID().toString(),"synthetic revoked",mapper.subject(a.getUserId()).getVersion(),
                a.getId(),a.getVersion(),null,null,null,null));
    }
    @Test void zeroRootDryRunAndTwoIndependentRootsHaveRealAuditReferences() throws Exception {
        Request r=request(Kind.BOOTSTRAP,null,1L,2L);
        assertEquals(2,tool.dryRun(r).before().size());
        assertEquals(0,count("rbac_offline_operation")); assertEquals(0,count("rbac_user_role_assignment"));
        Result result=tool.execute(signed(r));
        assertEquals(2,result.effects().size()); assertEquals(2,count("rbac_offline_effect"));
        assertEquals(0,count("rbac_authorization_change"));
        for (long user : List.of(1L,2L)) {
            var d=reader.read(user); assertEquals(Source.ASSIGNMENT,d.source());
            assertEquals(4,d.grants().get(0).permissionCodes().size()); assertFalse(d.allows("notice:read","NOTIFICATION"));
            Assignment a=mapper.assignments(user).get(0); assertNull(a.getExpiresAt());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_offline_effect WHERE id=? AND assignment_id=? AND user_id=?",Integer.class,a.getGrantChangeId(),a.getId(),user));
            assertEquals(1,mapper.subject(user).getLegacyRole());
        }
        assertFalse(new RbacAuthorizationReader(mapper,new RbacCatalog(Set.of()),new AdminPermissionPolicy()).readCurrent(1,mapper.now()).canUseAdmin());
    }
    @Test void repeatRestoresFixedReceiptWithoutReactingToLaterStateAndConflictRejects() throws Exception {
        Request r=request(Kind.BOOTSTRAP,null,1L,2L); var s=signed(r); Result original=tool.execute(s);
        revoke(2,mapper.assignments(1).get(0));
        assertEquals(encode(original),encode(tool.execute(s)));
        assertFalse(reader.read(1).canUseAdmin()); assertEquals(1,count("rbac_offline_operation"));
        var changed=new Request(1,r.operationId(),r.environmentId(),r.kind(),r.executorRef(),"different approval",r.approvalSha256(),r.approvedUntil(),r.expectedCatalogVersion(),null,null,r.targets());
        var other=signed(changed); assertThrows(IllegalStateException.class,()->tool.execute(other));
        assertEquals(2,count("rbac_user_role_assignment"));
    }
    @Test void concurrentFirstBootstrapHasOnlyOneCommittedBatch() throws Exception {
        var a=signed(request(Kind.BOOTSTRAP,null,1L,2L)); var b=signed(request(Kind.BOOTSTRAP,null,4L,5L));
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var x=pool.submit(()->{start.await();return outcome(a);}); var y=pool.submit(()->{start.await();return outcome(b);}); start.countDown();
            assertEquals(1,List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS)).stream().filter(Boolean::booleanValue).count());
        } finally { pool.shutdownNow(); }
        assertEquals(1,count("rbac_offline_operation")); assertEquals(2,count("rbac_offline_effect")); assertEquals(2,count("rbac_user_role_assignment"));
    }
    boolean outcome(SignedRequest r) { try { tool.execute(r); return true; } catch (IllegalStateException e) { return false; } }
    @Test void secondEffectStorageFailureRollsBackBothAuthoritiesAssignmentsAndFacts() throws Exception {
        var original=tool.inspect(List.of(1L,2L)); var r=signed(request(Kind.BOOTSTRAP,null,1L,2L));
        jdbc.execute("CREATE TRIGGER offline_effect_failure BEFORE INSERT ON rbac_offline_effect FOR EACH ROW BEGIN IF NEW.user_id=2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit failure'; END IF; END");
        try { assertThrows(RuntimeException.class,()->tool.execute(r)); }
        finally { jdbc.execute("DROP TRIGGER offline_effect_failure"); }
        assertEquals(encode(original),encode(tool.inspect(List.of(1L,2L))));
        assertEquals(0,count("rbac_offline_operation")); assertEquals(0,count("rbac_offline_effect")); assertEquals(0,count("rbac_user_role_assignment"));
    }
    @Test void committedMysqlTransactionWithLostJdbcAcknowledgementIsReconciled() throws Exception {
        DataSource base=context.getBean(DataSource.class); AtomicBoolean lose=new AtomicBoolean(true);
        DataSource uncertain=new AbstractDataSource() {
            public Connection getConnection() throws SQLException { return wrap(base.getConnection()); }
            public Connection getConnection(String u,String p) throws SQLException { return wrap(base.getConnection(u,p)); }
            Connection wrap(Connection original) {
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    try {
                        Object result=method.invoke(original,args);
                        if (method.getName().equals("commit") && lose.getAndSet(false)) throw new SQLException("synthetic lost commit acknowledgement");
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
        var unstable=RbacOfflineTool.create(url,uncertain,new DataSourceTransactionManager(uncertain),catalog);
        var r=signed(request(Kind.BOOTSTRAP,null,1L,2L));
        assertThrows(RuntimeException.class,()->unstable.execute(r));
        assertEquals(1,count("rbac_offline_operation"));
        Result recovered=tool.execute(r); assertEquals(r.request().operationId(),recovered.operationId());
        assertEquals(2,count("rbac_user_role_assignment")); assertEquals(2,count("rbac_offline_effect"));
    }
    @Test void currentBanDeletionAndAuthorityOrAccountChangesRejectApprovedCommands() throws Exception {
        for (String mutation : List.of("status=3","deleted_at=UTC_TIMESTAMP(6)","role=2")) {
            var r=signed(request(Kind.BOOTSTRAP,null,1L,2L));
            jdbc.update("UPDATE user_profile SET " + mutation + " WHERE id=1");
            assertThrows(IllegalStateException.class,()->tool.execute(r));
            jdbc.update("UPDATE user_profile SET status=1,deleted_at=NULL,role=1 WHERE id=1");
        }
        var stale=signed(request(Kind.BOOTSTRAP,null,1L,2L));
        jdbc.update("UPDATE rbac_authorization_authority SET version=UUID() WHERE user_id=2");
        assertThrows(IllegalStateException.class,()->tool.execute(stale)); assertEquals(0,count("rbac_offline_operation"));
    }
    @Test void shadowCannotBeActivatedEvenIfAReadyRoleWouldAllowIt() throws Exception {
        var r=signed(request(Kind.BOOTSTRAP,null,1L,2L));
        jdbc.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(UUID(),1,'ROOT','AUTHORIZATION','GLOBAL',UTC_TIMESTAMP(6),UUID(),UUID())");
        assertThrows(IllegalStateException.class,()->tool.execute(r));
        assertEquals("LEGACY",mapper.subject(1).getSource()); assertEquals(0,count("rbac_offline_operation"));
    }
    @Test void sourceSwitchDoesNotGrantWildcardAndNormalGovernanceRemainsSeparate() throws Exception {
        bootstrap(); assertEquals(List.of("*"),reader.read(3).legacyPermissions());
        Request r=request(Kind.SOURCE_SWITCH,1L,3L); var signed=signed(r);
        Result result=tool.execute(signed); assertNull(result.effects().get(0).assignmentId());
        assertEquals(encode(result),encode(tool.execute(signed)));
        Decision d=reader.read(3); assertTrue(d.legacyPermissions().isEmpty()); assertFalse(d.canUseAdmin()); assertEquals(3,mapper.subject(3).getLegacyRole());
        assertThrows(BusinessException.class,()->new CurrentAuthorization(reader).require(3,"system:read","SYSTEM","system:read"));
        var grant=governance.grant(grant(1,3,"NOTICE_MONITOR","NOTIFICATION"));
        assertTrue(reader.read(3).allows("notice:read","NOTIFICATION")); assertFalse(reader.read(3).allows("system:read","SYSTEM"));
        assertEquals(1,count("rbac_authorization_change"));
        assertEquals(grant.changeId(),mapper.assignments(3).get(0).getGrantChangeId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change WHERE id=?",Integer.class,grant.changeId()));
    }
    @Test void recoveryOnlyAddsNewFactsAndNeverUnrevokesOldRoot() throws Exception {
        bootstrap(); switchUser(3);
        var old=governance.grant(grant(1,3,"ROOT","AUTHORIZATION")); revoke(1,old.assignment());
        var revoked=mapper.assignments(3).get(0); String before=encode(revoked);
        jdbc.update("UPDATE user_profile SET status=3 WHERE id IN (1,2)");
        var r=signed(request(Kind.RECOVERY,null,3L,4L)); var result=tool.execute(r);
        assertEquals(encode(result),encode(tool.execute(r)));
        assertEquals(before,encode(mapper.assignments(3).stream().filter(a->a.getId().equals(revoked.getId())).findFirst().orElseThrow()));
        assertEquals(2,mapper.assignments(3).size()); assertTrue(reader.read(3).canUseAdmin());
        assertTrue(mapper.assignments(3).stream().anyMatch(a->a.getRevokedAt()==null && !a.getId().equals(revoked.getId())));
        assertEquals(2,count("rbac_authorization_change"));
        var page=context.getBean(RbacQueryService.class).changes(3,new RbacQueryService.AuditQuery(null,null,null,null,null,null,100),null);
        assertEquals(2,page.items().size()); assertTrue(page.items().stream().allMatch(a->Set.of("GRANT","REVOKE").contains(a.change().getAction())));
    }
    @Test void recoveryCannotBypassExistingRootOrDisabledProvider() throws Exception {
        bootstrap(); var r=signed(request(Kind.RECOVERY,null,3L,4L));
        assertThrows(IllegalStateException.class,()->tool.execute(r));
        jdbc.update("UPDATE user_profile SET status=3 WHERE id IN (1,2)");
        jdbc.update("UPDATE rbac_permission_definition SET lifecycle='DISABLED',grantable=0 WHERE code='authorization:root-manage'");
        var disabled=signed(request(Kind.RECOVERY,null,3L,4L));
        assertThrows(IllegalStateException.class,()->tool.execute(disabled)); assertEquals(1,count("rbac_offline_operation"));
    }
    @Test void partialRoleOrMissingApplicationSupportCannotBootstrap() throws Exception {
        jdbc.update("UPDATE rbac_permission_definition SET readiness='NOT_READY',grantable=0 WHERE code='audit-log:read'");
        var r=signed(request(Kind.BOOTSTRAP,null,1L,2L));
        assertThrows(IllegalStateException.class,()->tool.execute(r));
        enable("ROOT");
        var noSupport=RbacOfflineTool.create(url,context.getBean(DataSource.class),context.getBean(DataSourceTransactionManager.class),new RbacCatalog(Set.of()));
        var inspected=noSupport.inspect(List.of(1L,2L));
        var unsupported=new Request(1,r.request().operationId(),"synthetic-p0",Kind.BOOTSTRAP,"synthetic-executor",r.request().approvalRef(),r.request().approvalSha256(),r.request().approvedUntil(),inspected.catalogVersion(),null,null,inspected.targets());
        var approved=signed(unsupported); assertThrows(IllegalStateException.class,()->noSupport.execute(approved));
        assertEquals(0,count("rbac_user_role_assignment"));
    }
    @Test void trustedSignaturesEnvironmentAndExplicitExecutionBoundaryAreRequired() throws Exception {
        Request r=request(Kind.BOOTSTRAP,null,1L,2L); var correct=signed(r);
        assertThrows(IllegalStateException.class,()->tool.execute(new SignedRequest(r,List.of(correct.approvals().get(0),correct.approvals().get(0)))));
        KeyPair unknown=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var untrusted=new SignedRequest(r,List.of(approve(r,"approver-one",unknown),correct.approvals().get(1)));
        assertThrows(IllegalStateException.class,()->tool.execute(untrusted));
        var altered=new Request(1,r.operationId(),r.environmentId(),r.kind(),r.executorRef(),"altered evidence",r.approvalSha256(),r.approvedUntil(),r.expectedCatalogVersion(),null,null,r.targets());
        assertThrows(IllegalStateException.class,()->tool.execute(new SignedRequest(altered,correct.approvals())));
        jdbc.update("UPDATE rbac_offline_test_guard SET environment_id='synthetic-another' WHERE id=1");
        assertThrows(IllegalStateException.class,()->tool.execute(correct));
        jdbc.execute("DROP TABLE rbac_offline_test_guard");
        assertThrows(RuntimeException.class,()->tool.execute(correct));
        assertThrows(IllegalArgumentException.class,()->RbacOfflineTool.connect("jdbc:mysql://production.example:3306/campus","x","x"));
        assertThrows(IllegalArgumentException.class,()->requireSyntheticUrl("jdbc:mysql://127.0.0.1:3306/campus"));
        assertEquals(0,count("rbac_offline_operation"));
    }
    @Test void standaloneCliDefaultsToDryRunAndRequiresExplicitExecute() throws Exception {
        Path request=Files.createTempFile(Path.of("target"),"offline-synthetic-command-",".json");
        Path output=Files.createTempFile(Path.of("target"),"offline-synthetic-cli-",".log");
        try {
            Files.writeString(request,encode(signed(request(Kind.BOOTSTRAP,null,1L,2L))));
            runCli(output,url,request.toAbsolutePath().toString());
            assertEquals(0,count("rbac_offline_operation")); assertEquals(0,count("rbac_user_role_assignment"));
            runCli(output,url,request.toAbsolutePath().toString(),"--execute");
            assertEquals(1,count("rbac_offline_operation")); assertEquals(2,count("rbac_offline_effect"));
            runCli(output,url,request.toAbsolutePath().toString(),"--execute"); assertEquals(2,count("rbac_user_role_assignment"));
            runCli(output,url,"inspect","1,2");
            assertTrue(Files.readString(output).contains("ASSIGNMENT"));
            Files.copy(output,Path.of("target/rbac-offline-cli-evidence.log"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(request); Files.deleteIfExists(output); }
    }
    void runCli(Path output,String... arguments) throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),RbacOfflineTool.class.getName()));
        command.addAll(List.of(arguments));
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        process.environment().put("RBAC_OFFLINE_DB_USER","root"); process.environment().put("RBAC_OFFLINE_DB_PASSWORD","");
        var child=process.start();
        try { assertTrue(child.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,child.exitValue(),Files.readString(output)); }
        finally { if(child.isAlive()) child.destroyForcibly(); }
    }
    @Test void sourceSwitchRejectsStaleSponsorMissingAuthorityAndShadow() throws Exception {
        bootstrap(); var stale=signed(request(Kind.SOURCE_SWITCH,1L,3L));
        jdbc.update("UPDATE rbac_authorization_authority SET version=UUID() WHERE user_id=1");
        assertThrows(IllegalStateException.class,()->tool.execute(stale));
        var missing=signed(request(Kind.SOURCE_SWITCH,1L,3L));
        jdbc.update("DELETE FROM rbac_authorization_authority WHERE user_id=8");
        assertThrows(IllegalStateException.class,()->tool.execute(missing));
        jdbc.update("INSERT INTO rbac_authorization_authority VALUES(8,'LEGACY',UUID(),UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(UUID(),3,'NOTICE_MONITOR','NOTIFICATION','GLOBAL',UTC_TIMESTAMP(6),UUID(),UUID())");
        var shadow=signed(request(Kind.SOURCE_SWITCH,1L,3L));
        assertThrows(IllegalStateException.class,()->tool.execute(shadow)); assertEquals("LEGACY",mapper.subject(3).getSource());
        revoke(2,mapper.assignments(1).get(0));
        var noLongerRoot=signed(request(Kind.SOURCE_SWITCH,1L,4L));
        assertThrows(IllegalStateException.class,()->tool.execute(noLongerRoot));
    }
    @Test void incompleteFactCannotBeReturnedOrReexecuted() throws Exception {
        var r=signed(request(Kind.BOOTSTRAP,null,1L,2L)); tool.execute(r);
        jdbc.update("DELETE FROM rbac_offline_effect WHERE user_id=2");
        assertThrows(IllegalStateException.class,()->tool.execute(r));
        assertEquals(2,count("rbac_user_role_assignment")); assertEquals(1,count("rbac_offline_operation"));
    }
    @Test void readerAndHistoricalQueriesRejectMissingEffectWithoutRejectingOrdinaryGrant() throws Exception {
        bootstrap(); switchUser(3);
        governance.grant(grant(2,3,"NOTICE_MONITOR","NOTIFICATION"));
        Assignment root=mapper.assignments(1).get(0);
        var queries=context.getBean(RbacQueryService.class);
        revoke(2,root);
        assertNotNull(queries.assignment(2,1,root.getId()).items().get(0).getRevokedAt());
        jdbc.update("DELETE FROM rbac_offline_effect WHERE assignment_id=?",root.getId());
        assertTrue(reader.read(3).allows("notice:read","NOTIFICATION"));
        assertEquals("/problems/authorization-source-unavailable",assertThrows(
                cn.jualn.miniapp.common.exception.ContractProblemException.class,
                ()->queries.assignment(2,1,root.getId())).getType());
        assertEquals("/problems/authorization-source-unavailable",assertThrows(
                cn.jualn.miniapp.common.exception.ContractProblemException.class,
                ()->queries.assignments(2,1,new RbacQueryService.AssignmentQuery(null,null,null,100),null)).getType());
        assertFalse(reader.read(1).canUseAdmin()); // Revoked ROOT does not regain legacy permissions.
    }
    @Test void readerRejectsEffectWhoseOperationIsMissing() throws Exception {
        bootstrap(); Assignment root=mapper.assignments(1).get(0);
        String operation=jdbc.queryForObject("SELECT operation_id FROM rbac_offline_effect WHERE assignment_id=?",String.class,root.getId());
        // Corruption injection is confined to one connection in this disposable synthetic schema.
        try(var connection=context.getBean(DataSource.class).getConnection(); var statement=connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            try(var update=connection.prepareStatement("UPDATE rbac_offline_effect SET operation_id=? WHERE assignment_id=?")) {
                update.setString(1,UUID.randomUUID().toString());update.setString(2,root.getId());update.executeUpdate();
                try {
                    assertEquals("/problems/authorization-source-unavailable",assertThrows(
                            cn.jualn.miniapp.common.exception.ContractProblemException.class,()->reader.read(1)).getType());
                } finally {update.setString(1,operation);update.executeUpdate();}
            } finally {statement.execute("SET FOREIGN_KEY_CHECKS=1");}
        }
        assertTrue(reader.read(1).allows("authorization:root-manage","AUTHORIZATION"));
    }
    @Test void completeOrdinaryChangeMustNotHideConflictingOfflineReceipt() throws Exception {
        bootstrap(); switchUser(3);
        governance.grant(grant(2,3,"NOTICE_MONITOR","NOTIFICATION"));
        Assignment ordinary=mapper.assignments(3).get(0);
        assertEquals("GRANT",jdbc.queryForObject("SELECT action FROM rbac_authorization_change WHERE id=? AND assignment_id=? AND target_user_id=3",String.class,ordinary.getGrantChangeId(),ordinary.getId()));
        String operation=jdbc.queryForObject("SELECT operation_id FROM rbac_offline_effect WHERE user_id=1",String.class);
        String original=jdbc.queryForObject("SELECT receipt FROM rbac_offline_operation WHERE id=?",String.class,operation);
        var receipt=(com.fasterxml.jackson.databind.node.ObjectNode)RbacJson.mapper().readTree(original);
        ((com.fasterxml.jackson.databind.node.ArrayNode)receipt.get("effects")).addObject().put("assignmentId",ordinary.getId());
        jdbc.update("UPDATE rbac_offline_operation SET receipt=? WHERE id=?",encode(receipt),operation);
        try {
            assertUnavailable(()->reader.read(3));
            ((com.fasterxml.jackson.databind.node.ObjectNode)receipt.path("effects").get(2)).put("assignmentId",ordinary.getId().toUpperCase(java.util.Locale.ROOT));
            jdbc.update("UPDATE rbac_offline_operation SET receipt=? WHERE id=?",encode(receipt),operation);
            assertUnavailable(()->reader.read(3));
        }
        finally {jdbc.update("UPDATE rbac_offline_operation SET receipt=? WHERE id=?",original,operation);}
        assertTrue(reader.read(3).allows("notice:read","NOTIFICATION"));
    }
    @Test void mismatchedEffectAndCrossTypedReferenceCannotAuthorize() throws Exception {
        bootstrap();Assignment first=mapper.assignments(1).get(0),second=mapper.assignments(2).get(0);
        jdbc.update("UPDATE rbac_offline_effect SET assignment_id=NULL WHERE id=?",first.getGrantChangeId());
        try {assertUnavailable(()->reader.read(1));}
        finally {jdbc.update("UPDATE rbac_offline_effect SET assignment_id=? WHERE id=?",first.getId(),first.getGrantChangeId());}
        jdbc.update("UPDATE rbac_user_role_assignment SET grant_change_id=? WHERE id=?",second.getGrantChangeId(),first.getId());
        try {assertUnavailable(()->reader.read(1));}
        finally {jdbc.update("UPDATE rbac_user_role_assignment SET grant_change_id=? WHERE id=?",first.getGrantChangeId(),first.getId());}
        assertTrue(reader.read(1).allows("authorization:read","AUTHORIZATION"));
    }
    @Test void multipleNamespacesPaginationAndChangedVersionsKeepCurrentSemantics() throws Exception {
        bootstrap();switchUser(3);var queries=context.getBean(RbacQueryService.class);
        governance.grant(grant(2,3,"NOTICE_MONITOR","NOTIFICATION"));
        governance.grant(grant(2,3,"NOTICE_MONITOR","SYSTEM"));
        var q=new RbacQueryService.AssignmentQuery(null,null,null,1);
        var first=queries.assignments(2,3,q,null);assertNotNull(first.nextCursor());
        var next=queries.assignments(2,3,q,first.nextCursor());assertNull(next.nextCursor());
        assertNotEquals(first.items().get(0).getId(),next.items().get(0).getId());
        Assignment notification=mapper.assignments(3).stream().filter(a->"NOTIFICATION".equals(a.getNamespace())).findFirst().orElseThrow();
        revoke(2,notification);
        assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->queries.assignments(2,3,q,first.nextCursor()));
        var current=reader.read(3);assertFalse(current.allows("notice:read","NOTIFICATION"));assertTrue(current.allows("system:read","SYSTEM"));
        assertNotNull(queries.assignment(2,3,notification.getId()).items().get(0).getRevokedAt());
    }
    private void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        assertEquals("/problems/authorization-source-unavailable",assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,action).getType());
    }
    @Test void mixedHistoricalAndCurrentOfflineProofsStillRejectMissingOldEffect() throws Exception {
        bootstrap();Assignment original=mapper.assignments(1).get(0);revoke(2,original);
        jdbc.update("UPDATE user_profile SET status=2,ban_expire_at=NULL WHERE id=2");
        tool.execute(signed(request(Kind.RECOVERY,null,1L,4L)));
        var queries=context.getBean(RbacQueryService.class);var q=new RbacQueryService.AssignmentQuery(null,null,null,100);
        assertEquals(2,queries.assignments(4,1,q,null).items().size());
        jdbc.update("DELETE FROM rbac_offline_effect WHERE id=?",original.getGrantChangeId());
        assertTrue(reader.read(1).allows("authorization:read","AUTHORIZATION"));
        assertUnavailable(()->queries.assignments(4,1,q,null));
    }
    @Test void concurrentSameBatchReturnsOneFixedResult() throws Exception {
        var r=signed(request(Kind.BOOTSTRAP,null,1L,2L));
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();return encode(tool.execute(r));});
            var second=pool.submit(()->{start.await();return encode(tool.execute(r));}); start.countDown();
            assertEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(1,count("rbac_offline_operation")); assertEquals(2,count("rbac_offline_effect"));
    }

    @Configuration @EnableTransactionManagement
    @Import({cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,RbacAuthorizationReader.class,HistoricalRbacAssignmentWriter.class,RbacQueryService.class,RbacCursorCodec.class,AdminPermissionPolicy.class})
    static class Config {
        @Bean DataSource dataSource() { return ds(url); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean RbacCatalog catalog() { return new RbacCatalog(SYNTHETIC_SUPPORT); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.setCacheEnabled(false);
            factory.setConfiguration(config); factory.setMapperLocations(new ClassPathResource("mapper/RbacMapper.xml")); return factory.getObject();
        }
        @Bean MapperFactoryBean<RbacMapper> mapper(SqlSessionFactory factory) { var bean=new MapperFactoryBean<>(RbacMapper.class); bean.setSqlSessionFactory(factory); return bean; }
    }
}
