package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.support.RbacTime;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;

import cn.dev33.satoken.SaManager;
import cn.jualn.miniapp.MiniAppApplication;
import cn.jualn.miniapp.module.person.bo.PersonBO;
import cn.jualn.miniapp.module.person.service.bootstrap.PersonInitialAuthority;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO;
import cn.jualn.miniapp.module.media.service.PointMaterialProvider;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.MiniSessionResponse;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL, full app, QR, Sa/Redis, RBAC source/Assignment governance and person/Points owners.
 * External WeChat login and COS credentials/object/safety are explicit provider doubles. */
@EnabledIfSystemProperty(named="points.integration.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/points_integration_test_[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsIntegrationHttpTest {
    static final ObjectMapper JSON=RbacJson.mapper();
    static final AtomicReference<String> SCENE=new AtomicReference<>();
    static final AtomicReference<Runnable> OFFICIAL_SAFETY_HOOK=new AtomicReference<>();
    static final AtomicReference<Runnable> COS_INSPECTION_HOOK=new AtomicReference<>();
    static final long ROOT=930001,GOVERNOR=930002,STEWARD=930003,REVIEWER=930004,USER=930005,OUTSIDER=930006;
    static final String AUTH="/v1/admin/authorization",PEOPLE="/v1/admin/persons/",SCOPES="/v1/admin/person-management-scopes";
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final Path DIR=Path.of("target/points-integration");
    final List<Map<String,Object>> wire=Collections.synchronizedList(new ArrayList<>());
    final List<String> issued=new ArrayList<>();
    ServletWebServerApplicationContext app;
    JdbcTemplate db;
    String url,base,root,governor,steward,reviewer,user,outsider;
    PersonInitialAuthority controls;
    final Map<Long,String> subjects=new HashMap<>();
    DriverManagerDataSource source(String connection) { return new DriverManagerDataSource(connection+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",System.getenv().getOrDefault("MANAGED_PERSON_TEST_DB_USER","root"),System.getenv().getOrDefault("MANAGED_PERSON_TEST_DB_PASSWORD","")); }
    @BeforeAll void start() throws Exception {
        url=System.getProperty("points.integration.jdbcUrl");
        String redis=System.getenv("POINTS_INTEGRATION_REDIS_PORT");
        if(redis==null || !redis.matches("[0-9]+")) throw new IllegalStateException("Dedicated owned Redis required");
        Files.createDirectories(DIR);
        String database=url.substring(url.lastIndexOf('/')+1);
        var admin=new JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        assertEquals(System.getProperty("points.integration.serverUuid"),admin.queryForObject("SELECT @@server_uuid",String.class));
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,database));
        admin.execute("CREATE DATABASE "+database);
        Flyway.configure().dataSource(source(url)).load().migrate();
        db=new JdbcTemplate(source(url));
        for(long id=ROOT;id<=OUTSIDER;id++) {
            db.update("INSERT INTO user_profile(id,openid,nickname,role,status) VALUES(?,?,?,1,1)",id,"points-integration-"+id,"SYNTHETIC "+id);
            db.update("INSERT INTO rbac_authorization_authority VALUES(?,'LEGACY',UUID(),UTC_TIMESTAMP(6))",id);
        }
        var initial=new RbacInitialMigration(url,source(url));
        var plan=initial.inspect("isolated-points-integration",ROOT,"synthetic-executor","synthetic-approval",RbacCatalog.hash("isolated test authority".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        initial.dryRun(plan);initial.execute(plan);initial.execute(plan);
        // Isolated release fixture only; no migration/default definition is changed.
        var fixtureRoles=new java.util.HashSet<>(List.of("PERSON_FACT_STEWARD_V1","PERSON_SCOPE_ADMIN_V1","POINTS_REVIEWER","POINTS_READER_V1","LEARNING_MANAGER","LEARNING_REVIEWER"));
        if("EXPLICIT_SYNTHETIC_R3_APPROVAL".equals(System.getProperty("unified.r3.fixture")))
            new UnifiedDutyCatalog(new RbacCatalog()).templates().forEach(t->fixtureRoles.add(t.roleCode()));
        for(String role:fixtureRoles) {
            db.update("UPDATE rbac_permission_definition p JOIN rbac_role_permission m ON m.permission_code=p.code SET p.lifecycle='ENABLED',p.readiness='READY',p.grantable=1,p.unavailable_reason=NULL WHERE m.role_code=?",role);
            db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=?",role);
        }
        // Event stream XREADGROUP blocks for two seconds; command timeout must exceed it.
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(MiniAppApplication.class,Fixture.class).run(
                "--spring.profiles.active=points-integration","--points.integration.test-policy=FOUR_PROPOSED_STAGES_V1",
                "--logging.config=classpath:logback-unified-integration.xml","--server.address=127.0.0.1","--server.port=0","--spring.datasource.url="+source(url).getUrl(),
                "--spring.datasource.username=${MANAGED_PERSON_TEST_DB_USER:root}","--spring.datasource.password=${MANAGED_PERSON_TEST_DB_PASSWORD:}","--spring.datasource.hikari.minimum-idle=1","--spring.datasource.hikari.maximum-pool-size=2","--spring.datasource.hikari.connection-timeout=2500",
                "--spring.data.redis.host=127.0.0.1","--spring.data.redis.port="+redis,"--spring.data.redis.password=","--spring.data.redis.database=0",
                "--spring.data.redis.timeout=5000ms","--spring.data.redis.connect-timeout=1200ms","--spring.flyway.enabled=false",
                "--sa-token.jwt-secret-key=isolated-points-integration-synthetic-20261018","--sa-token.is-log=false","--logging.level.root=WARN",
                "--admin-auth.qr-login-v2.env-version=develop",
                "--admin-auth.qr-login-v2.create-ip-limit=200","--admin-auth.qr-login-v2.create-global-limit=300",
                "--people-qualification.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","--people-qualification.match-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "--cos.secret-id=synthetic-id","--cos.secret-key=synthetic-key",
                "--cos.bucket=synthetic-points-1250000000","--cos.region=ap-guangzhou","--cos.public-url-prefix=https://synthetic.invalid",
                "--cos.custom-domain=https://synthetic.invalid","--cos.presign-expire-seconds=3600");
        base="http://127.0.0.1:"+app.getWebServer().getPort();
        assertTrue(SaManager.getSaTokenDao().getClass().getName().contains("Redis"));
        root=qr(ROOT);expect(200,"GET","/v1/admin/authorization/me/duty-options",root,null);grantRbac(root,GOVERNOR,"PERSON_SCOPE_ADMIN_V1","ORGANIZATION");
        grantRbac(root,STEWARD,"PERSON_FACT_STEWARD_V1","IDENTITY");grantRbac(root,STEWARD,"PERSON_FACT_STEWARD_V1","ORGANIZATION");
        grantRbac(root,REVIEWER,"POINTS_REVIEWER","POINTS");
        governor=qr(GOVERNOR);steward=qr(STEWARD);reviewer=qr(REVIEWER);user=ordinary(USER);outsider=ordinary(OUTSIDER);
        controls=new PersonInitialAuthority(url,source(url));
    }
    @AfterAll void stop() throws Exception {
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve("http.json").toFile(),wire);
            if(db!=null) JSON.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve("auth-facts.json").toFile(),Map.of(
                    "authority",db.queryForList("SELECT user_id,source,version FROM rbac_authorization_authority ORDER BY user_id"),
                    "assignments",db.queryForList("SELECT id,user_id,role_code,namespace,grant_change_id,revoke_change_id FROM rbac_user_role_assignment"),
                    "offlineEffects",db.queryForList("SELECT id,operation_id,user_id,assignment_id FROM rbac_offline_effect"),
                    "userStatuses",db.queryForList("SELECT id,status,ban_expire_at FROM user_profile ORDER BY id"),
                    "providerDoubles",List.of("external WeChat login/QR generation","COS credentials/object retention/content safety")));
        } finally { if(app!=null) app.close(); }
    }
    @Test void v66RealSaPointsChainKeepsLedgerAndPrivateMaterialWithPerson() throws Exception {
        assertEquals("NO_BINDING",expect(200,"GET","/v1/users/me/points/eligibility",user,null).path("reason").asText());
        String organization=organization("v66 real Points acceptance");qualify(organization);person(USER,List.of(organization));String person=subject(USER);var original=currentBinding(person);
        duty(REVIEWER,"POINTS_REVIEW",new UnifiedModels.Target("ORGANIZATION",null,organization));
        duty(REVIEWER,"POINTS_PERSON_LEDGER",new UnifiedModels.Target("PERSON",person,null));
        var domain=PersonBO.Domain.POINTS;var actions=List.of(PersonBO.Action.READ,PersonBO.Action.REVIEW,PersonBO.Action.REVERSE);
        // Separate legacy action approval is still required for reversal; GLOBAL capability is not data scope.
        var foundation=app.getBean(cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.class);
        var scopeBody=Map.of("granteeUserId",Long.toString(REVIEWER),"target",Map.of("type","PERSON","personId",person),"actionSet",Map.of("domain","POINTS","actions",actions),"reason","independent reversal approval","verificationReference","synthetic-source");
        write(201,"POST",SCOPES,root,scopeBody,Map.of());
        String file=material(user,"submitted.pdf"),draft=material(user,"unsubmitted.pdf"),key=UUID.randomUUID().toString();
        var body=Map.of("title","v66 immutable owner","description","real controllers and owners; external COS is a declared double","fileIds",List.of(file),"acceptingOrganizationId",organization);
        var submitted=checked(201,call("POST","/v1/users/me/points/applications",user,body,Map.of("Idempotency-Key",key)));
        assertEquals(submitted,checked(201,call("POST","/v1/users/me/points/applications",user,body,Map.of("Idempotency-Key",key))));
        assertEquals(person,submitted.path("personId").asText());assertEquals(original.path("bindingId").asText(),submitted.path("submittedBindingId").asText());
        String application=submitted.path("applicationId").asText();
        var approved=write(200,"POST","/v1/admin/points/applications/"+application+"/decisions",reviewer,Map.of("decision","APPROVED","awardedPoints",9,"reviewNote","independent real authorization"),Map.of());
        String grant=approved.path("grantLedgerId").asText();assertEquals(9,expect(200,"GET","/v1/users/me/points/summary",user,null).path("balance").asInt());
        long next=930125;syntheticUser(next,true);grantRbac(root,next,"POINTS_REVIEWER","POINTS");String nextOrdinary=ordinary(next),nextAdmin=qr(next);
        duty(next,"POINTS_PERSON_LEDGER",new UnifiedModels.Target("PERSON",person,null));
        write(201,"POST",SCOPES,root,Map.of("granteeUserId",Long.toString(next),"target",Map.of("type","PERSON","personId",person),"actionSet",Map.of("domain","POINTS","actions",actions),"reason","independent negative self-review fixture","verificationReference","synthetic-source"),Map.of());
        moveBinding(person,next);
        assertEquals(9,expect(200,"GET","/v1/users/me/points/summary",nextOrdinary,null).path("balance").asInt());
        expect(404,"GET","/v1/users/me/points/applications/"+application,user,null);
        expect(404,"GET","/v1/users/me/points/files/"+file+"/access",user,null);
        expect(200,"GET","/v1/users/me/points/files/"+file+"/access",nextOrdinary,null);
        expect(404,"GET","/v1/users/me/points/files/"+draft,nextOrdinary,null);expect(200,"GET","/v1/users/me/points/files/"+draft,user,null);
        // Existing catalog intentionally supplies no adjustment/approval role. Do not expand it for a test.
        expect(403,"POST","/v1/admin/points/ledger/"+grant+":reverse",nextAdmin,Map.of("reason","same person with another account"));
        expect(403,"POST","/v1/admin/points/ledger/"+grant+":reverse",reviewer,Map.of("reason","existing review role cannot reverse"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rbac_role_permission WHERE permission_code IN ('points:adjust','points:adjust-approve')",Integer.class));
        assertEquals(9,expect(200,"GET","/v1/users/me/points/summary",nextOrdinary,null).path("balance").asInt());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE person_id=? AND applicant_user_id=?",Integer.class,person,USER));
    }
    @Test void v66RealSaLearningChainPreservesTwoAssignmentsAndFrozenRecipients() throws Exception {
        long personAccount=930127,manager=930128,account=930129;syntheticUser(personAccount,false);syntheticUser(manager,true);syntheticUser(account,false);
        String person=subject(personAccount),ownerToken=ordinary(personAccount),accountToken=ordinary(account);
        duty(manager,"LEARNING_MANAGE",new UnifiedModels.Target("PLATFORM",null,null));String managerToken=qr(manager);
        var audience=Map.of("persons",Map.of("personIds",List.of(person)),"accounts",Map.of("userIds",List.of(Long.toString(account))));
        var requirements=Map.of("confirmationRequired",true,"minimumTextLength",0,"minimumFiles",0,"maximumFiles",0,"allowedFileKinds",List.of());
        var draft=call("POST","/v1/admin/learning-tasks",managerToken,Map.of("title","v66 two responsibilities","description","real controllers, Sa and persisted subjects","requirements",requirements,"reviewRequired",false,"firstDeadline",Instant.now().plusSeconds(172800).toString(),"audience",audience,"managementTarget",Map.of("type","PLATFORM")),Map.of("Idempotency-Key",UUID.randomUUID().toString()));
        var task=checked(201,draft);String taskId=task.path("taskId").asText(),tag=draft.headers().firstValue("ETag").orElseThrow();
        var preview=write(201,"POST","/v1/admin/learning-tasks/"+taskId+"/audience-previews",managerToken,Map.of("purpose","PUBLISH","audience",audience),Map.of("If-Match",tag));assertTrue(preview.path("canApply").asBoolean());assertEquals(2,preview.path("resolvedCount").asInt());
        write(200,"POST","/v1/admin/learning-tasks/"+taskId+":publish",managerToken,Map.of("previewId",preview.path("previewId").asText()),Map.of("If-Match",tag));
        String personAssignment=db.queryForObject("SELECT CAST(id AS CHAR) FROM learning_task_assignment WHERE task_id=? AND subject_type='PERSON'",String.class,Long.parseLong(taskId));
        String accountAssignment=db.queryForObject("SELECT CAST(id AS CHAR) FROM learning_task_assignment WHERE task_id=? AND subject_type='ACCOUNT'",String.class,Long.parseLong(taskId));
        expect(200,"GET","/v1/users/me/learning-task-assignments/"+personAssignment,ownerToken,null);
        moveBinding(person,account);
        expect(404,"GET","/v1/users/me/learning-task-assignments/"+personAssignment,ownerToken,null);
        var mine=expect(200,"GET","/v1/users/me/learning-tasks",accountToken,null);assertEquals(2,mine.path("items").size());
        for(String assignment:List.of(personAssignment,accountAssignment)) {
            String path="/v1/users/me/learning-task-assignments/"+assignment;var detail=call("GET",path,accountToken,null,Map.of());checked(200,detail);
            var confirmed=write(200,"POST",path+":confirm",accountToken,Map.of("confirmed",true),Map.of("If-Match",detail.headers().firstValue("ETag").orElseThrow()));
            assertEquals(Long.toString(account),confirmed.path("assignment").path("confirmedByUserId").asText());
        }
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM learning_task_assignment WHERE task_id=?",Integer.class,Long.parseLong(taskId)));
        var notifications=expect(200,"GET","/v1/users/me/notifications?representation=structured",ownerToken,null);
        assertTrue(notifications.path("items").isArray());assertTrue(notifications.path("items").findValuesAsText("assignmentId").contains(personAssignment));
        assertNotEquals(personAssignment,accountAssignment);
    }
    @Test void realAuthorityAndPersonMaintenanceFeedEligibilityAndScopedPoints() throws Exception {
        expect(503,"GET","/v1/users/me/points/eligibility",user,null);
        expect(403,"GET","/v1/admin/identity-stages",reviewer,null);
        expect(403,"GET","/v1/admin/points/applications",steward,null);
        // Independent root establishes bounded control; HTTP governor grants actual OPERATE.
        control(STEWARD,PersonBO.Domain.ORGANIZATION,List.of(PersonBO.Action.READ,PersonBO.Action.MAINTAIN),new PersonBO.Target("DIRECTORY",null,null));
        var org=write(201,"POST","/v1/admin/organizations",steward,Map.of("name","Synthetic Points organization","description","isolated evidence","reason","approved test directory","verificationReference","test-evidence"),Map.of());
        String organization=org.path("organizationId").asText();assertFalse(organization.isBlank());
        qualify(organization);
        for(var domain:List.of(PersonBO.Domain.IDENTITY,PersonBO.Domain.ORGANIZATION)) {
            var target=new PersonBO.Target("PERSON",Long.toString(USER),null);control(domain,List.of(PersonBO.Action.READ,PersonBO.Action.MAINTAIN),target);
            scope(STEWARD,target,domain,List.of(PersonBO.Action.READ,PersonBO.Action.MAINTAIN));
        }
        var identity=call("GET",PEOPLE+USER+"/identity",steward,null,Map.of());checked(200,identity);
        write(200,"POST",PEOPLE+USER+"/identity-changes",steward,Map.of("kind","RECORD","stageId","PARTY_ACTIVIST","reason","verified isolated identity","verificationReference","test-evidence"),Map.of("If-Match",identity.headers().firstValue("ETag").orElseThrow()));
        var memberships=call("GET",PEOPLE+USER+"/memberships",steward,null,Map.of());checked(200,memberships);
        write(200,"PUT",PEOPLE+USER+"/memberships",steward,Map.of("verification","VERIFIED","memberships",List.of(Map.of("organizationId",organization)),"reason","verified complete membership set","verificationReference","test-evidence"),Map.of("If-Match",memberships.headers().firstValue("ETag").orElseThrow()));
        // Controlled status fixture: private evidence is not a content-creation permission.
        db.update("UPDATE user_profile SET status=2,ban_expire_at=NULL WHERE id=?",USER);
        assertTrue(expect(200,"GET","/v1/users/me/points/eligibility",user,null).path("eligible").asBoolean());
        assertEquals(0,expect(200,"GET","/v1/users/me/points/summary",user,null).path("balance").asInt());
        var target=new PersonBO.Target("PERSON",Long.toString(USER),null);control(PersonBO.Domain.POINTS,List.of(PersonBO.Action.READ,PersonBO.Action.REVIEW,PersonBO.Action.REVERSE),target);
        scope(REVIEWER,target,PersonBO.Domain.POINTS,List.of(PersonBO.Action.READ,PersonBO.Action.REVIEW,PersonBO.Action.REVERSE));
        var pointScope=duty(REVIEWER,"POINTS_REVIEW",new UnifiedModels.Target("ORGANIZATION",null,organization));
        duty(REVIEWER,"POINTS_PERSON_LEDGER",new UnifiedModels.Target("PERSON",Long.toString(USER),null));
        duty(REVIEWER,"POINTS_STATS_CURRENT",new UnifiedModels.Target("ORGANIZATION",null,organization));
        duty(REVIEWER,"POINTS_STATS_ACCEPTING",new UnifiedModels.Target("ORGANIZATION",null,organization));
        verifyRoster(organization);
        String mine="/v1/users/me/points/",admin="/v1/admin/points/";
        var upload=write(200,"POST",mine+"file-upload-credentials",user,Map.of("files",List.of(Map.of("fileName","proof.pdf","kind","PDF","contentType","application/pdf","sizeBytes",128))),Map.of());
        var file=write(201,"POST",mine+"files",user,Map.of("uploadId",upload.path("uploadId").asText(),"fileIndex",0),Map.of());
        String fileId=file.path("fileId").asText();assertFalse(fileId.isBlank());
        expect(404,"GET",mine+"files/"+fileId,outsider,null);
        expect(404,"GET",mine+"files/"+fileId+"/access",outsider,null);
        expect(404,"GET",admin+"files/"+fileId+"/access",reviewer,null);
        expect(200,"GET",mine+"files/"+fileId+"/access",user,null);
        String createKey=UUID.randomUUID().toString();var applicationBody=Map.of("title","Verified isolated contribution","description","Provider object and safety are explicit test doubles","fileIds",List.of(fileId),"acceptingOrganizationId",organization);
        var application=checked(201,call("POST",mine+"applications",user,applicationBody,Map.of("Idempotency-Key",createKey)));
        assertEquals(application,checked(201,call("POST",mine+"applications",user,applicationBody,Map.of("Idempotency-Key",createKey))));
        String applicationId=application.path("applicationId").asText();
        expect(200,"GET",admin+"files/"+fileId+"/access",reviewer,null);
        expect(404,"GET",mine+"applications/"+applicationId,outsider,null);
        expect(200,"GET",admin+"applications?page=1&pageSize=1",reviewer,null);
        var decision=Map.of("decision","APPROVED","awardedPoints",7,"reviewNote","verified controlled evidence");
        String reviewKey=UUID.randomUUID().toString(),decisionPath=admin+"applications/"+applicationId+"/decisions";
        var approved=checked(200,call("POST",decisionPath,reviewer,decision,Map.of("Idempotency-Key",reviewKey)));
        assertEquals(approved,checked(200,call("POST",decisionPath,reviewer,decision,Map.of("Idempotency-Key",reviewKey))));
        write(409,"POST",decisionPath,reviewer,decision,Map.of());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE application_id=? AND kind='GRANT'",Integer.class,Long.parseLong(applicationId)));
        assertEquals(7,expect(200,"GET",mine+"summary",user,null).path("balance").asInt());
        assertEquals(1,expect(200,"GET",mine+"ledger",user,null).path("totalItems").asInt());
        assertEquals(7,expect(200,"GET",admin+"statistics/users",reviewer,null).path("items").get(0).path("balance").asInt());
        assertEquals(7,expect(200,"GET",admin+"statistics/organizations",reviewer,null).path("items").get(0).path("balance").asInt());
        expect(404,"GET",admin+"statistics/users?organizationId="+UUID.randomUUID(),reviewer,null);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM point_authorization_audit WHERE application_id=? AND operation_name='REVIEW'",Integer.class,Long.parseLong(applicationId)));
        // A controlled but unknown nonparticipant is not a statistics candidate.
        var unknown=new PersonBO.Target("PERSON",Long.toString(OUTSIDER),null);
        control(PersonBO.Domain.POINTS,List.of(PersonBO.Action.READ),unknown);
        scope(REVIEWER,unknown,PersonBO.Domain.POINTS,List.of(PersonBO.Action.READ));
        duty(REVIEWER,"POINTS_PERSON_LEDGER",new UnifiedModels.Target("PERSON",Long.toString(OUTSIDER),null));
        assertEquals(1,expect(200,"GET",admin+"statistics/users",reviewer,null).path("totalItems").asInt());
        // Only the negative test creates an otherwise impossible unknown applicant directly.
        db.update("INSERT INTO point_application(applicant_user_id,title,description,file_ids,status,created_at) VALUES(?,'SYNTHETIC unknown participant','negative owning-policy fixture',JSON_ARRAY('not-a-real-file'),'PENDING',UTC_TIMESTAMP(6))",OUTSIDER);
        expect(503,"GET",admin+"statistics/users",reviewer,null);
        db.update("DELETE FROM point_application WHERE applicant_user_id=? AND title='SYNTHETIC unknown participant'",OUTSIDER);
        var nextUpload=write(200,"POST",mine+"file-upload-credentials",user,Map.of("files",List.of(Map.of("fileName","parallel.pdf","kind","PDF","contentType","application/pdf","sizeBytes",128))),Map.of());
        String nextFile=write(201,"POST",mine+"files",user,Map.of("uploadId",nextUpload.path("uploadId").asText(),"fileIndex",0),Map.of()).path("fileId").asText();
        var next=write(201,"POST",mine+"applications",user,Map.of("title","Concurrent review","description","real isolated HTTP concurrency","fileIds",List.of(nextFile),"acceptingOrganizationId",organization),Map.of());
        String nextId=next.path("applicationId").asText(),parallelPath=admin+"applications/"+nextId+"/decisions";
        var first=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try { return call("POST",parallelPath,reviewer,decision,Map.of("Idempotency-Key",UUID.randomUUID().toString())); }
            catch(Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
        var second=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try { return call("POST",parallelPath,reviewer,decision,Map.of("Idempotency-Key",UUID.randomUUID().toString())); }
            catch(Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
        var statuses=List.of(first.get(25,java.util.concurrent.TimeUnit.SECONDS).statusCode(),second.get(25,java.util.concurrent.TimeUnit.SECONDS).statusCode()).stream().sorted().toList();
        assertEquals(List.of(200,409),statuses);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE application_id=? AND kind='GRANT'",Integer.class,Long.parseLong(nextId)));
        revokeDuty(pointScope.scopeGrantIds().get(0));
        expect(404,"GET",admin+"applications/"+applicationId,reviewer,null);
        expect(404,"GET",admin+"files/"+fileId+"/access",reviewer,null);
        assertEquals(14,expect(200,"GET",mine+"summary",user,null).path("balance").asInt());
        var identityBeforeLoss=call("GET",PEOPLE+USER+"/identity",steward,null,Map.of());checked(200,identityBeforeLoss);
        write(200,"POST",PEOPLE+USER+"/identity-changes",steward,Map.of("kind","INVALIDATE","reason","Eligibility loss does not mutate history"),Map.of("If-Match",identityBeforeLoss.headers().firstValue("ETag").orElseThrow()));
        assertFalse(expect(200,"GET",mine+"eligibility",user,null).path("eligible").asBoolean());
        assertEquals(14,expect(200,"GET",mine+"summary",user,null).path("balance").asInt());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM point_application WHERE applicant_user_id=? AND status='APPROVED'",Integer.class,USER));
        write(403,"POST",mine+"applications",user,applicationBody,Map.of());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM point_authorization_audit WHERE policy_source<>'CONFIRMED_LOCAL_PARTY_V1'",Integer.class));
        JSON.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve("business-facts.json").toFile(),Map.of(
                "applications",db.queryForList("SELECT id,applicant_user_id,status,awarded_points,grant_ledger_id FROM point_application"),
                "ledger",db.queryForList("SELECT id,user_id,application_id,kind,points_delta FROM point_ledger"),
                "authorizationAudit",db.queryForList("SELECT * FROM point_authorization_audit")));
        // Formal catalog has no immutable role containing adjustment permissions: never invent one.
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rbac_role_permission WHERE permission_code IN ('points:adjust','points:adjust-approve')",Integer.class));
        expect(403,"POST","/v1/admin/points/ledger/1:reverse",reviewer,Map.of("reason","no formal reversal capability role"));
    }
    @Test void organizationStewardMaintainsOnlyRootAssignedOrganizationWithNoRequiredMaterial() throws Exception {
        long member=940001,other=940002;
        for(long id:List.of(member,other)) {
            db.update("INSERT INTO user_profile(id,openid,nickname,role,status) VALUES(?,?,?,1,1)",id,"policy-member-"+id,"Synthetic org member");
            db.update("INSERT INTO rbac_authorization_authority VALUES(?,'LEGACY',UUID(),UTC_TIMESTAMP(6))",id);
        }
        var body=Map.of("name","Organization policy A","description","isolated","reason","directory","verificationReference","test");
        String organization=write(201,"POST","/v1/admin/organizations",root,body,Map.of()).path("organizationId").asText();
        String outside=write(201,"POST","/v1/admin/organizations",root,body,Map.of()).path("organizationId").asText();
        for(long id:List.of(member,other)) {
            var previous=call("GET",PEOPLE+id+"/memberships",root,null,Map.of());checked(200,previous);
            write(200,"PUT",PEOPLE+id+"/memberships",root,Map.of("verification","VERIFIED","memberships",List.of(Map.of("organizationId",id==member?organization:outside)),"reason","Root confirms initial membership"),Map.of("If-Match",previous.headers().firstValue("ETag").orElseThrow()));
        }
        for(String domain:List.of("IDENTITY","ORGANIZATION")) {
            var scope=write(201,"POST",SCOPES,root,Map.of("granteeUserId",Long.toString(STEWARD),"target",Map.of("type","ORGANIZATION","organizationId",organization),"actionSet",Map.of("domain",domain,"actions",List.of("READ","MAINTAIN")),"reason","Root assigns this organization","verificationReference","test"),Map.of());
            assertEquals("ROOT_OWNER",scope.path("source").asText());
        }
        var identity=call("GET",PEOPLE+member+"/identity",steward,null,Map.of());checked(200,identity);
        var current=write(200,"POST",PEOPLE+member+"/identity-changes",steward,Map.of("kind","RECORD","stageId","FULL_PARTY_MEMBER","reason","Manual verification, no mandatory material"),Map.of("If-Match",identity.headers().firstValue("ETag").orElseThrow()));
        assertEquals("FULL_PARTY_MEMBER",current.path("currentRecord").path("stageId").asText());
        identity=call("GET",PEOPLE+member+"/identity",steward,null,Map.of());checked(200,identity);
        write(200,"POST",PEOPLE+member+"/identity-changes",steward,Map.of("kind","TRANSITION","stageId","PARTY_ACTIVIST","reason","Manual stage change without an ordering rule"),Map.of("If-Match",identity.headers().firstValue("ETag").orElseThrow()));
        assertEquals("MANUAL_CONFIRMATION_NO_MATERIAL",db.queryForObject("SELECT verification_reference FROM person_foundation_event WHERE aggregate_type='PERSON' AND aggregate_id=? AND operation='RECORD'",String.class,Long.toString(member)));
        var detail=call("GET","/v1/admin/organizations/"+organization,steward,null,Map.of());checked(200,detail);
        write(200,"PATCH","/v1/admin/organizations/"+organization,steward,Map.of("name","Updated by assigned steward","reason","Organization maintenance","verificationReference","test"),Map.of("If-Match",detail.headers().firstValue("ETag").orElseThrow()));
        expect(404,"GET","/v1/admin/organizations/"+outside,steward,null);
        expect(404,"GET",PEOPLE+other+"/identity",steward,null);
        write(403,"POST",PEOPLE+STEWARD+"/identity-changes",steward,Map.of("kind","MARK_UNKNOWN","reason","Self maintenance remains forbidden"),Map.of());
        var membership=call("GET",PEOPLE+member+"/memberships",steward,null,Map.of());checked(200,membership);
        checked(404,call("PUT",PEOPLE+member+"/memberships",steward,Map.of("verification","VERIFIED","memberships",List.of(Map.of("organizationId",organization),Map.of("organizationId",outside)),"reason","Cannot expand into unmanaged organization"),Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-Match",membership.headers().firstValue("ETag").orElseThrow())));
    }
    @Test void expiredRestrictionIsReadUnderOwnerLocksWithoutNormalizationOrNewTransaction() {
        db.update("UPDATE user_profile SET status=3,ban_expire_at=? WHERE id=?",LocalDateTime.now().minusSeconds(5),OUTSIDER);
        try {
            var transaction=new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status->{
                app.getBean(cn.jualn.miniapp.module.person.service.facts.PersonFactReaders.class).open(OUTSIDER);
                long started=System.nanoTime();
                app.getBean(cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess.class).require(OUTSIDER);
                assertTrue(System.nanoTime()-started<1_000_000_000L,"Read-only locked User helper must not wait on a REQUIRES_NEW self-lock");
                assertEquals(3,db.queryForObject("SELECT status FROM user_profile WHERE id=?",Integer.class,OUTSIDER));
            });
            assertEquals(3,db.queryForObject("SELECT status FROM user_profile WHERE id=?",Integer.class,OUTSIDER));
        } finally { db.update("UPDATE user_profile SET status=1,ban_expire_at=NULL WHERE id=?",OUTSIDER); }
    }
    @Test void r3ApplicantClosesEndedCycleWithoutEligibilityAndRejoinDoesNotReviveOldApplication() throws Exception {
        long applicant=930100,reviewA=930101,reviewB=930102;syntheticUser(applicant,false);syntheticUser(reviewA,true);syntheticUser(reviewB,true);
        String a=organization("R3 A"),b=organization("R3 B");qualify(a);qualify(b);person(applicant,List.of(a,b));
        duty(reviewA,"POINTS_REVIEW",new UnifiedModels.Target("ORGANIZATION",null,a));duty(reviewB,"POINTS_REVIEW",new UnifiedModels.Target("ORGANIZATION",null,b));
        String applicantToken=ordinary(applicant),aToken=qr(reviewA),bToken=qr(reviewB),mine="/v1/users/me/points/",admin="/v1/admin/points/applications/";
        assertEquals(2,expect(200,"GET",mine+"accepting-organizations",applicantToken,null).path("items").size());
        String file=material(applicantToken,"r3-cycle.pdf");
        var body=Map.of("title","Cycle bound application","description","new controlled acceptance basis","fileIds",List.of(file),"acceptingOrganizationId",a);
        var application=write(201,"POST",mine+"applications",applicantToken,body,Map.of());String id=application.path("applicationId").asText();
        String original=application.path("acceptanceBasis").path("membershipId").asText();assertFalse(original.isBlank());
        expect(404,"GET",admin+id,bToken,null);
        memberships(applicant,List.of(b));expect(404,"GET",admin+id,aToken,null);
        memberships(applicant,List.of(a,b));expect(404,"GET",admin+id,aToken,null);
        assertNotEquals(original,db.queryForObject("SELECT id FROM person_membership WHERE user_id=? AND organization_id=? AND ended_event_id IS NULL",String.class,applicant,a));
        memberships(applicant,List.of());var eligibility=expect(200,"GET",mine+"eligibility",applicantToken,null);assertFalse(eligibility.path("eligible").asBoolean());assertEquals("NO_ACCEPTING_ORGANIZATION",eligibility.path("reason").asText());
        identity(applicant,"INVALIDATE");
        String key=UUID.randomUUID().toString();var closed=checked(200,call("POST",mine+"applications/"+id+":close",applicantToken,Map.of("reason","old original cycle ended"),Map.of("Idempotency-Key",key)));
        assertEquals("CLOSED",closed.path("status").asText());assertFalse(closed.has("reviewerUserId"));
        assertEquals(closed,checked(200,call("POST",mine+"applications/"+id+":close",applicantToken,Map.of("reason","old original cycle ended"),Map.of("Idempotency-Key",key))));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE application_id=?",Integer.class,Long.parseLong(id)));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM point_authorization_audit WHERE application_id=? AND operation_name='CLOSE'",Integer.class,Long.parseLong(id)));
        expect(200,"GET",mine+"applications/"+id,applicantToken,null);expect(200,"GET",mine+"files/"+file,applicantToken,null);
        identity(applicant,"RECORD");
        memberships(applicant,List.of(b));String nextFile=material(applicantToken,"r3-new.pdf");
        var next=write(201,"POST",mine+"applications",applicantToken,Map.of("title","Current B application","description","new application, not a transfer","fileIds",List.of(nextFile),"acceptingOrganizationId",b),Map.of());
        expect(404,"GET",admin+next.path("applicationId").asText(),aToken,null);
        write(200,"POST",admin+next.path("applicationId").asText()+"/decisions",bToken,Map.of("decision","APPROVED","awardedPoints",3,"reviewNote","B current cycle reviewed"),Map.of());
        assertEquals(3,expect(200,"GET",mine+"summary",applicantToken,null).path("balance").asInt());
    }
    @Test void r3CurrentAndAcceptanceStatisticsPreserveHistoricalAttributionThroughTransferAndReversal() throws Exception {
        long applicant=930104,review=930105,secondRoot=930106;syntheticUser(applicant,false);syntheticUser(review,true);syntheticUser(secondRoot,true);
        String a=organization("R3 accounting A"),b=organization("R3 accounting B");qualify(a);qualify(b);person(applicant,List.of(a));
        duty(review,"POINTS_REVIEW",new UnifiedModels.Target("ORGANIZATION",null,a));
        for(String code:List.of("POINTS_STATS_CURRENT","POINTS_STATS_ACCEPTING")) for(String org:List.of(a,b)) duty(review,code,new UnifiedModels.Target("ORGANIZATION",null,org));
        String token=ordinary(applicant),reviewToken=qr(review),mine="/v1/users/me/points/",statistics="/v1/admin/points/statistics/";
        String file=material(token,"r3-ledger.pdf");var application=write(201,"POST",mine+"applications",token,Map.of("title","Historical A contribution","description","single personal ledger","fileIds",List.of(file),"acceptingOrganizationId",a),Map.of());
        var approved=write(200,"POST","/v1/admin/points/applications/"+application.path("applicationId").asText()+"/decisions",reviewToken,Map.of("decision","APPROVED","awardedPoints",10,"reviewNote","A contribution"),Map.of());
        verifyRoster(a);verifyRoster(b);
        assertEquals(10,expect(200,"GET",statistics+"organizations?organizationId="+a,reviewToken,null).path("items").get(0).path("balance").asInt());
        memberships(applicant,List.of(b));expect(503,"GET",statistics+"organizations?organizationId="+a,reviewToken,null);
        verifyRoster(a);verifyRoster(b);
        assertEquals(0,expect(200,"GET",statistics+"organizations?organizationId="+a,reviewToken,null).path("items").get(0).path("balance").asInt());
        assertEquals(10,expect(200,"GET",statistics+"organizations?organizationId="+b,reviewToken,null).path("items").get(0).path("balance").asInt());
        assertEquals(10,expect(200,"GET",statistics+"accepting-organizations?organizationId="+a,reviewToken,null).path("items").get(0).path("balance").asInt());
        assertEquals(0,expect(200,"GET",statistics+"accepting-organizations?organizationId="+b,reviewToken,null).path("items").get(0).path("balance").asInt());
        // Existing ROOT governance and explicit reverse/read scopes, not a fabricated combined role or self-grant.
        grantRbac(root,secondRoot,"ROOT","AUTHORIZATION");
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code IN ('points:adjust','points:adjust-approve')");
        dutyAs(secondRoot,ROOT,"POINTS_PERSON_LEDGER",new UnifiedModels.Target("PERSON",Long.toString(applicant),null));
        write(201,"POST","/v1/admin/person-management-scopes",qr(secondRoot),Map.of("granteeUserId",Long.toString(ROOT),"target",Map.of("type","PERSON","userId",Long.toString(applicant)),"actionSet",Map.of("domain","POINTS","actions",List.of("REVERSE")),"reason","explicit isolated reversal scope","verificationReference","approved-test"),Map.of());
        var reversed=write(201,"POST","/v1/admin/points/ledger/"+approved.path("grantLedgerId").asText()+":reverse",root,Map.of("reason","full controlled reversal"),Map.of());
        assertEquals(a,reversed.path("acceptanceBasis").path("organizationId").asText());
        assertEquals(0,expect(200,"GET",mine+"summary",token,null).path("balance").asInt());
        var totals=expect(200,"GET",statistics+"accepting-organizations?organizationId="+a,reviewToken,null).path("items").get(0);
        assertEquals(10,totals.path("totalGranted").asInt());assertEquals(10,totals.path("totalReversed").asInt());assertEquals(0,totals.path("balance").asInt());
        assertEquals(0,expect(200,"GET",statistics+"organizations?organizationId="+b,reviewToken,null).path("items").get(0).path("balance").asInt());
    }
    @Test void sameRoleDutyCodeCorruptionCannotChangeTheApprovedBusinessMeaning() throws Exception {
        long staff=930119;syntheticUser(staff,true);String organization=organization("Immutable duty proof");
        var granted=duty(staff,"POINTS_STATS_CURRENT",new UnifiedModels.Target("ORGANIZATION",null,organization));String scope=granted.scopeGrantIds().get(0);
        assertTrue(app.getBean(RbacAuthorizationReader.class).read(staff).allows("points:read","POINTS"));
        db.update("UPDATE person_business_scope SET duty_code='POINTS_STATS_ACCEPTING' WHERE id=?",scope);
        try {assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->app.getBean(RbacAuthorizationReader.class).read(staff)).getStatus());}
        finally {db.update("UPDATE person_business_scope SET duty_code='POINTS_STATS_CURRENT' WHERE id=?",scope);}
        assertTrue(app.getBean(RbacAuthorizationReader.class).read(staff).allows("points:read","POINTS"));
    }
    @Test void localPositiveAdmissionDoesNotPretendCompleteMembershipOrRoster() throws Exception {
        long applicant=930117,review=930118;syntheticUser(applicant,false);syntheticUser(review,true);String org=organization("Locally verified admission");qualify(org);identity(applicant,"RECORD");
        String local="/v1/admin/organizations/"+org+"/members/"+applicant;var unknown=call("GET",local,root,null,Map.of());checked(200,unknown);
        write(200,"POST",local+"/membership-changes",root,Map.of("kind","JOIN","reason","only this organization verified","verificationReference","isolated local proof"),Map.of("If-Match",unknown.headers().firstValue("ETag").orElseThrow()));
        assertEquals("UNKNOWN",expect(200,"GET",PEOPLE+applicant+"/memberships",root,null).path("verification").asText());
        for(String code:List.of("POINTS_REVIEW","POINTS_STATS_CURRENT","POINTS_STATS_ACCEPTING","PERSON_IDENTITY_MAINTAIN"))duty(review,code,new UnifiedModels.Target("ORGANIZATION",null,org));
        duty(review,"POINTS_PERSON_LEDGER",new UnifiedModels.Target("PERSON",Long.toString(applicant),null));
        String token=ordinary(applicant),r=qr(review),mine="/v1/users/me/points/",stats="/v1/admin/points/statistics/";
        assertTrue(expect(200,"GET",mine+"eligibility",token,null).path("eligible").asBoolean());expect(503,"GET",mine+"accepting-organizations",token,null);
        String file=material(token,"local-positive.pdf");var created=write(201,"POST",mine+"applications",token,Map.of("title","Explicit local accepting target","description","no complete membership claim","fileIds",List.of(file),"acceptingOrganizationId",org),Map.of());
        var approved=write(200,"POST","/v1/admin/points/applications/"+created.path("applicationId").asText()+"/decisions",r,Map.of("decision","APPROVED","awardedPoints",4,"reviewNote","same verified local cycle"),Map.of());
        verifyRoster(org);expect(503,"GET",stats+"organizations?organizationId="+org,r,null);expect(503,"GET","/v1/admin/points/ledger/"+approved.path("grantLedgerId").asText(),r,null);
        assertEquals(4,expect(200,"GET",stats+"accepting-organizations?organizationId="+org,r,null).path("items").get(0).path("balance").asInt());
        expect(503,"GET",PEOPLE+applicant+"/identity",r,null);
        duty(review,"PERSON_IDENTITY_MAINTAIN",new UnifiedModels.Target("PERSON",Long.toString(applicant),null));
        var direct=call("GET",PEOPLE+applicant+"/identity",r,null,Map.of());checked(200,direct);
        write(200,"POST",PEOPLE+applicant+"/identity-changes",r,Map.of("kind","INVALIDATE","reason","explicit person grant permits maintenance","verificationReference","isolated approval"),Map.of("If-Match",direct.headers().firstValue("ETag").orElseThrow()));
    }
    @Test void materialRegistrationRechecksEligibilityAfterExternalInspection() throws Exception {
        long applicant=930116;syntheticUser(applicant,false);String org=organization("Material admission race");qualify(org);person(applicant,List.of(org));String token=ordinary(applicant);
        var upload=write(200,"POST","/v1/users/me/points/file-upload-credentials",token,Map.of("files",List.of(Map.of("fileName","race.pdf","kind","PDF","contentType","application/pdf","sizeBytes",128))),Map.of());
        COS_INSPECTION_HOOK.set(()->{try {memberships(applicant,List.of());}catch(Exception failure){throw new IllegalStateException(failure);}});
        try {write(403,"POST","/v1/users/me/points/files",token,Map.of("uploadId",upload.path("uploadId").asText(),"fileIndex",0),Map.of());}
        finally {COS_INSPECTION_HOOK.set(null);}
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_point_file WHERE owner_user_id=? AND state IN ('REGISTERED','BOUND')",Integer.class,applicant));
        assertEquals(0,expect(200,"GET","/v1/users/me/points/summary",token,null).path("balance").asInt());
    }
    @Test void unifiedRoutesSupportPureDelegateQrAndCurrentDependencyRevocation() throws Exception {
        long delegate=930113,recipient=930114;syntheticUser(delegate,true);syntheticUser(recipient,true);
        String org=organization("Delegated org");qualify(org);
        String expiry=RbacTime.wire(LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0)),prefix="/v1/admin/authorization";
        var selection=new UnifiedModels.Selection("POINTS_REVIEW",new UnifiedModels.Target("ORGANIZATION",null,org),expiry,null);
        var authority=write(200,"POST",prefix+"/delegation-authorities",root,new UnifiedModels.AuthorityRequest(Long.toString(delegate),List.of(selection),"INDEPENDENT_APPROVAL",expiry,"isolated bounded authority"),Map.of());
        String d=qr(delegate);var options=expect(200,"GET",prefix+"/me/duty-options",d,null);assertFalse(options.path("canViewAdvancedGovernance").asBoolean());assertEquals(1,options.path("items").size());
        assertEquals(1,expect(200,"GET",prefix+"/delegation-authorities",d,null).path("items").size());
        var intent=new UnifiedModels.Intent("GRANT",Long.toString(recipient),List.of(selection),authority.path("authorityId").asText(),null,null,"isolated delegated appointment");
        var preview=write(200,"POST",prefix+"/duty-previews",d,intent,Map.of());expect(200,"GET",prefix+"/duty-previews/"+preview.path("previewId").asText(),d,null);
        var commit=new UnifiedModels.Commit(preview.path("previewId").asText(),intent);String key=UUID.randomUUID().toString();
        var receipt=write(200,"POST",prefix+"/duty-changes",d,commit,Map.of("Idempotency-Key",key));assertEquals(receipt,write(200,"POST",prefix+"/duty-changes",d,commit,Map.of("Idempotency-Key",key)));
        String receiver=qr(recipient);assertTrue(expect(200,"GET",prefix+"/users/"+recipient+"/effective-duties",receiver,null).path("items").get(0).path("effective").asBoolean());
        var revoke=new UnifiedModels.Intent("REVOKE",null,null,null,"DELEGATION_AUTHORITY",authority.path("authorityId").asText(),"upstream authority withdrawn");
        var removal=write(200,"POST",prefix+"/duty-previews",root,revoke,Map.of());write(200,"POST",prefix+"/duty-changes",root,new UnifiedModels.Commit(removal.path("previewId").asText(),revoke),Map.of());
        assertFalse(app.getBean(RbacAuthorizationReader.class).read(recipient).allows("points:review","POINTS"));
        assertEquals(0,expect(200,"GET",prefix+"/me/duty-options",d,null).path("items").size());
    }
    @Test void learningManagementAudienceAndIndependentReviewRemainBoundToOriginalTask() throws Exception {
        long manager=930107,review=930108,member=930109,bOnly=930110,noOrg=930111,unknown=930112;
        for(long id:List.of(manager,review,member,bOnly,noOrg,unknown))syntheticUser(id,id==manager||id==review);
        String a=organization("Learning A"),b=organization("Learning B");
        person(member,List.of(a,b));person(bOnly,List.of(b));person(noOrg,List.of());
        var manage=duty(manager,"LEARNING_MANAGE",new UnifiedModels.Target("ORGANIZATION",null,a));
        duty(review,"LEARNING_REVIEW",new UnifiedModels.Target("ORGANIZATION",null,a));
        String m=qr(manager),r=qr(review),u=ordinary(member),path="/v1/admin/learning-tasks";
        var body=new java.util.HashMap<String,Object>();body.put("title","Bound learning task");body.put("description","Independent result review");body.put("managementTarget",Map.of("type","ORGANIZATION","organizationId",a));
        body.put("requirements",Map.of("confirmationRequired",false,"minimumTextLength",1,"minimumFiles",0,"maximumFiles",0,"allowedFileKinds",List.of()));body.put("reviewRequired",true);body.put("firstDeadline",java.time.OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0).toString());
        body.put("audience",Map.of("userIds",List.of(Long.toString(member))));
        var created=write(201,"POST",path,m,body,Map.of());String task=created.path("taskId").asText(),url=path+"/"+task;
        assertEquals(a,created.path("managementTarget").path("organizationId").asText());
        var get=call("GET",url,m,null,Map.of());checked(200,get);String tag=get.headers().firstValue("ETag").orElseThrow();
        body.put("managementTarget",Map.of("type","ORGANIZATION","organizationId",b));write(404,"POST",path,m,body,Map.of());
        body.put("managementTarget",Map.of("type","PLATFORM"));write(404,"POST",path,m,body,Map.of());
        duty(manager,"LEARNING_MANAGE",new UnifiedModels.Target("ORGANIZATION",null,b));
        for(long candidate:List.of(bOnly,noOrg,unknown)) {
            var audience=Map.of("userIds",List.of(Long.toString(candidate)));
            var changed=call("PATCH",url,m,Map.of("audience",audience),Map.of("If-Match",tag));checked(200,changed);tag=changed.headers().firstValue("ETag").orElseThrow();
            write(candidate==unknown?503:404,"POST",url+"/audience-previews",m,Map.of("purpose","PUBLISH","audience",audience),Map.of("If-Match",tag));
        }
        var filtered=Map.of("filters",List.of(Map.of("organizationIds",List.of(a),"developmentStageIds",List.of("PARTY_ACTIVIST"))));
        var changedFilter=call("PATCH",url,m,Map.of("audience",filtered),Map.of("If-Match",tag));checked(200,changedFilter);tag=changedFilter.headers().firstValue("ETag").orElseThrow();
        write(503,"POST",url+"/audience-previews",m,Map.of("purpose","PUBLISH","audience",filtered),Map.of("If-Match",tag));
        verifyRoster(a);
        assertEquals(1,write(201,"POST",url+"/audience-previews",m,Map.of("purpose","PUBLISH","audience",filtered),Map.of("If-Match",tag)).path("resolvedCount").asInt());
        var approvedScope=Map.of("authorizedScopeId",manage.scopeGrantIds().get(0));
        var changedScope=call("PATCH",url,m,Map.of("audience",approvedScope),Map.of("If-Match",tag));checked(200,changedScope);tag=changedScope.headers().firstValue("ETag").orElseThrow();
        assertEquals(1,write(201,"POST",url+"/audience-previews",m,Map.of("purpose","PUBLISH","audience",approvedScope),Map.of("If-Match",tag)).path("resolvedCount").asInt());
        for(var unready:List.of(Map.of("filters",List.of(Map.of("organizationIds",List.of(a),"identityIds",List.of("unmapped-identity")))),Map.of("filters",List.of(Map.of("organizationIds",List.of(a),"developmentStageIds",List.of("UNKNOWN_STAGE")))))) {
            var changed=call("PATCH",url,m,Map.of("audience",unready),Map.of("If-Match",tag));checked(200,changed);tag=changed.headers().firstValue("ETag").orElseThrow();
            write(503,"POST",url+"/audience-previews",m,Map.of("purpose","PUBLISH","audience",unready),Map.of("If-Match",tag));
        }
        var restored=call("PATCH",url,m,Map.of("audience",Map.of("userIds",List.of(Long.toString(member)))),Map.of("If-Match",tag));checked(200,restored);tag=restored.headers().firstValue("ETag").orElseThrow();

        var preview=write(201,"POST",url+"/audience-previews",m,Map.of("purpose","PUBLISH","audience",Map.of("userIds",List.of(Long.toString(member)))),Map.of("If-Match",tag));
        var published=write(200,"POST",url+":publish",m,Map.of("previewId",preview.path("previewId").asText()),Map.of("If-Match",tag));
        assertEquals("PUBLISHED",published.path("status").asText());
        var legacyBody=new java.util.HashMap<String,Object>(body);legacyBody.put("managementTarget",Map.of("type","ORGANIZATION","organizationId",a));legacyBody.put("title","Legacy assignment continuity");legacyBody.put("requirements",Map.of("confirmationRequired",true,"minimumTextLength",0,"minimumFiles",0,"maximumFiles",0,"allowedFileKinds",List.of()));legacyBody.put("reviewRequired",false);
        var legacy=write(201,"POST",path,m,legacyBody,Map.of());String legacyId=legacy.path("taskId").asText(),legacyUrl=path+"/"+legacyId;
        var legacyGet=call("GET",legacyUrl,m,null,Map.of());checked(200,legacyGet);String legacyTag=legacyGet.headers().firstValue("ETag").orElseThrow();
        var legacyPreview=write(201,"POST",legacyUrl+"/audience-previews",m,Map.of("purpose","PUBLISH","audience",Map.of("userIds",List.of(Long.toString(member)))),Map.of("If-Match",legacyTag));
        write(200,"POST",legacyUrl+":publish",m,Map.of("previewId",legacyPreview.path("previewId").asText()),Map.of("If-Match",legacyTag));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("UPDATE learning_task SET management_target=JSON_OBJECT('invalid',true) WHERE id=?",legacyId));
        db.update("UPDATE learning_task SET management_target=NULL WHERE id=?",legacyId);expect(503,"GET",legacyUrl,m,null);
        memberships(member,List.of(b));
        var legacyOwn=call("GET","/v1/users/me/learning-tasks/"+legacyId,u,null,Map.of());checked(200,legacyOwn);
        assertEquals("COMPLETED",write(200,"POST","/v1/users/me/learning-tasks/"+legacyId+":confirm",u,Map.of("confirmed",true),Map.of("If-Match",legacyOwn.headers().firstValue("ETag").orElseThrow())).path("assignment").path("progress").asText());
        long otherReviewer=930115;syntheticUser(otherReviewer,true);duty(otherReviewer,"LEARNING_REVIEW",new UnifiedModels.Target("ORGANIZATION",null,b));String otherToken=qr(otherReviewer);
        var own=call("GET","/v1/users/me/learning-tasks/"+task,u,null,Map.of());checked(200,own);
        var submission=write(201,"POST","/v1/users/me/learning-tasks/"+task+"/submissions",u,Map.of("text","Finished after transfer"),Map.of("If-Match",own.headers().firstValue("ETag").orElseThrow()));
        String result="/v1/admin/learning-task-submissions/"+submission.path("submissionId").asText();
        expect(403,"GET",result,m,null);expect(404,"GET",result,otherToken,null);
        var queue=expect(200,"GET","/v1/admin/learning-task-submissions?taskId="+task,r,null);assertEquals(1,queue.path("totalItems").asInt());
        var detail=call("GET",result,r,null,Map.of());checked(200,detail);
        write(200,"POST",result+":review",r,Map.of("decision","APPROVE"),Map.of("If-Match",detail.headers().firstValue("ETag").orElseThrow()));
        assertEquals("COMPLETED",expect(200,"GET","/v1/users/me/learning-tasks/"+task,u,null).path("assignment").path("progress").asText());
        expect(403,"GET",PEOPLE+member+"/identity",r,null);
        revokeDuty(manage.scopeGrantIds().get(0));expect(404,"GET",url,m,null);
        // Reviewer has no task management scope, but retains access to this original task's result only.
        expect(200,"GET",result,r,null);expect(404,"GET",url,r,null);
    }
    void control(PersonBO.Domain domain,List<PersonBO.Action> actions,PersonBO.Target target) throws Exception {
        control(GOVERNOR,domain,actions,target);
    }
    void control(long grantee,PersonBO.Domain domain,List<PersonBO.Action> actions,PersonBO.Target target) throws Exception {
        var plan=controls.inspect(ROOT,grantee,domain,actions,target,null,"synthetic-executor","synthetic-independent-approval","a".repeat(64),"approved isolated control",null);
        assertEquals("VALIDATED_NOT_EXECUTED",controls.verify(plan));byte[] bytes=JSON.writeValueAsBytes(plan);
        controls.execute(plan,RbacCatalog.hash(bytes),bytes);
    }
    JsonNode scope(long grantee,PersonBO.Target target,PersonBO.Domain domain,List<PersonBO.Action> actions) throws Exception {
        var t="PERSON".equals(target.type())?Map.of("type","PERSON","personId",target.personId()):Map.of("type","ORGANIZATION","organizationId",target.organizationId());
        return write(201,"POST",SCOPES,governor,Map.of("granteeUserId",Long.toString(grantee),"target",t,"actionSet",Map.of("domain",domain.name(),"actions",actions),"reason","approved isolated operate","verificationReference","test-evidence"),Map.of());
    }
    void grantRbac(String actor,long who,String role,String namespace) throws Exception {
        String path=AUTH+"/users/"+who+"/role-assignments";
        var version=expect(200,"GET",path,actor,null).path("authorizationVersion").asText();
        write(201,"POST",path,actor,Map.of("roleCode",role,"namespace",namespace,"scope",Map.of("type","GLOBAL"),"reason","approved isolated integration","expectedAuthorizationVersion",version),Map.of());
    }
    UnifiedModels.Receipt duty(long grantee,String code,UnifiedModels.Target target) {
        return dutyAs(ROOT,grantee,code,target);
    }
    UnifiedModels.Receipt dutyAs(long actor,long grantee,String code,UnifiedModels.Target target) {
        String token;try {token=actor==ROOT?root:qr(actor);}catch(Exception failure){throw new IllegalStateException(failure);}
        String rootAssignment=app.getBean(RbacAuthorizationReader.class).read(actor).grants().stream().filter(g->"ROOT".equals(g.roleCode())).findFirst().orElseThrow().assignmentId();
        var selection=new UnifiedModels.Selection(code,target,RbacTime.wire(LocalDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0)),"LEARNING_MANAGE".equals(code)?List.of(target):null);
        var intent=new UnifiedModels.Intent("GRANT",Long.toString(grantee),List.of(selection),rootAssignment,null,null,"explicit isolated duty approval");
        try {
            var preview=write(200,"POST","/v1/admin/authorization/duty-previews",token,intent,Map.of());
            return JSON.treeToValue(write(200,"POST","/v1/admin/authorization/duty-changes",token,new UnifiedModels.Commit(preview.path("previewId").asText(),intent),Map.of()),UnifiedModels.Receipt.class);
        } catch(Exception failure){throw new IllegalStateException(failure);}

    }
    void syntheticUser(long id,boolean admin) {
        db.update("INSERT INTO user_profile(id,openid,nickname,role,status) VALUES(?,?,?,1,1)",id,"points-integration-"+id,"EXPLICIT SYNTHETIC R3 "+id);
        db.update("INSERT INTO rbac_authorization_authority VALUES(?,?,UUID(),UTC_TIMESTAMP(6))",id,admin?"ASSIGNMENT":"LEGACY");
    }
    String organization(String name) throws Exception {
        return write(201,"POST","/v1/admin/organizations",root,Map.of("name",name,"type","PARTY_BRANCH","description","isolated fixture","reason","explicit synthetic directory","verificationReference","test-source"),Map.of()).path("organizationId").asText();
    }
    String subject(long account) throws Exception {
        String known=subjects.get(account);if(known!=null) return known;
        var profile=write(201,"POST","/v1/admin/persons",root,Map.of("realName","EXPLICIT PERSON "+account,"reason","explicit isolated Person fixture","verificationReference","synthetic-source"),Map.of());
        String person=profile.path("personId").asText();assertFalse(person.matches("[0-9]+"));
        var personSlot=expect(200,"GET",PEOPLE+person+"/binding",root,null);var userSlot=expect(200,"GET","/v1/admin/person-account-bindings/"+account,root,null);
        write(201,"POST","/v1/admin/user-person-bindings",root,Map.of("personId",person,"userId",Long.toString(account),"personVersion",profile.path("version").asText(),"personBindingVersion",personSlot.path("version").asText(),"userBindingVersion",userSlot.path("version").asText(),"reason","explicit independent binding fixture","verificationReference","synthetic-source"),Map.of());
        subjects.put(account,person);return person;
    }
    JsonNode currentBinding(String person) throws Exception {return expect(200,"GET",PEOPLE+person+"/binding",root,null).path("binding");}
    void moveBinding(String person,long account) throws Exception {
        var old=currentBinding(person);var profile=expect(200,"GET",PEOPLE+person,root,null);var personSlot=expect(200,"GET",PEOPLE+person+"/binding",root,null);var accountSlot=expect(200,"GET","/v1/admin/person-account-bindings/"+account,root,null);
        write(201,"POST","/v1/admin/user-person-binding-changes",root,Map.of("endedBindings",List.of(Map.of("bindingId",old.path("bindingId").asText(),"version",old.path("version").asText())),"personId",person,"userId",Long.toString(account),"personVersion",profile.path("version").asText(),"personBindingVersion",personSlot.path("version").asText(),"userBindingVersion",accountSlot.path("version").asText(),"reason","atomic account change","verificationReference","synthetic-source"),Map.of());
    }
    void identity(long user,String kind) throws Exception {
        var current=call("GET",PEOPLE+subject(user)+"/identity",root,null,Map.of());checked(200,current);
        var body=new java.util.HashMap<String,Object>();body.put("kind",kind);body.put("reason","explicit synthetic identity change");body.put("verificationReference","test-source");if("RECORD".equals(kind))body.put("stageId","PARTY_ACTIVIST");
        write(200,"POST",PEOPLE+subject(user)+"/identity-changes",root,body,Map.of("If-Match",current.headers().firstValue("ETag").orElseThrow()));
    }
    void person(long user,List<String> organizations) throws Exception {identity(user,"RECORD");memberships(user,organizations);}
    void memberships(long user,List<String> organizations) throws Exception {
        var current=call("GET",PEOPLE+subject(user)+"/memberships",root,null,Map.of());checked(200,current);
        write(200,"PUT",PEOPLE+subject(user)+"/memberships",root,Map.of("verification","VERIFIED","memberships",organizations.stream().map(o->Map.of("organizationId",o)).toList(),"reason","explicit complete synthetic memberships","verificationReference","test-source"),Map.of("If-Match",current.headers().firstValue("ETag").orElseThrow()));
    }
    String material(String token,String name) throws Exception {
        var upload=write(200,"POST","/v1/users/me/points/file-upload-credentials",token,Map.of("files",List.of(Map.of("fileName",name,"kind","PDF","contentType","application/pdf","sizeBytes",128))),Map.of());
        return write(201,"POST","/v1/users/me/points/files",token,Map.of("uploadId",upload.path("uploadId").asText(),"fileIndex",0),Map.of()).path("fileId").asText();
    }
    void revokeDuty(String scope) {
        var service=app.getBean(UnifiedAuthorizationService.class);var intent=new UnifiedModels.Intent("REVOKE",null,null,null,"SCOPE",scope,"isolated source withdrawal");
        var preview=service.preview(ROOT,intent,UUID.randomUUID().toString());service.commit(ROOT,new UnifiedModels.Commit(preview.previewId(),intent),UUID.randomUUID().toString());
    }
    void qualify(String organization) {
        String evidence=UUID.randomUUID().toString();
        db.update("INSERT INTO point_acceptance_evidence VALUES(?,?,'VERIFIED',1,'EXPLICIT_SYNTHETIC_ACCEPTANCE_FIXTURE',UTC_TIMESTAMP(6))",evidence,organization);
        db.update("INSERT INTO point_organization_acceptance VALUES(?,?)",organization,evidence);
    }
    void verifyRoster(String organization) {
        var ids=db.queryForList("SELECT id FROM person_membership WHERE organization_id=? AND ended_event_id IS NULL AND valid_from<=UTC_TIMESTAMP(6) AND (valid_until IS NULL OR valid_until>UTC_TIMESTAMP(6)) ORDER BY id",String.class,organization);
        String digest=RbacCatalog.hash(String.join(",",ids).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        db.update("INSERT INTO person_roster_completeness VALUES(?,'VERIFIED',?,?, 'EXPLICIT_SYNTHETIC_COMPLETE_ROSTER') ON DUPLICATE KEY UPDATE membership_digest=VALUES(membership_digest),version=VALUES(version)",organization,UUID.randomUUID().toString(),digest);
    }
    String ordinary(long who) throws Exception {return expect(200,"POST","/v1/auth/login",null,Map.of("code","points-"+who)).path("data").path("token").asText();}
    String qr(long who) throws Exception {
        String ordinary=ordinary(who);var created=expect(201,"POST","/v1/admin/auth/qr-login-sessions",null,null);
        String scene=SCENE.get();expect(200,"POST","/v1/admin/auth/qr-login-scans",ordinary,Map.of("sceneCode",scene));
        expect(200,"POST","/v1/admin/auth/qr-login-scans:confirm",ordinary,Map.of("sceneCode",scene));
        String token=checked(200,call("POST","/v1/admin/auth/qr-login-sessions/"+created.path("session").path("sessionId").asText()+":consume",null,null,Map.of("X-Admin-Login-Secret",created.path("pollSecret").asText(),"X-Admin-Identity-Representation","RBAC_V1"))).path("token").asText();
        assertFalse(token.isBlank());issued.add(token);return token;
    }
    JsonNode write(int status,String method,String path,String token,Object body,Map<String,String> headers) throws Exception {
        var h=new HashMap<>(headers);h.putIfAbsent("Idempotency-Key",UUID.randomUUID().toString());return checked(status,call(method,path,token,body,h));
    }
    JsonNode expect(int status,String method,String path,String token,Object body) throws Exception {return checked(status,call(method,path,token,body,Map.of()));}
    JsonNode checked(int status,HttpResponse<String> response) throws Exception {assertEquals(status,response.statusCode(),response.uri().getPath()+" "+response.body());return JSON.readTree(response.body());}
    HttpResponse<String> call(String method,String path,String token,Object body,Map<String,String> headers) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20));if(token!=null) request.header("Authorization","Bearer "+token);headers.forEach(request::header);
        if(body!=null)request.header("Content-Type","application/json");
        var response=HTTP.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        boolean credentials=path.contains("/auth/") || path.endsWith("file-upload-credentials") || path.endsWith("/access");
        wire.add(Map.of("method",method,"path",path,"status",response.statusCode(),"body",credentials?"<credentials redacted>":JSON.readTree(response.body()),"cacheControl",response.headers().firstValue("Cache-Control").orElse("")));
        return response;
    }
    @Configuration(proxyBeanMethods=false) static class Fixture {
        @Bean @Primary cn.jualn.miniapp.module.article.service.ArticleSafetyChecks isolatedArticleSafety() {return new cn.jualn.miniapp.module.article.service.ArticleSafetyChecks() {
            public void text(long actor,String text) {assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());}
            public void media(long actor,long id,java.util.List<cn.jualn.miniapp.module.media.bo.MediaAttachmentBO> media) {assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());}
        };}
        @Bean @Primary cn.jualn.miniapp.module.officialcontent.service.OfficialContentSafetyChecks isolatedOfficialSafety() {return new cn.jualn.miniapp.module.officialcontent.service.OfficialContentSafetyChecks() {
            public void text(long actor,String text) {assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());}
            public void media(long actor,long id,java.util.List<cn.jualn.miniapp.module.media.bo.MediaAttachmentBO> media) {assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());var hook=OFFICIAL_SAFETY_HOOK.getAndSet(null);if(hook!=null)hook.run();}
        };}
        @Bean @Primary cn.jualn.miniapp.module.learningtask.service.LearningTaskTextSafety isolatedLearningSafety() {return (actor,text)->{if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Safety must run outside transaction");};}
        @Bean @Primary WxClient integrationWx() {
            WxClient wx=mock(WxClient.class);
            when(wx.getMiniSession(anyString())).thenAnswer(i->{String code=i.getArgument(0);if(!code.matches("points-930(?:00[1-6]|1[0-2][0-9])"))throw new IllegalArgumentException("Synthetic users only");var session=new MiniSessionResponse();session.setOpenid("points-integration-"+code.substring(7));session.setSessionKey("synthetic-only");return session;});
            when(wx.generateMiniProgramCode(anyString(),anyString(),anyString(),anyBoolean())).thenAnswer(i->{SCENE.set(i.getArgument(1));var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB),"PNG",bytes);return bytes.toByteArray();});
            return wx;
        }
        @Bean @Primary PointMaterialProvider isolatedCosProvider() { return new PointMaterialProvider() {
            public PointMaterialBO.Credentials credentials(String id,List<String> keys,LocalDateTime expires) {return new PointMaterialBO.Credentials("synthetic-private","local",keys,"synthetic-id","synthetic-secret","synthetic-token",expires.toEpochSecond(ZoneOffset.UTC),"https://synthetic.invalid",id,expires);}
            public void retainAndInspect(String source,String retained,long owner,PointMaterialBO.Declaration declaration) {assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());var hook=COS_INSPECTION_HOOK.getAndSet(null);if(hook!=null)hook.run(); /* Explicit external object + safety provider double. */ }
            public PointMaterialBO.Access access(String key,LocalDateTime expires) {return new PointMaterialBO.Access("https://synthetic.invalid/private/"+key+"?synthetic-signed",expires);}
            public void delete(String key) {}
        };}
    }
}


