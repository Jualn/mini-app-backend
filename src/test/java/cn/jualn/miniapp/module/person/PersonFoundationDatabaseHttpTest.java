package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.web.RbacExceptionHandler;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.support.RbacTime;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.web.UnifiedAuthorizationController;
import cn.jualn.miniapp.module.authorization.service.UnifiedAuthorizationService;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyCatalog;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.authorization.mapper.UnifiedMapper;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonIdentifierService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonAccess;
import cn.jualn.miniapp.module.person.service.profile.PersonIdentifierNamespaces;
import cn.jualn.miniapp.module.person.service.binding.ManagedPersonBindingService;
import cn.jualn.miniapp.module.person.service.binding.PersonBindingTransitions;
import cn.jualn.miniapp.module.person.service.roster.PartyRosterService;
import cn.jualn.miniapp.module.person.service.roster.RosterAccess;
import cn.jualn.miniapp.module.person.service.roster.RosterUploadService;
import cn.jualn.miniapp.module.person.service.roster.RosterUploadCommands;
import cn.jualn.miniapp.module.person.service.roster.RosterWorkbook;
import cn.jualn.miniapp.module.person.service.roster.RosterApplicationThrottle;
import cn.jualn.miniapp.module.person.service.qualification.MentorManagementService;
import cn.jualn.miniapp.module.person.service.qualification.PersistentMentorQualification;
import cn.jualn.miniapp.module.person.service.support.ManagedPersonJournal;
import cn.jualn.miniapp.module.person.service.support.PersonnelJournal;
import cn.jualn.miniapp.module.person.service.support.PersonPrivateData;
import cn.jualn.miniapp.module.person.service.bootstrap.PersonInitialAuthority;

import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.person.bo.PersonBO.*;
import cn.jualn.miniapp.module.person.bo.PersonBO.Scope;
import cn.jualn.miniapp.module.person.controller.*;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL, proxies and loopback HTTP. Session/capability fixture is EXPLICIT TEST DOUBLE.
 * TestCatalog can approve synthetic stages solely to exercise generic lifecycle storage. */
@EnabledIfSystemProperty(named="person.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/person_foundation_test_[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonFoundationDatabaseHttpTest {
    ConfigurableApplicationContext context;
    JdbcTemplate db;
    PersonFoundationService service;
    PersonFactReaders readers;
    PersonMapper mapper;
    TestAuthentication auth;
    TestCatalog catalog;
    PersonInitialAuthority bootstrap;
    TransactionTemplate tx;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    List<Map<String,Object>> httpEvidence=new ArrayList<>();
    List<Map<String,Object>> readerEvidence=new ArrayList<>();
    String url,base;
    long sequence=1000;
    static DataSource source(String url) { return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",System.getenv().getOrDefault("MANAGED_PERSON_TEST_DB_USER","root"),System.getenv().getOrDefault("MANAGED_PERSON_TEST_DB_PASSWORD","")); }
    @BeforeAll void start() throws Exception {
        System.setProperty("people-qualification.encryption-key",Base64.getEncoder().encodeToString(new byte[32]));
        var matching=new byte[32];Arrays.fill(matching,(byte)1);System.setProperty("people-qualification.match-key",Base64.getEncoder().encodeToString(matching));
        System.setProperty("people-qualification.student-number-namespace","SYNTHETIC_CAMPUS");System.setProperty("people-qualification.student-number-display-name","Synthetic test namespace");
        url=System.getProperty("person.test.jdbcUrl");String name=url.substring(url.lastIndexOf('/')+1),upgrade=name.replace("_test_","_upgrade_");
        var admin=new JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        admin.execute("CREATE DATABASE "+name);admin.execute("CREATE DATABASE "+upgrade);
        var fresh=Flyway.configure().dataSource(source(url)).load();fresh.migrate();fresh.validate();
        String old=url.replace(name,upgrade);Flyway.configure().dataSource(source(old)).target("47").load().migrate();
        var prior=new JdbcTemplate(source(old));prior.update("INSERT INTO user_profile(id,openid,nickname) VALUES(777,'person-upgrade-sentinel','preserved')");
        prior.update("INSERT INTO point_application(applicant_user_id,title,description,file_ids,status,created_at) VALUES(777,'preserve','preserve',JSON_ARRAY('sentinel'),'PENDING',UTC_TIMESTAMP(6))");
        String before=prior.queryForObject("SELECT SHA2(CAST(JSON_ARRAY(id,applicant_user_id,title,description,file_ids,status,revision,created_at) AS CHAR),256) FROM point_application",String.class);
        var upgradeFlyway=Flyway.configure().dataSource(source(old)).load();
        assertThrows(org.flywaydb.core.api.FlywayException.class,upgradeFlyway::migrate);
        assertEquals(before,prior.queryForObject("SELECT SHA2(CAST(JSON_ARRAY(id,applicant_user_id,title,description,file_ids,status,revision,created_at) AS CHAR),256) FROM point_application",String.class));
        assertEquals("preserved",prior.queryForObject("SELECT nickname FROM user_profile WHERE id=777",String.class));
        prior.update("DELETE FROM point_application WHERE applicant_user_id=777");
        upgradeFlyway.repair();upgradeFlyway.migrate();upgradeFlyway.validate();
        assertEquals(0,prior.queryForObject("SELECT COUNT(*) FROM person_fact_current",Integer.class));
        context=SpringApplication.run(Config.class,"--spring.profiles.active=dev","--spring.datasource.url="+url,"--server.address=127.0.0.1","--server.port=0","--spring.config.location=optional:classpath:/person-no-config.yml","--spring.main.banner-mode=off","--logging.level.root=WARN");
        db=context.getBean(JdbcTemplate.class);service=context.getBean(PersonFoundationService.class);readers=context.getBean(PersonFactReaders.class);mapper=context.getBean(PersonMapper.class);auth=context.getBean(TestAuthentication.class);catalog=context.getBean(TestCatalog.class);
        tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(10);
        bootstrap=new PersonInitialAuthority(url,source(url));
        base="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
        for(long id:new long[]{1,2,3,9,10}) addUser(id);
        db.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT' WHERE user_id IN (1,2,3)");
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code IN ('authorization:read','authorization:assignment-manage','authorization:root-manage','audit-log:read')");
        db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code='ROOT'");
        String assignment=key(),change=key(),version=key();
        db.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(?,1,'ROOT','AUTHORIZATION','GLOBAL',UTC_TIMESTAMP(6),?,?)",assignment,version,change);
        var assigned=context.getBean(RbacMapper.class).assignments(1).get(0);String operation=key();
        var rootReceipt=new RbacModels.Receipt(change,operation,"GRANT",assigned.getGrantedAt(),context.getBean(RbacMapper.class).subject(1).getVersion(),assigned);
        db.update("INSERT INTO rbac_authorization_change(id,operation_id,actor_user_id,target_user_id,assignment_id,action,idempotency_key,request_fingerprint,reason,snapshot_version,after_snapshot,receipt,occurred_at) VALUES(?,?,2,1,?,'GRANT',?,REPEAT('a',64),'synthetic existing root authority',1,?,?,?)",change,operation,assignment,key(),json.writeValueAsString(assigned),json.writeValueAsString(rootReceipt),assigned.getGrantedAt());
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        assertEquals("+00:00",db.queryForObject("SELECT @@session.time_zone",String.class));
    }
    @AfterAll void stop() throws Exception {
        if(context!=null) context.close();Files.createDirectories(Path.of("target/person-foundation"));json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/person-foundation/http.json").toFile(),httpEvidence);
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/person-foundation/readers.json").toFile(),readerEvidence);
    }
    @BeforeEach void reset() { auth.denied.clear();auth.onRequire=null;catalog.confirmed=false; }
    long unifiedUser() {long user=user();db.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT' WHERE user_id=?",user);return user;}
    void enableUnifiedPerson() {
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code IN ('identity:read','identity:map-manage','organization:read','organization:map-manage')");
        db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='PERSON_FACT_STEWARD_V1'");
    }
    String unifiedExpiry() {return RbacTime.wire(LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0));}
    cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection unifiedSelection(String organization) {
        return new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection("ORG_MEMBER_MAINTAIN",new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,organization),unifiedExpiry(),null);
    }
    @Test void unifiedAllSevenHttpOperationsExposeRealBoundedDelegationAndAtomicDutyEffects() throws Exception {
        enableUnifiedPerson();long manager=unifiedUser(),staff=unifiedUser();String organization=org();var selection=unifiedSelection(organization);
        var options=http("GET","/v1/admin/authorization/me/duty-options",1,null,null,null,200,"DutyOptions");
        assertTrue(options.body.get("canViewAdvancedGovernance").asBoolean());
        String authorityBody=json.writeValueAsString(new cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityRequest(Long.toString(manager),List.of(selection),"INDEPENDENT_APPROVAL",selection.expiresAt(),"explicit independent approval"));
        var authority=http("POST","/v1/admin/authorization/delegation-authorities",1,authorityBody,key(),null,200,"DelegationAuthority");
        String authorityId=authority.body.get("authorityId").asText();
        var ownOptions=http("GET","/v1/admin/authorization/me/duty-options",manager,null,null,null,200,"DutyOptions");
        assertFalse(ownOptions.body.get("canViewAdvancedGovernance").asBoolean());assertEquals(1,ownOptions.body.get("items").size());
        assertTrue(context.getBean(RbacAuthorizationReader.class).read(manager).canUseAdmin());
        assertFalse(context.getBean(RbacAuthorizationReader.class).read(manager).allows("organization:map-manage","ORGANIZATION"));
        var intent=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("GRANT",Long.toString(staff),List.of(selection),authorityId,null,null,"appoint bounded member maintainer");
        String body=json.writeValueAsString(intent);
        var preview=http("POST","/v1/admin/authorization/duty-previews",manager,body,key(),null,200,"DutyPreview");String previewId=preview.body.get("previewId").asText();
        http("GET","/v1/admin/authorization/duty-previews/"+previewId,manager,null,null,null,200,"DutyPreview");
        String commitBody=json.writeValueAsString(new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(previewId,intent));String requestKey=key();
        var committed=http("POST","/v1/admin/authorization/duty-changes",manager,commitBody,requestKey,null,200,"DutyReceipt");
        var replay=http("POST","/v1/admin/authorization/duty-changes",manager,commitBody,requestKey,null,200,"DutyReceipt");assertEquals(committed.body,replay.body);
        var effective=http("GET","/v1/admin/authorization/users/"+staff+"/effective-duties",staff,null,null,null,200,"EffectiveDuties");
        assertTrue(effective.body.get("items").get(0).get("effective").asBoolean());
        http("GET","/v1/admin/authorization/delegation-authorities",manager,null,null,null,200,"DelegationAuthorityPage");
        long target=user();var before=service.localMembership(staff,subject(target),organization);
        var joined=service.changeLocalMembership(staff,subject(target),organization,localChange(LocalMemberKind.JOIN),key(),before.etag());assertEquals("PRESENT",joined.value().presence());
        assertTrue(context.getBean(RbacAuthorizationReader.class).read(staff).allows("organization:map-manage","ORGANIZATION"));
        // Local policy remains bounded; a valid role does not authorize another organization or global replacement.
        String other=org();problem("not-found",()->service.localMembership(staff,subject(target),other));
        problem("not-found",()->service.updateOrganization(staff,organization,new OrganizationChange("unauthorized directory edit",null,null,"member duty is local only","explicit isolated evidence",null),key(),service.organization(1,organization).etag()));
        problem("not-found",()->service.memberships(staff,subject(target)));
        service.replaceMemberships(1,subject(target),members(organization),key(),service.memberships(1,subject(target)).etag());
    }
    @Test void unifiedAuthorityRevocationInvalidatesOnlyItsBranchAndPreviewShowsIndependentSource() throws Exception {
        enableUnifiedPerson();long manager=unifiedUser(),staff=unifiedUser();String organization=org();var selection=unifiedSelection(organization);
        var unified=context.getBean(UnifiedAuthorizationService.class);
        var authority=unified.grantAuthority(1,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityRequest(Long.toString(manager),List.of(selection),"INDEPENDENT_APPROVAL",selection.expiresAt(),"independent manager"),key());
        var childIntent=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("GRANT",Long.toString(staff),List.of(selection),authority.authorityId(),null,null,"dependent branch");
        var childPreview=unified.preview(manager,childIntent,key());var child=unified.commit(manager,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(childPreview.previewId(),childIntent),key());
        String root=context.getBean(RbacAuthorizationReader.class).read(1).grants().stream().filter(g->"ROOT".equals(g.roleCode())).findFirst().orElseThrow().assignmentId();
        var directIntent=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("GRANT",Long.toString(staff),List.of(selection),root,null,null,"independent root approval");
        var directPreview=unified.preview(1,directIntent,key());var direct=unified.commit(1,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(directPreview.previewId(),directIntent),key());
        assertNotEquals(child.assignmentIds(),direct.assignmentIds());
        var revoke=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("REVOKE",null,null,null,"DELEGATION_AUTHORITY",authority.authorityId(),"handover source ended");
        var preview=unified.preview(1,revoke,key());assertEquals(2,preview.impacts().size());
        assertEquals(Set.of("BECOMES_INEFFECTIVE","REMAINS_EFFECTIVE"),preview.impacts().stream().map(cn.jualn.miniapp.module.authorization.model.UnifiedModels.Impact::effect).collect(java.util.stream.Collectors.toSet()));
        unified.commit(1,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(preview.previewId(),revoke),key());
        var effective=unified.effective(staff,staff,100,null);assertEquals(1,effective.items().stream().filter(cn.jualn.miniapp.module.authorization.model.UnifiedModels.EffectiveDuty::effective).count());
        assertEquals(1,context.getBean(RbacAuthorizationReader.class).read(staff).grants().size());
        assertTrue(unified.options(manager,20,null).items().isEmpty());
    }
    @Test void unifiedCeilingsSelfGrantRedelegationAndRootProtectionCannotBeBypassed() throws Exception {
        enableUnifiedPerson();long manager=unifiedUser(),staff=unifiedUser();String a=org(),b=org();var selected=unifiedSelection(a);var unified=context.getBean(UnifiedAuthorizationService.class);
        var authority=unified.grantAuthority(1,new UnifiedModels.AuthorityRequest(Long.toString(manager),List.of(selected),"INDEPENDENT_APPROVAL",selected.expiresAt(),"bounded authority"),key());
        var cross=new UnifiedModels.Intent("GRANT",Long.toString(staff),List.of(unifiedSelection(b)),authority.authorityId(),null,null,"cross organization attempt");
        problem("forbidden",()->unified.preview(manager,cross,key()));
        var self=new UnifiedModels.Intent("GRANT",Long.toString(manager),List.of(selected),authority.authorityId(),null,null,"self grant attempt");
        problem("forbidden",()->unified.preview(manager,self,key()));
        var late=new UnifiedModels.Selection(selected.dutyCode(),selected.target(),RbacTime.wire(LocalDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0)),null);
        problem("forbidden",()->unified.preview(manager,new UnifiedModels.Intent("GRANT",Long.toString(staff),List.of(late),authority.authorityId(),null,null,"exceed expiry"),key()));
        problem("forbidden",()->unified.grantAuthority(manager,new UnifiedModels.AuthorityRequest(Long.toString(staff),List.of(selected),"INDEPENDENT_APPROVAL",selected.expiresAt(),"redelegation attempt"),key()));
        String root=context.getBean(RbacAuthorizationReader.class).read(1).grants().stream().filter(g->"ROOT".equals(g.roleCode())).findFirst().orElseThrow().assignmentId();
        problem("forbidden",()->unified.preview(1,new UnifiedModels.Intent("REVOKE",null,null,null,"ASSIGNMENT",root,"bypass ROOT guard"),key()));
        assertTrue(unified.options(staff,20,null).items().isEmpty());
    }
    @Test void unifiedSameOrganizationAppointmentsDoNotReviveAfterMembershipRejoin() throws Exception {
        enableUnifiedPerson();long manager=unifiedUser(),staff=unifiedUser();String a=org();
        grant(9,1,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        service.replaceMemberships(9,subject(1),members(a),key(),service.memberships(9,subject(1)).etag());
        service.replaceMemberships(1,subject(manager),members(a),key(),service.memberships(1,subject(manager)).etag());
        service.replaceMemberships(1,subject(staff),members(a),key(),service.memberships(1,subject(staff)).etag());
        var selected=unifiedSelection(a);var unified=context.getBean(UnifiedAuthorizationService.class);
        var authority=unified.grantAuthority(1,new UnifiedModels.AuthorityRequest(Long.toString(manager),List.of(selected),"SAME_ORGANIZATION_MEMBERS",selected.expiresAt(),"same organization appointment"),key());
        var intent=new UnifiedModels.Intent("GRANT",Long.toString(staff),List.of(selected),authority.authorityId(),null,null,"member-bound appointment");
        var preview=unified.preview(manager,intent,key());unified.commit(manager,new UnifiedModels.Commit(preview.previewId(),intent),key());
        String original=service.memberships(1,subject(staff)).value().currentMemberships().get(0).membershipId();
        service.replaceMemberships(1,subject(staff),members(),key(),service.memberships(1,subject(staff)).etag());
        assertEquals("MEMBERSHIP_ENDED",unified.effective(staff,staff,20,null).items().get(0).reason());
        service.replaceMemberships(1,subject(staff),members(a),key(),service.memberships(1,subject(staff)).etag());
        assertNotEquals(original,service.memberships(1,subject(staff)).value().currentMemberships().get(0).membershipId());
        assertFalse(unified.effective(staff,staff,20,null).items().get(0).effective());
        service.replaceMemberships(1,subject(manager),members(),key(),service.memberships(1,subject(manager)).etag());
        service.replaceMemberships(1,subject(manager),members(a),key(),service.memberships(1,subject(manager)).etag());
        assertTrue(unified.options(manager,20,null).items().isEmpty());
    }
    @Test void unifiedCommitRaceAndAuditFailureKeepAssignmentScopeAndReceiptAtomic() throws Exception {
        enableUnifiedPerson();long staff=unifiedUser();String a=org();var unified=context.getBean(UnifiedAuthorizationService.class);
        String root=context.getBean(RbacAuthorizationReader.class).read(1).grants().stream().filter(g->"ROOT".equals(g.roleCode())).findFirst().orElseThrow().assignmentId();
        var intent=new UnifiedModels.Intent("GRANT",Long.toString(staff),List.of(unifiedSelection(a)),root,null,null,"atomic combination");
        var preview=unified.preview(1,intent,key());long assignments=count("rbac_user_role_assignment"),scopes=count("person_business_scope"),receipts=count("rbac_unified_command");
        db.execute("CREATE TRIGGER fail_unified_commit BEFORE INSERT ON rbac_unified_command FOR EACH ROW BEGIN IF NEW.operation='commitDutyChange' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated audit failure'; END IF; END");
        try { assertThrows(RuntimeException.class,()->unified.commit(1,new UnifiedModels.Commit(preview.previewId(),intent),key())); }
        finally {db.execute("DROP TRIGGER fail_unified_commit");}
        assertEquals(assignments,count("rbac_user_role_assignment"));assertEquals(scopes,count("person_business_scope"));assertEquals(receipts,count("rbac_unified_command"));
        String sameKey=key();var command=new UnifiedModels.Commit(preview.previewId(),intent);
        var results=race(()->unified.commit(1,command,sameKey),()->unified.commit(1,command,sameKey));
        assertTrue(results.stream().allMatch(r->r instanceof UnifiedModels.Receipt));assertEquals(results.get(0),results.get(1));
        assertEquals(assignments+1,count("rbac_user_role_assignment"));assertEquals(scopes+1,count("person_business_scope"));assertEquals(receipts+1,count("rbac_unified_command"));
    }
    final List<String> localTestScopes=new ArrayList<>();
    void localTestGrant(String organization) throws Exception { localTestScopes.add(orgGrant(9,organization,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN).scopeGrantId()); }
    @AfterEach void retireLocalTestScopes() {
        for(var id:localTestScopes) service.revoke(1,id,new Reason("local fixture complete","isolated-test"),key(),service.scope(1,id).etag());
        localTestScopes.clear();
    }
    LocalMemberChange localChange(LocalMemberKind kind) { return new LocalMemberChange(kind,"verified local fact","MANUAL_CONFIRMATION_NO_MATERIAL"); }
    @Test void localJoinDoesNotConfirmCompleteMembershipsAndCannotReachAnotherOrganization() throws Exception {
        long u=user();String a=org(),b=org();localTestGrant(a);
        var initial=service.localMembership(9,subject(u),a);assertEquals(Verification.UNKNOWN,initial.value().verification());
        String idempotency=key();var change=localChange(LocalMemberKind.JOIN);
        var joined=service.changeLocalMembership(9,subject(u),a,change,idempotency,initial.etag());
        assertEquals("PRESENT",joined.value().presence());assertNotNull(joined.value().membershipId());
        assertEquals(joined,service.changeLocalMembership(9,subject(u),a,change,idempotency,initial.etag()));
        assertEquals(Verification.UNKNOWN,service.memberships(1,subject(u)).value().verification());
        tx.execute(s->{var c=readers.open(1,u);assertEquals(PersonFactReaders.Outcome.INDETERMINATE,readers.memberships(u,c).outcome());
            var fact=readers.localMembership(u,a,c);assertEquals(PersonFactReaders.Outcome.KNOWN,fact.outcome());
            assertEquals(joined.value().membershipId(),fact.fact().membershipId());
            readers.beginFinalCheck(c);assertEquals(fact.fact(),readers.localMembershipFinal(u,a,c).fact());return null;});
        problem("not-found",()->service.localMembership(9,subject(u),b));
        problem("not-found",()->service.changeLocalMembership(9,subject(u),b,change,key(),joined.etag()));
        problem("person-self-governance-forbidden",()->service.changeLocalMembership(9,subject(9),a,change,key(),initial.etag()));
        assertEquals(0,mapper.corruptFacts());assertEquals(0,mapper.corruptSource());
        var history=service.localMembershipHistory(9,subject(u),a,1,20);assertEquals(1,history.totalItems());
        assertEquals("JOIN",history.items().get(0).operation());assertEquals(joined.value(),history.items().get(0).after());
        service.replaceMemberships(1,subject(u),members(a),key(),service.memberships(1,subject(u)).etag());
    }
    @Test void localRemovalRejoinCreatesNewCycleAndKeepsOtherOrganizationAndFullVerification() throws Exception {
        long u=user();String a=org(),b=org();localTestGrant(a);
        service.replaceMemberships(1,subject(u),members(a,b),key(),service.memberships(1,subject(u)).etag());
        var before=service.localMembership(9,subject(u),a);String original=before.value().membershipId();
        var removed=service.changeLocalMembership(9,subject(u),a,localChange(LocalMemberKind.REMOVE),key(),before.etag());
        assertEquals("ABSENT",removed.value().presence());assertNull(removed.value().membershipId());
        var full=service.memberships(1,subject(u)).value();assertEquals(Verification.VERIFIED,full.verification());
        assertEquals(List.of(b),full.currentMemberships().stream().map(Member::organizationId).toList());
        var rejoined=service.changeLocalMembership(9,subject(u),a,localChange(LocalMemberKind.JOIN),key(),removed.etag());
        assertNotEquals(original,rejoined.value().membershipId());
        problem("precondition-failed",()->service.replaceMemberships(1,subject(u),members(b),key(),removed.etag()));
        assertEquals(0,mapper.corruptFacts());assertEquals(0,mapper.corruptSource());
    }
    @Test void competingLocalWritesSharePersonVersionAndOnlyOneCommits() throws Exception {
        long u=user();String a=org();localTestGrant(a);
        var current=service.localMembership(9,subject(u),a);
        var outcomes=race(()->service.changeLocalMembership(9,subject(u),a,localChange(LocalMemberKind.JOIN),key(),current.etag()),
                ()->service.changeLocalMembership(9,subject(u),a,localChange(LocalMemberKind.VERIFY_ABSENT),key(),current.etag()));
        assertEquals(1,outcomes.stream().filter(o->o instanceof Result<?>).count());
        assertEquals(1,outcomes.stream().filter(o->o instanceof ContractProblemException).count());
        assertEquals(1,service.localMembershipHistory(9,subject(u),a,1,20).totalItems());assertEquals(0,mapper.corruptFacts());
        service.replaceMemberships(1,subject(u),members(a),key(),service.memberships(1,subject(u)).etag());
    }
    @Test void localHttpAndFinalAuthorizationFailurePreserveAtomicFactsAndReceipts() throws Exception {
        long u=user();String a=org();localTestGrant(a);
        String path="/v1/admin/organizations/"+a+"/members/"+u;
        var initial=http("GET",path,9,null,null,null,200,"LocalMembership");
        String body="{\"kind\":\"JOIN\",\"reason\":\"verified local member\"}";String intentKey=key();
        var joined=http("POST",path+"/membership-changes",9,body,intentKey,initial.etag,200,"LocalMembership");
        http("POST",path+"/membership-changes",9,body,intentKey,initial.etag,200,"LocalMembership");
        http("GET",path+"/membership-history",9,null,null,null,200,"LocalMembershipHistoryPage");
        var verified=service.changeLocalMembership(9,subject(u),a,localChange(LocalMemberKind.VERIFY_PRESENT),key(),joined.etag);
        assertEquals(joined.body.get("membershipId").asText(),verified.value().membershipId());
        problem("person-state-conflict",()->service.changeLocalMembership(9,subject(u),a,localChange(LocalMemberKind.VERIFY_ABSENT),key(),verified.etag()));
        long events=count("person_foundation_event"),receipts=count("person_command_receipt");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        auth.onRequire=()->{if(calls.incrementAndGet()==3) throw PersonProblems.denied();};
        try { problem("forbidden",()->service.changeLocalMembership(9,subject(u),a,localChange(LocalMemberKind.REMOVE),key(),verified.etag())); }
        finally { auth.onRequire=null; }
        assertEquals(events,count("person_foundation_event"));assertEquals(receipts,count("person_command_receipt"));
        assertEquals(verified.value(),service.localMembership(9,subject(u),a).value());assertEquals(0,mapper.corruptFacts());
        service.replaceMemberships(1,subject(u),members(a),key(),service.memberships(1,subject(u)).etag());
    }
    @Test void localAndWholeMembershipWritesCannotBothCommitFromTheSamePersonVersion() throws Exception {
        long u=user();String a=org(),b=org();localTestGrant(a);
        var whole=service.replaceMemberships(1,subject(u),members(a,b),key(),service.memberships(1,subject(u)).etag());
        var local=service.localMembership(9,subject(u),a);long revision=Long.parseLong(whole.value().version());
        var outcomes=race(()->service.changeLocalMembership(9,subject(u),a,localChange(LocalMemberKind.REMOVE),key(),local.etag()),
                ()->service.replaceMemberships(1,subject(u),members(b),key(),whole.etag()));
        assertEquals(1,outcomes.stream().filter(o->o instanceof Result<?>).count());
        assertEquals(1,outcomes.stream().filter(o->o instanceof ContractProblemException).count());
        var finalValue=service.memberships(1,subject(u)).value();assertEquals(revision+1,Long.parseLong(finalValue.version()));
        assertEquals(List.of(b),finalValue.currentMemberships().stream().map(Member::organizationId).toList());
        assertEquals(0,mapper.corruptFacts());assertEquals(0,mapper.corruptSource());
    }
    @Test void localEvidenceCorruptionFailsClosedInsteadOfBecomingAnAbsentMembership() throws Exception {
        long u=user();String a=org();localTestGrant(a);
        var joined=service.changeLocalMembership(9,subject(u),a,localChange(LocalMemberKind.JOIN),key(),service.localMembership(9,subject(u),a).etag());
        db.update("UPDATE person_local_membership SET verified_at=verified_at+INTERVAL 1 SECOND WHERE user_id=? AND organization_id=?",u,a);
        try { problem("person-foundation-unavailable",()->service.localMembership(9,subject(u),a)); }
        finally { db.update("UPDATE person_local_membership SET verified_at=? WHERE user_id=? AND organization_id=?",joined.value().verifiedAt(),u,a); }
        assertEquals(joined.value(),service.localMembership(9,subject(u),a).value());
        service.replaceMemberships(1,subject(u),members(a),key(),service.memberships(1,subject(u)).etag());
    }
    long user() { long id=sequence++;addUser(id);subject(id);return id; }
    String subject(String person) { return person; }
    String subject(long user) {
        var persons=context.getBean(cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper.class);
        var binding=persons.accountBinding(user);if(binding!=null) return binding.getPersonId();
        var profiles=context.getBean(ManagedPersonService.class);var links=context.getBean(ManagedPersonBindingService.class);
        var profile=profiles.createManagedPerson(1,new cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Create("Synthetic person "+user,null,"explicit fixture","synthetic source"),key());
        if(user==1) {
            String id=key();db.update("INSERT INTO person_account_binding(id,user_id,person_id,status,revision,started_at,verification_method,source_reference,started_by) VALUES(?,1,?,'ACTIVE',1,UTC_TIMESTAMP(6),'ADMIN_MANUAL','explicit synthetic self-governance fixture',2)",id,profile.value().personId());
            db.update("UPDATE person_profile SET binding_revision=binding_revision+1 WHERE id=?",profile.value().personId());db.update("INSERT INTO person_account_binding_slot(user_id,revision) VALUES(1,1)");
        } else {
            var personSlot=links.getPersonAccountBinding(1,profile.value().personId()).value().version();var accountSlot=links.getAccountPersonBinding(1,user).value().version();
            links.createUserPersonBinding(1,new cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Bind(profile.value().personId(),Long.toString(user),profile.value().version(),personSlot,accountSlot,"explicit fixture","synthetic source"),key());
        }
        return profile.value().personId();
    }
    void addUser(long id) { db.update("INSERT INTO user_profile(id,openid) VALUES(?,?)",id,"person-test-"+id);db.update("INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at) VALUES(?,'LEGACY',?,UTC_TIMESTAMP(6))",id,key()); }
    String key() { return UUID.randomUUID().toString(); }
    IdentityChange identity(ChangeKind kind) { return new IdentityChange(kind,null,null,null,"verified case","case-reference"); }
    MembershipChange members(String... ids) { return new MembershipChange(Verification.VERIFIED,Arrays.stream(ids).map(id->new MemberInput(id,null)).toList(),"verified whole set","case-reference"); }
    PersonInitialAuthority.Plan plan(Domain domain,List<Action> actions,Target target) { return bootstrap.inspect(1,2,domain,actions,target,null,"operator-ref","independent-approval-ref","a".repeat(64),"approved control",null); }
    Scope bootstrap(Domain domain,List<Action> actions,Target target) throws Exception {
        var p=plan(domain,actions,target);assertEquals("VALIDATED_NOT_EXECUTED",bootstrap.verify(p));byte[] bytes=json.writeValueAsBytes(p);
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThrows(IllegalArgumentException.class,()->bootstrap.execute(p,"0".repeat(64),bytes));
        var result=bootstrap.execute(p,sha,bytes);assertEquals(result,bootstrap.execute(p,sha,bytes));return result;
    }
    Scope grant(long actor,long target,Domain domain,Action... actions) throws Exception {
        Target t=new Target("PERSON",subject(target),null);bootstrap(domain,List.of(actions),t);
        return service.grant(2,new ScopeChange(Long.toString(actor),t,new ActionSet(domain,List.of(actions)),null,"explicit scope","scope-case"),key()).value();
    }
    String org() throws Exception {
        // One formal directory control may be reused; no automatic OPERATE is created.
        if(db.queryForObject("SELECT COUNT(*) FROM person_scope_grant WHERE mode='ADMINISTER' AND target_type='DIRECTORY' AND revoked_at IS NULL",Integer.class)==0)
            bootstrap(Domain.ORGANIZATION,List.of(Action.READ,Action.MAINTAIN),new Target("DIRECTORY",null,null));
        return service.createOrganization(2,new OrganizationChange("same allowed name","",null,"directory case","case-ref","PARTY_BRANCH"),key()).value().organizationId();
    }
    Scope orgGrant(long actor,String org,Domain domain,Action... actions) throws Exception {
        if(actor==2) {
            var p=bootstrap.inspect(1,3,domain,List.of(actions),new Target("ORGANIZATION",null,org),null,"operator-ref","independent-approval-ref","a".repeat(64),"approved independent control",null);
            byte[] bytes=json.writeValueAsBytes(p);String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));bootstrap.execute(p,sha,bytes);
            return service.grant(3,new ScopeChange("2",new Target("ORGANIZATION",null,org),new ActionSet(domain,List.of(actions)),null,"scope","case-ref"),key()).value();
        }
        if(domain!=Domain.ORGANIZATION) bootstrap(domain,List.of(actions),new Target("ORGANIZATION",null,org));
        return service.grant(2,new ScopeChange(Long.toString(actor),new Target("ORGANIZATION",null,org),new ActionSet(domain,List.of(actions)),null,"scope","case-ref"),key()).value();
    }
    void problem(String type,Runnable runnable) { assertEquals("/problems/"+type,assertThrows(ContractProblemException.class,runnable::run).getType()); }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }

    @Test void unknownIsNotVerifiedNoneAndSharedVersion() throws Exception {
        long u=user();grant(9,u,Domain.IDENTITY,Action.READ,Action.MAINTAIN);grant(9,u,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        var unknown=service.identity(9,subject(u));assertEquals(Verification.UNKNOWN,unknown.value().verification());assertNull(unknown.value().presence());
        var beforeMembers=service.memberships(9,subject(u));
        var none=service.changeIdentity(9,subject(u),identity(ChangeKind.VERIFY_NONE),key(),unknown.etag());assertEquals("NONE",none.value().presence());
        problem("precondition-failed",()->service.replaceMemberships(9,subject(u),members(),key(),beforeMembers.etag()));
        var empty=service.replaceMemberships(9,subject(u),members(),key(),service.memberships(9,subject(u)).etag());assertEquals(List.of(),empty.value().currentMemberships());
        tx.execute(s->{var c=readers.open(9,u);assertEquals(PersonFactReaders.Outcome.KNOWN,readers.identity(u,c).outcome());assertEquals(List.of(),readers.memberships(u,c).organizationIds());return null;});
        service.changeIdentity(9,subject(u),identity(ChangeKind.MARK_DISPUTED),key(),service.identity(9,subject(u)).etag());
        service.replaceMemberships(9,subject(u),new MembershipChange(Verification.DISPUTED,null,"disputed source","case-ref"),key(),service.memberships(9,subject(u)).etag());
        tx.execute(s->{var c=readers.open(9,u);assertEquals("DISPUTED",readers.identity(u,c).reason());assertEquals("DISPUTED",readers.memberships(u,c).reason());return null;});
    }
    @Test void proposedStageRemainsClosedAndGenericLifecycleIsTestedWithExplicitPolicyDouble() throws Exception {
        long u=user();grant(9,u,Domain.IDENTITY,Action.READ,Action.MAINTAIN);
        var record=new IdentityChange(ChangeKind.RECORD,"PARTY_ACTIVIST",null,null,"case","case-ref");var first=service.identity(9,subject(u));
        problem("person-stage-unconfirmed",()->service.changeIdentity(9,subject(u),record,key(),first.etag()));
        catalog.confirmed=true;
        var active=service.changeIdentity(9,subject(u),record,key(),first.etag());assertEquals("ACTIVE",active.value().currentRecord().status());
        var transition=new IdentityChange(ChangeKind.TRANSITION,"PARTY_DEVELOPMENT_CANDIDATE",null,null,"case","case-ref");
        var next=service.changeIdentity(9,subject(u),transition,key(),active.etag());assertNotEquals(active.value().currentRecord().recordId(),next.value().currentRecord().recordId());
        var corrected=service.changeIdentity(9,subject(u),new IdentityChange(ChangeKind.CORRECT,"PROBATIONARY_PARTY_MEMBER",null,next.value().lastChangeId(),"correction","case-ref"),key(),next.etag());
        var paused=service.changeIdentity(9,subject(u),identity(ChangeKind.INVALIDATE),key(),corrected.etag());assertEquals("INACTIVE",paused.value().currentRecord().status());
        var resumed=service.changeIdentity(9,subject(u),record,key(),paused.etag());
        var revoked=service.changeIdentity(9,subject(u),identity(ChangeKind.REVOKE),key(),resumed.etag());assertEquals("REVOKED",revoked.value().currentRecord().status());
        catalog.confirmed=false;
        tx.execute(s->{var c=readers.open(9,u);assertEquals("STAGE_UNCONFIRMED",readers.identity(u,c).reason());return null;});
        assertEquals(6,service.identityHistory(9,subject(u),1,20).totalItems());
    }
    @Test void idempotencyReauthOldMatchAndConcurrentCas() throws Exception {
        long u=user();var scope=grant(9,u,Domain.IDENTITY,Action.READ,Action.MAINTAIN);var first=service.identity(9,subject(u));String key=key();
        var none=service.changeIdentity(9,subject(u),identity(ChangeKind.VERIFY_NONE),key,first.etag());assertEquals(none,service.changeIdentity(9,subject(u),identity(ChangeKind.VERIFY_NONE),key,first.etag()));
        problem("person-idempotency-conflict",()->service.changeIdentity(9,subject(u),identity(ChangeKind.MARK_UNKNOWN),key,first.etag()));
        var outcomes=race(()->service.changeIdentity(9,subject(u),identity(ChangeKind.MARK_UNKNOWN),key(),none.etag()),()->service.changeIdentity(9,subject(u),identity(ChangeKind.MARK_DISPUTED),key(),none.etag()));
        assertEquals(1,outcomes.stream().filter(x->x instanceof Result).count());assertEquals(1,outcomes.stream().filter(x->x instanceof ContractProblemException e && e.getStatus().value()==412).count());
        var revoke=service.scope(2,scope.scopeGrantId());service.revoke(2,scope.scopeGrantId(),new Reason("revoked","case-ref"),key(),revoke.etag());
        problem("not-found",()->service.changeIdentity(9,subject(u),identity(ChangeKind.VERIFY_NONE),key,first.etag()));
    }
    @Test void membershipsMultipleOrganizationsLeavingAndInactiveSource() throws Exception {
        long u=user();grant(9,u,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);String a=org(),b=org();orgGrant(2,a,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        var joined=service.replaceMemberships(9,subject(u),members(a,b),key(),service.memberships(9,subject(u)).etag());assertEquals(2,joined.value().currentMemberships().size());
        var same=service.replaceMemberships(9,subject(u),members(a,b),key(),joined.etag());assertEquals(joined.value().currentMemberships(),same.value().currentMemberships());
        var left=service.replaceMemberships(9,subject(u),members(b),key(),same.etag());assertEquals(1,left.value().currentMemberships().size());
        var inactive=service.updateOrganization(2,a,new OrganizationChange(null,null,"INACTIVE","closed","case-ref",null),key(),service.organization(2,a).etag());
        problem("person-organization-inactive",()->service.replaceMemberships(9,subject(u),members(a),key(),left.etag()));
        service.updateOrganization(2,a,new OrganizationChange(null,null,"ACTIVE","reopen","case-ref",null),key(),inactive.etag());
        service.replaceMemberships(9,subject(u),members(a),key(),left.etag());
        service.updateOrganization(2,a,new OrganizationChange(null,null,"INACTIVE","closed","case-ref",null),key(),service.organization(2,a).etag());
        tx.execute(s->{var c=readers.open(9,u);assertEquals("ORGANIZATION_INACTIVE",readers.memberships(u,c).reason());return null;});
    }
    @Test void fullOrganizationCoverageAndReadWriteAreIndependent() throws Exception {
        long u=user(),manager=user();String a=org(),b=org();grant(9,u,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        service.replaceMemberships(9,subject(u),members(a,b),key(),service.memberships(9,subject(u)).etag());
        orgGrant(manager,a,Domain.ORGANIZATION,Action.READ);orgGrant(manager,b,Domain.ORGANIZATION,Action.MAINTAIN);
        problem("not-found",()->service.memberships(manager,subject(u)));
        orgGrant(manager,b,Domain.ORGANIZATION,Action.READ);
        problem("not-found",()->service.memberships(manager,subject(u))); // independent A and B approvals cannot cover the whole person
        grant(manager,u,Domain.ORGANIZATION,Action.READ);
        assertEquals(2,service.memberships(manager,subject(u)).value().currentMemberships().size());
        problem("not-found",()->service.replaceMemberships(manager,subject(u),members(b),key(),service.memberships(manager,subject(u)).etag()));
        problem("person-self-governance-forbidden",()->service.replaceMemberships(manager,subject(manager),members(),key(),"\"anything\""));
    }
    @Test void controlledPageCountDetailIntersectionAndUnknownOrganizationFilter() throws Exception {
        long u=user(),v=user(),manager=user();
        grant(manager,u,Domain.IDENTITY,Action.READ);grant(manager,u,Domain.ORGANIZATION,Action.READ);
        grant(manager,v,Domain.IDENTITY,Action.READ);
        var page=service.persons(manager,1,1,null);assertEquals(1,page.totalItems());assertEquals(Long.toString(u),page.items().get(0).personId());
        assertEquals(0,service.persons(manager,2,1,null).items().size());
        assertNotNull(service.identity(manager,subject(u)));assertNotNull(service.memberships(manager,subject(u)));problem("not-found",()->service.memberships(manager,subject(v)));
        problem("person-foundation-unavailable",()->service.persons(manager,1,10,"unresolved-org-filter"));
        problem("forbidden",()->service.persons(user(),1,10,null));
    }
    @Test void unknownScopeSourceCannotBecomeEmptyResultAndParentRevokeDisablesChild() throws Exception {
        long u=user();var scope=grant(9,u,Domain.IDENTITY,Action.READ);
        db.update("UPDATE person_scope_grant SET target_id='missing-user' WHERE id=?",scope.authorityGrantId());
        problem("person-foundation-unavailable",()->service.identity(9,subject(u)));
        db.update("UPDATE person_scope_grant SET target_id=? WHERE id=?",Long.toString(u),scope.authorityGrantId());
        var p=bootstrap.inspect(1,2,Domain.IDENTITY,List.of(Action.READ),new Target("PERSON",subject(u),null),null,"operator-ref","approval-ref","a".repeat(64),"revoke control",scope.authorityGrantId());
        byte[] bytes=json.writeValueAsBytes(p);String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));bootstrap.execute(p,sha,bytes);
        problem("not-found",()->service.identity(9,subject(u)));
    }
    @Test void authorityCannotDelegateAcrossTargetTypeOrCombineNarrowActions() throws Exception {
        long u=user(),manager=user();String a=org();bootstrap(Domain.IDENTITY,List.of(Action.READ),new Target("ORGANIZATION",null,a));
        problem("not-found",()->service.grant(2,new ScopeChange(Long.toString(manager),new Target("PERSON",subject(u),null),new ActionSet(Domain.IDENTITY,List.of(Action.READ)),null,"case","case-ref"),key()));
        bootstrap(Domain.IDENTITY,List.of(Action.READ),new Target("PERSON",subject(u),null));bootstrap(Domain.IDENTITY,List.of(Action.MAINTAIN),new Target("PERSON",subject(u),null));
        problem("not-found",()->service.grant(2,new ScopeChange(Long.toString(manager),new Target("PERSON",subject(u),null),new ActionSet(Domain.IDENTITY,List.of(Action.READ,Action.MAINTAIN)),null,"case","case-ref"),key()));
        problem("person-self-governance-forbidden",()->service.grant(2,new ScopeChange("2",new Target("PERSON",subject(u),null),new ActionSet(Domain.IDENTITY,List.of(Action.READ)),null,"case","case-ref"),key()));
    }
    @Test void bootstrapRejectsMissingOrCorruptExistingRootGrantOrigin() {
        String change=db.queryForObject("SELECT grant_change_id FROM rbac_user_role_assignment WHERE user_id=1 AND role_code='ROOT' AND revoked_at IS NULL",String.class);
        String original=db.queryForObject("SELECT receipt FROM rbac_authorization_change WHERE id=?",String.class,change);long scopes=count("person_scope_grant");
        db.update("UPDATE rbac_authorization_change SET receipt=JSON_SET(receipt,'$.assignment.id','corrupt-origin') WHERE id=?",change);
        try { assertThrows(ContractProblemException.class,()->plan(Domain.IDENTITY,List.of(Action.READ),new Target("PERSON","9",null))); }
        finally { db.update("UPDATE rbac_authorization_change SET receipt=? WHERE id=?",original,change); }
        assertEquals(scopes,count("person_scope_grant"));
    }
    @Test void rollbackEventReceiptAndHeadTogether() throws Exception {
        long u=user();grant(9,u,Domain.IDENTITY,Action.READ,Action.MAINTAIN);var first=service.identity(9,subject(u));long events=count("person_foundation_event"),receipts=count("person_command_receipt");
        db.execute("CREATE TRIGGER person_test_failure BEFORE INSERT ON person_fact_current FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic head failure'");
        try { assertThrows(org.springframework.dao.DataAccessException.class,()->service.changeIdentity(9,subject(u),identity(ChangeKind.VERIFY_NONE),key(),first.etag())); }
        finally { db.execute("DROP TRIGGER person_test_failure"); }
        assertEquals(events,count("person_foundation_event"));assertEquals(receipts,count("person_command_receipt"));assertEquals(first,service.identity(9,subject(u)));
    }
    @Test void transactionContextCannotEscapeOrJoinNewTransaction() throws Exception {
        long u=user();grant(9,u,Domain.IDENTITY,Action.READ);final PersonReadContext[] stored=new PersonReadContext[1];
        tx.execute(s->{stored[0]=readers.open(9,u);assertEquals(PersonFactReaders.Outcome.INDETERMINATE,readers.identity(u,stored[0]).outcome());return null;});
        assertThrows(RuntimeException.class,()->readers.identity(u,stored[0]));
        tx.execute(s->{problem("person-foundation-unavailable",()->readers.identity(u,stored[0]));s.setRollbackOnly();return null;});
    }
    @Test void realReaderResultsConformToCanonicalInternalSchemas() throws Exception {
        long u=user();grant(9,u,Domain.IDENTITY,Action.READ,Action.MAINTAIN);grant(9,u,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        tx.execute(s->{var c=readers.open(9,u);evidence("IdentityReaderResult",readers.identity(u,c));evidence("MembershipReaderResult",readers.memberships(u,c));
            evidence("ScopeDecision",readers.authorizePerson(9,u,Domain.IDENTITY,Action.READ,c));evidence("ScopeDecision",readers.authorizePerson(9,u,Domain.IDENTITY,Action.REVIEW,c));
            evidence("ControlledQueryResult",readers.plan(9,Domain.IDENTITY,Action.READ,null,c));evidence("ControlledQueryResult",readers.plan(9,Domain.POINTS,Action.READ,null,c));return null;});
        service.changeIdentity(9,subject(u),identity(ChangeKind.VERIFY_NONE),key(),service.identity(9,subject(u)).etag());service.replaceMemberships(9,subject(u),members(),key(),service.memberships(9,subject(u)).etag());
        tx.execute(s->{var c=readers.open(9,u);evidence("IdentityReaderResult",readers.identity(u,c));evidence("MembershipReaderResult",readers.memberships(u,c));return null;});
        catalog.confirmed=true;service.changeIdentity(9,subject(u),new IdentityChange(ChangeKind.RECORD,"PARTY_ACTIVIST",null,null,"synthetic lifecycle policy","case-ref"),key(),service.identity(9,subject(u)).etag());
        tx.execute(s->{var c=readers.open(9,u);evidence("IdentityReaderResult",readers.identity(u,c));return null;});catalog.confirmed=false;
        tx.execute(s->{var c=readers.open(9,u);evidence("IdentityReaderResult",readers.identity(u,c));return null;});
    }
    void evidence(String schema,Object value) { readerEvidence.add(Map.of("schema",schema,"body",json.valueToTree(value))); }
    @Test void sourceUnknownDeniedAndExpireAtFinalWriteRollsBack() throws Exception {
        long u=user();grant(9,u,Domain.IDENTITY,Action.READ,Action.MAINTAIN);var first=service.identity(9,subject(u));
        auth.denied.add("identity:map-manage");problem("forbidden",()->service.changeIdentity(9,subject(u),identity(ChangeKind.VERIFY_NONE),key(),first.etag()));auth.denied.clear();
        final int[] checks={0};auth.onRequire=()->{if(++checks[0]==2) throw PersonProblems.denied();};
        problem("forbidden",()->service.changeIdentity(9,subject(u),identity(ChangeKind.VERIFY_NONE),key(),first.etag()));auth.onRequire=null;
        assertEquals(first,service.identity(9,subject(u)));
    }
    @Test void gateMakesMembershipChangeWaitUntilAuthorizedReadCommits() throws Exception {
        long u=user();grant(9,u,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);var match=service.memberships(9,subject(u)).etag();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->tx.execute(s->{var c=readers.open(9,u);assertEquals(PersonFactReaders.Decision.ALLOW,readers.authorizePerson(9,u,Domain.ORGANIZATION,Action.READ,c).decision());locked.countDown();try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new RuntimeException(e); }return null;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));var write=pool.submit(()->service.replaceMemberships(9,subject(u),members(),key(),match));
            assertThrows(TimeoutException.class,()->write.get(150,TimeUnit.MILLISECONDS));release.countDown();first.get(5,TimeUnit.SECONDS);assertEquals(Verification.VERIFIED,write.get(5,TimeUnit.SECONDS).value().verification());
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void scopeRevocationWaitsForReadThenNoOldAllowSurvives() throws Exception {
        long u=user();var scope=grant(9,u,Domain.IDENTITY,Action.READ);var match=service.scope(2,scope.scopeGrantId()).etag();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->tx.execute(s->{var c=readers.open(9,u);assertEquals(PersonFactReaders.Decision.ALLOW,readers.authorizePerson(9,u,Domain.IDENTITY,Action.READ,c).decision());locked.countDown();try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new RuntimeException(e); }return null;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));var revoke=pool.submit(()->service.revoke(2,scope.scopeGrantId(),new Reason("withdraw scope","case-ref"),key(),match));
            assertThrows(TimeoutException.class,()->revoke.get(150,TimeUnit.MILLISECONDS));release.countDown();first.get(5,TimeUnit.SECONDS);assertEquals("REVOKED",revoke.get(5,TimeUnit.SECONDS).value().status());
            problem("not-found",()->service.identity(9,subject(u)));
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void sharedPlanQueryCountGroupAndSingleDecisionsAgree() throws Exception {
        long u=user(),manager=user();String a=org();grant(9,u,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);grant(manager,u,Domain.IDENTITY,Action.READ);
        service.replaceMemberships(9,subject(u),members(a),key(),service.memberships(9,subject(u)).etag());
        tx.execute(s->{var c=readers.open(manager,u);var plan=readers.plan(manager,Domain.IDENTITY,Action.READ,null,c);assertEquals("READY",plan.decision());
            var list=readers.query(plan.planHandle(),1,20,c);assertEquals(List.of(subject(u)),list.personIds());assertEquals(list.totalItems(),readers.count(plan.planHandle(),c));assertEquals(PersonFactReaders.Decision.ALLOW,readers.authorizePerson(manager,u,Domain.IDENTITY,Action.READ,c).decision());
            var groups=readers.group(plan.planHandle(),c);assertEquals(1,groups.size());assertEquals(a,groups.get(0).organizationId());assertEquals(1,groups.get(0).totalPersons());return null;});
    }
    @Test void missingMembershipCannotBeReadAsVerifiedEmpty() throws Exception {
        long u=user();String a=org();grant(9,u,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        service.replaceMemberships(9,subject(u),members(a),key(),service.memberships(9,subject(u)).etag());
        var row=db.queryForMap("SELECT id,user_id,organization_id,valid_from,valid_until,created_event_id,ended_event_id FROM person_membership WHERE user_id=? AND ended_event_id IS NULL",u);
        db.update("DELETE FROM person_membership WHERE id=?",row.get("id"));
        try { problem("person-foundation-unavailable",()->service.memberships(9,subject(u))); }
        finally { db.update("INSERT INTO person_membership(id,user_id,organization_id,valid_from,valid_until,created_event_id,ended_event_id) VALUES(?,?,?,?,?,?,?)",row.get("id"),row.get("user_id"),row.get("organization_id"),row.get("valid_from"),row.get("valid_until"),row.get("created_event_id"),row.get("ended_event_id")); }
        assertEquals(1,service.memberships(9,subject(u)).value().currentMemberships().size());
    }
    @Test void expiryChangesMembershipEtagAndExpiredScopeRollsBackFinalWrite() throws Exception {
        long u=user();String a=org();grant(9,u,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        var until=LocalDateTime.now(ZoneOffset.UTC).plusSeconds(2).withNano(0);
        var command=new MembershipChange(Verification.VERIFIED,List.of(new MemberInput(a,until)),"bounded member","case-ref");
        var prior=service.replaceMemberships(9,subject(u),command,key(),service.memberships(9,subject(u)).etag());
        Thread.sleep(2100);var current=service.memberships(9,subject(u));assertTrue(current.value().currentMemberships().isEmpty());assertNotEquals(prior.etag(),current.etag());
        problem("precondition-failed",()->service.replaceMemberships(9,subject(u),members(),key(),prior.etag()));
        bootstrap(Domain.IDENTITY,List.of(Action.READ,Action.MAINTAIN),new Target("PERSON",subject(u),null));
        var scope=new ScopeChange("10",new Target("PERSON",subject(u),null),new ActionSet(Domain.IDENTITY,List.of(Action.READ,Action.MAINTAIN)),LocalDateTime.now(ZoneOffset.UTC).plusSeconds(3).withNano(0),"short scope","case-ref");
        service.grant(2,scope,key());var identity=service.identity(10,subject(u));final int[] calls={0};
        auth.onRequire=()->{if(++calls[0]==2) try { Thread.sleep(3100); } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new RuntimeException(e); }};
        problem("not-found",()->service.changeIdentity(10,subject(u),identity(ChangeKind.VERIFY_NONE),key(),identity.etag()));auth.onRequire=null;
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM person_fact_current WHERE user_id=? AND identity_verification='VERIFIED'",Integer.class,u));
    }
    @Test void realHttpAllSixteenOperationsAndStrictWire() throws Exception {
        long u=user();grant(9,u,Domain.IDENTITY,Action.READ,Action.MAINTAIN);grant(9,u,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        http("GET","/v1/admin/identity-stages",9,null,null,null,200,"PersonStageCatalog");
        http("GET","/v1/admin/persons",9,null,null,null,200,"ManagedPersonPage");
        var identity=http("GET","/v1/admin/persons/"+u+"/identity",9,null,null,null,200,"PersonIdentity");String match=identity.etag;
        String body="{\"kind\":\"VERIFY_NONE\",\"reason\":\"case\",\"verificationReference\":\"case-ref\"}";
        http("POST","/v1/admin/persons/"+u+"/identity-changes",9,body,key(),null,428,null);
        var verified=http("POST","/v1/admin/persons/"+u+"/identity-changes",9,body,key(),match,200,"PersonIdentity");
        http("POST","/v1/admin/persons/"+u+"/identity-changes",9,body,key(),match,412,null);
        http("POST","/v1/admin/persons/"+u+"/identity-changes",9,"{\"kind\":\"RECORD\",\"stageId\":\"PARTY_ACTIVIST\",\"reason\":\"case\",\"verificationReference\":\"case-ref\"}",key(),verified.etag,409,null);
        http("POST","/v1/admin/persons/"+u+"/identity-changes",9,"{\"kind\":\"RECORD\",\"stageId\":\"PARTY_ACTIVIST\",\"validUntil\":\"2027-01-01T00:00:00.1234567Z\",\"reason\":\"case\",\"verificationReference\":\"case-ref\"}",key(),verified.etag,400,null);
        http("GET","/v1/admin/persons/"+u+"/identity-history",9,null,null,null,200,"PersonIdentityHistoryPage");
        var membership=http("GET","/v1/admin/persons/"+u+"/memberships",9,null,null,null,200,"PersonMemberships");
        http("PUT","/v1/admin/persons/"+u+"/memberships",9,"{\"verification\":\"VERIFIED\",\"memberships\":[],\"reason\":\"case\",\"verificationReference\":\"case-ref\"}",key(),membership.etag,200,"PersonMemberships");
        http("GET","/v1/admin/persons/"+u+"/membership-history",9,null,null,null,200,"PersonMembershipHistoryPage");
        String o=org();orgGrant(2,o,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        http("GET","/v1/admin/organizations",2,null,null,null,200,"PersonOrganizationPage");
        http("POST","/v1/admin/organizations",2,"{\"name\":\"HTTP directory\",\"description\":\"\",\"reason\":\"case\",\"verificationReference\":\"case-ref\"}",key(),null,201,"PersonOrganization");
        var org=http("GET","/v1/admin/organizations/"+o,2,null,null,null,200,"PersonOrganization");
        http("PATCH","/v1/admin/organizations/"+o,2,"{\"status\":\"INACTIVE\",\"reason\":\"case\",\"verificationReference\":\"case-ref\"}",key(),org.etag,200,"PersonOrganization");
        http("GET","/v1/admin/person-management-scopes",2,null,null,null,200,"PersonScopePage");
        long other=user();bootstrap(Domain.IDENTITY,List.of(Action.READ),new Target("PERSON",subject(other),null));
        var granted=http("POST","/v1/admin/person-management-scopes",2,"{\"granteeUserId\":\"9\",\"target\":{\"type\":\"PERSON\",\"userId\":\""+other+"\"},\"actionSet\":{\"domain\":\"IDENTITY\",\"actions\":[\"READ\"]},\"reason\":\"case\",\"verificationReference\":\"case-ref\"}",key(),null,201,"PersonScopeGrant");
        String id=granted.body.get("scopeGrantId").textValue();var scope=http("GET","/v1/admin/person-management-scopes/"+id,2,null,null,null,200,"PersonScopeGrant");
        http("POST","/v1/admin/person-management-scopes/"+id+":revoke",2,"{\"reason\":\"case\",\"verificationReference\":\"case-ref\"}",key(),scope.etag,200,"PersonScopeGrant");
        http("POST","/v1/admin/persons/"+u+"/identity-changes",9,body.replace("}",",\"role\":\"ROOT\"}"),key(),match,400,null);
        http("PUT","/v1/admin/persons/"+u+"/memberships",9,"{\"verification\":\"UNKNOWN\",\"memberships\":[],\"reason\":\"case\",\"verificationReference\":\"case-ref\"}",key(),match,400,null);
        http("GET","/v1/admin/identity-stages",null,null,null,null,401,null);
        auth.denied.add("identity:read");http("GET","/v1/admin/identity-stages",9,null,null,null,403,null);auth.denied.clear();
    }
    @Test void rootOwnerManagesUnknownPersonsDirectoryAndIndependentScopesWithoutBootstrap() throws Exception {
        long target=user();
        assertEquals("UNKNOWN",service.identity(1,subject(target)).value().verification().name());
        var page=service.persons(1,1,100,null);
        assertTrue(page.items().stream().anyMatch(p->p.personId().equals(subject(target))));
        var organization=service.createOrganization(1,new OrganizationChange("Root owned organization","case",null,"owner case","owner reference","PARTY_BRANCH"),key());
        assertTrue(service.organizations(1,1,100,null).items().stream().anyMatch(o->o.organizationId().equals(organization.value().organizationId())));
        service.updateOrganization(1,organization.value().organizationId(),new OrganizationChange("Root edit",null,null,"owner edit","owner reference",null),key(),organization.etag());
        var membership=service.memberships(1,subject(target));
        service.replaceMemberships(1,subject(target),members(organization.value().organizationId()),key(),membership.etag());
        var identity=service.identity(1,subject(target));
        service.changeIdentity(1,subject(target),identity(ChangeKind.VERIFY_NONE),key(),identity.etag());
        var scope=service.grant(1,new ScopeChange("9",new Target("PERSON",subject(target),null),new ActionSet(Domain.IDENTITY,List.of(Action.READ)),null,"root grant","owner reference"),key());
        assertEquals("ROOT_OWNER",scope.value().source());assertNull(scope.value().authorityGrantId());
        assertEquals(scope.value().scopeGrantId(),service.scope(1,scope.value().scopeGrantId()).value().scopeGrantId());
        service.identity(9,subject(target)); // Explicit scope works; other roles received no automatic capability.
        assertTrue(service.scopes(1,1,100,null,null).items().stream().anyMatch(g->g.scopeGrantId().equals(scope.value().scopeGrantId())));
        service.revoke(1,scope.value().scopeGrantId(),new Reason("root revoke","owner reference"),key(),scope.etag());
        assertThrows(ContractProblemException.class,()->service.identity(9,subject(target)));
        assertThrows(ContractProblemException.class,()->service.changeIdentity(1,subject(1),identity(ChangeKind.MARK_UNKNOWN),key(),"unused"));
        assertEquals(0L,tx.execute(t->mapper.corruptFacts()).longValue());
    }
    @Test void registeredOrganizationMembersIgnoreUnknownCompleteSetsWithoutWideningPersonReads() throws Exception {
        String organization=org(),other=org();localTestGrant(organization);
        localTestScopes.add(orgGrant(9,organization,Domain.IDENTITY,Action.READ).scopeGrantId());
        long member=user();for(int i=0;i<15;i++) user();
        var initial=service.localMembership(9,subject(member),organization);
        var joined=service.changeLocalMembership(9,subject(member),organization,localChange(LocalMemberKind.JOIN),key(),initial.etag());
        var page=service.organizationMembers(9,organization,1,20);
        assertEquals("REGISTERED_CURRENT_MEMBERS",page.coverage());assertEquals(1,page.totalItems());
        assertEquals(List.of(joined.value()),page.items());assertNotNull(page.evaluatedAt());
        assertEquals(Verification.UNKNOWN,service.memberships(1,subject(member)).value().verification());
        problem("person-foundation-unavailable",()->service.persons(9,1,20,organization));
        problem("not-found",()->service.organizationMembers(9,other,1,20));
        auth.denied.add("identity:read");
        var wire=http("GET","/v1/admin/organizations/"+organization+"/members",9,null,null,null,200,"OrganizationMemberPage");
        assertEquals(1,wire.body.path("totalItems").asInt());assertFalse(wire.body.path("items").get(0).has("identityVerification"));
        auth.denied.clear();auth.denied.add("organization:read");
        http("GET","/v1/admin/organizations/"+organization+"/members",9,null,null,null,403,null);auth.denied.clear();
        service.changeLocalMembership(1,subject(member),other,localChange(LocalMemberKind.JOIN),key(),service.localMembership(1,subject(member),other).etag());
        assertEquals(1,service.organizationMembers(9,organization,1,20).totalItems());
        assertEquals(0,service.organizationMembers(9,organization,2,20).items().size());
        String empty=org();var emptyPage=service.organizationMembers(1,empty,1,20);assertEquals(0,emptyPage.totalItems());
    }
    @Test void registeredMemberIntegrityIsCheckedBeforePaginationAndExpiryUsesSameTime() throws Exception {
        String organization=org();long first=user(),second=user();
        for(long member:List.of(first,second)) service.changeLocalMembership(1,subject(member),organization,localChange(LocalMemberKind.JOIN),key(),service.localMembership(1,subject(member),organization).etag());
        assertEquals(2,service.organizationMembers(1,organization,1,1).totalItems());
        assertEquals(Long.toString(first),service.organizationMembers(1,organization,1,1).items().get(0).personId());
        assertEquals(Long.toString(second),service.organizationMembers(1,organization,2,1).items().get(0).personId());
        String event=db.queryForObject("SELECT last_event_id FROM person_local_membership WHERE user_id=? AND organization_id=?",String.class,second,organization);
        String original=db.queryForObject("SELECT local_after_json FROM person_foundation_event WHERE id=?",String.class,event);
        db.update("UPDATE person_foundation_event SET local_after_json=JSON_SET(local_after_json,'$.fact.membershipId','corrupt') WHERE id=?",event);
        try {problem("person-foundation-unavailable",()->service.organizationMembers(1,organization,1,1));}
        finally {db.update("UPDATE person_foundation_event SET local_after_json=? WHERE id=?",original,event);}
        var row=mapper.memberships(subject(second)).stream().filter(m->organization.equals(m.getOrganizationId())).findFirst().orElseThrow();
        tx.executeWithoutResult(t->{
            var at=mapper.now();
            db.update("UPDATE person_membership SET valid_until=? WHERE id=?",at,row.getId());
            var query=new PersonMapper.OrganizationMembers(organization,at,20,0);
            assertEquals(1,mapper.organizationMemberCount(query));assertEquals(1,mapper.organizationMembers(query).size());
            db.update("UPDATE person_membership SET valid_until=NULL,valid_from=? WHERE id=?",at.plusHours(1),row.getId());
            assertEquals(1,mapper.organizationMemberCount(query));t.setRollbackOnly();
        });
        var detail=service.organization(1,organization);
        service.updateOrganization(1,organization,new OrganizationChange(null,null,"INACTIVE","stop","test",null),key(),detail.etag());
        problem("person-foundation-unavailable",()->service.organizationMembers(1,organization,1,20));
    }
    record Wire(JsonNode body,String etag) {}
    Wire http(String method,String path,Number actor,String body,String key,String match,int status,String schema) throws Exception {
        var req=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");if(actor!=null) req.header("X-Person-Test-Actor",actor.toString());if(key!=null) req.header("Idempotency-Key",key);if(match!=null) req.header("If-Match",match);
        var r=HttpClient.newHttpClient().send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(status,r.statusCode(),r.body());assertEquals("no-store",r.headers().firstValue("Cache-Control").orElse(null));
        var node=json.readTree(r.body());var evidence=new LinkedHashMap<String,Object>();evidence.put("method",method);evidence.put("path",path);evidence.put("status",status);evidence.put("schema",schema);evidence.put("body",node);evidence.put("etag",r.headers().firstValue("ETag").orElse(null));evidence.put("location",r.headers().firstValue("Location").orElse(null));evidence.put("cacheControl",r.headers().firstValue("Cache-Control").orElse(null));evidence.put("contentType",r.headers().firstValue("Content-Type").orElse(null));httpEvidence.add(evidence);
        return new Wire(node,r.headers().firstValue("ETag").orElse(null));
    }
    List<Object> race(Callable<?> a,Callable<?> b) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try { Callable<Object> one=()->{start.await();try{return a.call();}catch(Exception e){return e;}};Callable<Object> two=()->{start.await();try{return b.call();}catch(Exception e){return e;}};var x=pool.submit(one);var y=pool.submit(two);start.countDown();return List.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS)); }
        finally { pool.shutdownNow(); }
    }
    static class TestCatalog extends PersonCatalog { volatile boolean confirmed;@Override public boolean confirmed(String stage) { return confirmed; } }
    static class TestAuthentication implements PersonAuthentication {
        Set<String> denied=ConcurrentHashMap.newKeySet();volatile Runnable onRequire;
        @Override public void verifyActor(long actor) { /* Explicit synthetic service-call identity adapter. */ }
        public long actor() {
            var request=RequestContextHolder.getRequestAttributes();if(request instanceof ServletRequestAttributes r) {
                var value=r.getRequest().getHeader("X-Person-Test-Actor");if(value==null) throw new ContractProblemException(org.springframework.http.HttpStatus.UNAUTHORIZED,"/problems/unauthorized","Synthetic session missing");return Long.parseLong(value);
            }return 9;
        }
        public void require(long actor,String namespace,String permission,LocalDateTime at) { if(denied.contains(permission)) throw PersonProblems.denied();if(onRequire!=null) onRequire.run(); }
    }
    @Configuration @EnableAutoConfiguration @EnableTransactionManagement
    @MapperScan(basePackageClasses={PersonMapper.class,RbacMapper.class})
    @Import({cn.jualn.miniapp.module.person.service.qualification.MentorOperationAccess.class,cn.jualn.miniapp.module.person.service.qualification.MentorWorkflowService.class,cn.jualn.miniapp.module.person.service.binding.PersonBindingTransitions.class,cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.class,cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,ManagedPersonService.class,ManagedPersonIdentifierService.class,ManagedPersonBindingService.class,ManagedPersonAccess.class,ManagedPersonJournal.class,PersonIdentifierNamespaces.class,ManagedPersonController.class,PartyRosterService.class,RosterAccess.class,RosterWorkbook.class,PersistentMentorQualification.class,MentorManagementService.class,
            RosterUploadService.class,RosterUploadCommands.class,
            QualificationController.class,QualificationProblemAdvice.class,
            PersonnelJournal.class,PersonPrivateData.class,PersonFoundationService.class,PersonFactReaders.class,TransactionalAuthorization.class,RbacAuthorizationReader.class,RbacCatalog.class,AdminPermissionPolicy.class,PersonController.class,PersonProblemAdvice.class,PersonNoStoreFilter.class,GlobalExceptionHandler.class,JacksonConfig.class,
            PersonBusinessScopes.class,UnifiedDutyCatalog.class,UnifiedDutyReader.class,UnifiedAuthorizationService.class,UnifiedAuthorizationController.class,RbacExceptionHandler.class})
    @org.springframework.context.annotation.ComponentScan(basePackages="cn.jualn.miniapp.module.person.service.roster",useDefaultFilters=false,
            includeFilters=@org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.REGEX,pattern=".*PartyPersonnelCorrections"))
    static class Config {
        @Bean cn.jualn.miniapp.module.user.service.UserAccountService qualificationUsers() {return org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserAccountService.class);}
        @Bean RosterApplicationThrottle qualificationThrottle() {return org.mockito.Mockito.mock(RosterApplicationThrottle.class);}
        @Bean DataSource dataSource() { return source(System.getProperty("person.test.jdbcUrl")); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean TestAuthentication authentication() { return new TestAuthentication(); }
        @Bean TestCatalog catalog() { return new TestCatalog(); }
        @Bean cn.jualn.miniapp.module.admin.auth.service.AdminTokenService tokens(TestAuthentication authentication) {
            var tokens=org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.auth.service.AdminTokenService.class);
            org.mockito.Mockito.when(tokens.requireAuthenticatedSession()).thenAnswer(invocation->authentication.actor());return tokens;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setCacheEnabled(false);factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/MentorWorkflowMapper.xml"),new ClassPathResource("mapper/PersonMapper.xml"),new ClassPathResource("mapper/ManagedPersonMapper.xml"),new ClassPathResource("mapper/MentorAnswerEvidence.xml"), new ClassPathResource("mapper/QualificationMapper.xml"),new ClassPathResource("mapper/RbacMapper.xml"),new ClassPathResource("mapper/RbacOfflineMapper.xml"),new ClassPathResource("mapper/UnifiedMapper.xml"),new ClassPathResource("mapper/PersonBusinessMapper.xml"));return factory.getObject();
        }
    }
}
