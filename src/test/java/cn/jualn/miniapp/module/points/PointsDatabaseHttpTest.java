package cn.jualn.miniapp.module.points;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.config.TimeConfig;
import cn.jualn.miniapp.module.points.bo.PointsBO.*;
import cn.jualn.miniapp.module.points.controller.*;
import cn.jualn.miniapp.module.points.mapper.PointsMapper;
import cn.jualn.miniapp.module.points.service.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.request.*;
import static cn.jualn.miniapp.module.points.PointsProblems.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL8 migrations, Spring transactions, Mapper, loopback HTTP and production Jackson.
 * Identity/scope, private file retention and durable notice acceptance are EXPLICIT TEST DOUBLES.
 * This is mechanism evidence, not full Sa/RBAC/provider integration or HTTP READY acceptance. */
@EnabledIfSystemProperty(named="points.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/points_v1_test(?:_[a-z0-9]+)?")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsDatabaseHttpTest {
    ConfigurableApplicationContext context;
    JdbcTemplate db;
    PointsService service;
    TestDependencies deps;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final List<Map<String,Object>> responses=new ArrayList<>();
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String root;
    long nextUser=100;
    static final String TEST_ORG="ea6d7091-cd4a-4985-b858-e91662ff7012";

    static String connection(String url) { return url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC"; }
    static DataSource source(String url) { return new DriverManagerDataSource(connection(url),"root",""); }
    @BeforeAll void start() {
        String url=System.getProperty("points.test.jdbcUrl");
        assertTrue(url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/points_v1_test(?:_[a-z0-9]+)?"));
        String database=url.substring(url.lastIndexOf('/')+1),upgradeDatabase=database.replace("points_v1_test","points_v1_upgrade");
        var admin=new JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        // Fixed, disposable-only schemas must NOT already exist. Never drop/rebuild to resolve migration conflicts.
        admin.execute("CREATE DATABASE "+database);admin.execute("CREATE DATABASE "+upgradeDatabase);
        var fresh=Flyway.configure().dataSource(source(url)).load();fresh.migrate();fresh.validate();
        String upgrade=url.replace("points_v1_test","points_v1_upgrade");
        Flyway.configure().dataSource(source(upgrade)).target("46").load().migrate();
        var prior=new JdbcTemplate(source(upgrade));prior.update("INSERT INTO user_profile(id,openid,nickname) VALUES(777,'points-upgrade-sentinel','preserve')");
        var next=Flyway.configure().dataSource(source(upgrade)).load();next.migrate();next.validate();
        assertEquals("preserve",prior.queryForObject("SELECT nickname FROM user_profile WHERE id=777",String.class));
        assertEquals(4,prior.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name IN ('point_application','point_ledger','point_request','point_subject_lock')",Integer.class,upgradeDatabase));
        context=SpringApplication.run(Config.class,"--server.address=127.0.0.1","--server.port=0",
                "--spring.config.location=optional:classpath:/points-test-no-config.yml","--spring.main.banner-mode=off","--logging.level.root=WARN");
        db=context.getBean(JdbcTemplate.class);service=context.getBean(PointsService.class);deps=context.getBean(TestDependencies.class);
        root="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
        db.execute("CREATE TABLE points_test_file(file_id VARCHAR(128) PRIMARY KEY,owner_id BIGINT NOT NULL,application_id BIGINT NOT NULL)");
        db.execute("CREATE TABLE points_test_notice(fact_key VARCHAR(128) PRIMARY KEY,user_id BIGINT NOT NULL)");
        db.update("INSERT INTO user_profile(id,openid) VALUES(9,'points-reviewer9'),(10,'points-reviewer10')");
        String orgEvent=syntheticEvent("ORGANIZATION",TEST_ORG);
        db.update("INSERT INTO person_organization VALUES(?,'CORE TEST ONLY','Explicit owner double','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",TEST_ORG,orgEvent);
        // Programmatic RR entry is verified by atomic rollback and competing write cases below.
    }
    @AfterAll void stop() throws Exception {
        if(context!=null) context.close();
        Files.createDirectories(Path.of("target/points-v1"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/points-v1/http-responses.json").toFile(),responses);
    }
    @BeforeEach void reset() {
        deps.actor.set(9L);deps.ineligible.clear();deps.denied.clear();deps.failBind.set(false);deps.failNotice.set(false);deps.failFiles.set(false);
    }
    long person() { long id=nextUser++;db.update("INSERT INTO user_profile(id,openid) VALUES(?,?)",id,"points-person-"+id);String event=syntheticEvent("PERSON",Long.toString(id));db.update("INSERT INTO person_membership(id,user_id,organization_id,valid_from,created_event_id) VALUES(UUID(),?,?,UTC_TIMESTAMP(6),?)",id,TEST_ORG,event);return id; }
    // Explicit Person-owner double data. Core tests do not prove trusted Person policy.
    String syntheticEvent(String type,String aggregate) {
        String event=UUID.randomUUID().toString(),receipt=UUID.randomUUID().toString();
        db.update("INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id) VALUES(?,?,?,1,'TEST_FIXTURE',JSON_OBJECT('synthetic',true),9,UTC_TIMESTAMP(6),'isolated core fixture','ISOLATED_CORE_OWNER_DOUBLE','APPROVED_BOOTSTRAP',?)",event,type,aggregate,receipt);
        db.update("INSERT INTO person_command_receipt(id,actor,operation,target,command_key,fingerprint,result_json,etag,status,event_id,created_at) VALUES(?,9,'TEST_FIXTURE',?,?,REPEAT('a',64),JSON_OBJECT('synthetic',true),'fixture',201,?,UTC_TIMESTAMP(6))",receipt,aggregate,UUID.randomUUID().toString(),event);
        return event;
    }
    String key() { return UUID.randomUUID().toString(); }
    Create command() { return new Create("志愿服务","本次证明申请",List.of("file-"+key()),TEST_ORG); }
    Application create(long user) { deps.actor.set(user);return service.create(command(),key()); }
    Application approve(Application app) { deps.actor.set(9L);return service.review(app.applicationId(),new Review(Status.APPROVED,12,"核对证明"),key()); }
    void problem(String suffix,Runnable work) { assertEquals("/problems/points-"+suffix,assertThrows(ContractProblemException.class,work::run).getType()); }
    long count(String sql,Object... args) { return db.queryForObject(sql,Long.class,args); }

    @Test void idempotencyBindingAndDifferentIntent() {
        long user=person();deps.actor.set(user);Create command=command();String key=key();
        var first=service.create(command,key);assertEquals(first,service.create(command,key));
        assertEquals(1,count("SELECT COUNT(*) FROM point_application WHERE applicant_user_id=?",user));
        assertEquals(1,count("SELECT COUNT(*) FROM points_test_file WHERE application_id=?",first.applicationId()));
        problem("idempotency-conflict",()->service.create(command(),key));
        var second=service.create(command(),key());assertNotEquals(first.applicationId(),second.applicationId());
        deps.ineligible.add(user);assertEquals(first,service.create(command,key));
        problem("ineligible",()->service.create(command(),key()));
        deps.actor.set(9L);assertThrows(ContractProblemException.class,()->service.mine(first.applicationId()));
    }
    @Test void approveReverseReplayAndHistoricalRead() {
        long user=person();var pending=create(user);deps.actor.set(9L);
        String reviewKey=key();Review decision=new Review(Status.APPROVED,12,"材料已核实");
        var approved=service.review(pending.applicationId(),decision,reviewKey);
        deps.denied.add(user);
        assertEquals("/problems/not-found",assertThrows(ContractProblemException.class,()->service.review(pending.applicationId(),decision,reviewKey)).getType());
        deps.denied.clear();
        deps.ineligible.add(user);
        assertEquals(approved,service.review(pending.applicationId(),decision,reviewKey));
        problem("application-finalized",()->service.review(pending.applicationId(),decision,key()));
        deps.extraAdmin=user;deps.actor.set(user);
        problem("self-review-forbidden",()->service.reverse(approved.grantLedgerId(),new Reversal("纠错","synthetic test evidence"),key()));
        deps.extraAdmin=-1;deps.actor.set(9L);
        String reversalKey=key();var reversal=service.reverse(approved.grantLedgerId(),new Reversal("纠错","synthetic test evidence"),reversalKey);
        assertEquals(-12,reversal.pointsDelta());assertEquals(reversal,service.reverse(approved.grantLedgerId(),new Reversal("纠错","synthetic test evidence"),reversalKey));
        assertEquals(approved,service.review(pending.applicationId(),decision,reviewKey));
        problem("grant-already-reversed",()->service.reverse(approved.grantLedgerId(),new Reversal("纠错","synthetic test evidence"),key()));
        problem("reversal-target-invalid",()->service.reverse(reversal.ledgerId(),new Reversal("纠错","synthetic test evidence"),key()));
        deps.actor.set(user);var summary=service.summary();
        assertEquals(0,summary.balance());assertEquals(12,summary.totalGranted());assertEquals(12,summary.totalReversed());
        assertEquals(Status.APPROVED,service.mine(pending.applicationId()).status());
        assertEquals(2,service.ledgers(new Query(1,1,null,null)).totalItems());
        assertEquals(1,service.ledgers(new Query(2,1,null,null)).items().size());
        assertEquals(1,service.ledgers(new Query(1,20,null,Kind.REVERSAL)).totalItems());
    }
    @Test void rejectionScopeSelfReviewAndChangedEligibility() {
        long user=person();var pending=create(user);deps.actor.set(user);
        // Test provider permits admin action for this explicit synthetic actor to exercise self guard.
        deps.extraAdmin=user;problem("self-review-forbidden",()->service.review(pending.applicationId(),new Review(Status.APPROVED,1,"x"),key()));
        deps.extraAdmin=-1;deps.actor.set(9L);deps.denied.add(user);
        assertEquals("/problems/not-found",assertThrows(ContractProblemException.class,()->service.adminApplication(pending.applicationId())).getType());
        deps.denied.clear();deps.ineligible.add(user);
        problem("ineligible",()->service.review(pending.applicationId(),new Review(Status.APPROVED,1,"x"),key()));
        var rejected=service.review(pending.applicationId(),new Review(Status.REJECTED,null,"证明不完整"),key());
        assertEquals(Status.REJECTED,rejected.status());assertEquals(0,count("SELECT COUNT(*) FROM point_ledger WHERE application_id=?",pending.applicationId()));
    }
    @Test void transactionFailuresRollbackBothFactsAndReceipts() {
        long user=person();deps.actor.set(user);var body=command();deps.failBind.set(true);
        assertThrows(IllegalStateException.class,()->service.create(body,key()));
        assertEquals(0,count("SELECT COUNT(*) FROM point_application WHERE applicant_user_id=?",user));
        assertEquals(0,count("SELECT COUNT(*) FROM points_test_file WHERE owner_id=?",user));
        deps.failBind.set(false);var pending=create(user);deps.actor.set(9L);deps.failNotice.set(true);String reviewKey=key();
        assertThrows(IllegalStateException.class,()->service.review(pending.applicationId(),new Review(Status.APPROVED,12,"核准"),reviewKey));
        assertEquals("PENDING",db.queryForObject("SELECT status FROM point_application WHERE id=?",String.class,pending.applicationId()));
        assertEquals(0,count("SELECT COUNT(*) FROM point_ledger WHERE application_id=?",pending.applicationId()));
        assertEquals(0,count("SELECT COUNT(*) FROM point_request WHERE target_id=? AND operation_name='reviewPointApplication'",pending.applicationId()));
        // Fail precisely BETWEEN GRANT insertion and APPROVED update, with real DB rollback.
        deps.failNotice.set(false);
        db.execute("CREATE TRIGGER points_test_approval_failure BEFORE UPDATE ON point_application FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic approval write failure'");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->service.review(pending.applicationId(),new Review(Status.APPROVED,12,"核准"),reviewKey));
            assertEquals(0,count("SELECT COUNT(*) FROM point_ledger WHERE application_id=?",pending.applicationId()));
        } finally { db.execute("DROP TRIGGER points_test_approval_failure"); }
        deps.failNotice.set(false);var approved=approve(pending);deps.failNotice.set(true);
        assertThrows(IllegalStateException.class,()->service.reverse(approved.grantLedgerId(),new Reversal("更正","synthetic test evidence"),key()));
        assertEquals(0,count("SELECT COUNT(*) FROM point_ledger WHERE original_ledger_id=?",approved.grantLedgerId()));
        deps.failNotice.set(false);deps.failFiles.set(true);
        var other=create(user);deps.actor.set(9L);
        assertThrows(ContractProblemException.class,()->service.review(other.applicationId(),new Review(Status.APPROVED,12,"核准"),key()));
        assertEquals("PENDING",db.queryForObject("SELECT status FROM point_application WHERE id=?",String.class,other.applicationId()));
    }
    @Test void concurrentTerminalDecisionAndReversalHaveSingleWinner() throws Exception {
        var pending=create(person());
        var outcomes=race(()->{deps.actor.set(9L);return service.review(pending.applicationId(),new Review(Status.APPROVED,12,"核准"),key());},
                ()->{deps.actor.set(10L);return service.review(pending.applicationId(),new Review(Status.REJECTED,null,"不足"),key());});
        assertEquals(1,outcomes.stream().filter(x->x instanceof Application).count());
        assertEquals(1,outcomes.stream().filter(x->x instanceof ContractProblemException p && p.getType().endsWith("application-finalized")).count());
        String status=db.queryForObject("SELECT status FROM point_application WHERE id=?",String.class,pending.applicationId());
        assertEquals("APPROVED".equals(status)?1:0,count("SELECT COUNT(*) FROM point_ledger WHERE application_id=?",pending.applicationId()));
        var approved=approve(create(person()));
        var reversed=race(()->{deps.actor.set(9L);return service.reverse(approved.grantLedgerId(),new Reversal("纠错","synthetic test evidence"),key());},
                ()->{deps.actor.set(10L);return service.reverse(approved.grantLedgerId(),new Reversal("纠错","synthetic test evidence"),key());});
        assertEquals(1,reversed.stream().filter(x->x instanceof Ledger).count());
        assertEquals(1,reversed.stream().filter(x->x instanceof ContractProblemException p && p.getType().endsWith("grant-already-reversed")).count());
        assertEquals(1,count("SELECT COUNT(*) FROM point_ledger WHERE original_ledger_id=?",approved.grantLedgerId()));
    }
    @Test void sameKeyConcurrentCreateAndGrantUniqueness() throws Exception {
        long user=person();var body=command();String key=key();
        var created=race(()->{deps.actor.set(user);return service.create(body,key);},()->{deps.actor.set(user);return service.create(body,key);});
        assertTrue(created.stream().allMatch(x->x instanceof Application));assertEquals(created.get(0),created.get(1));
        long applicationId=((Application)created.get(0)).applicationId();String reviewKey=key();
        var reviewed=race(()->{deps.actor.set(9L);return service.review(applicationId,new Review(Status.APPROVED,12,"核准"),reviewKey);},
                ()->{deps.actor.set(9L);return service.review(applicationId,new Review(Status.APPROVED,12,"核准"),reviewKey);});
        assertEquals(reviewed.get(0),reviewed.get(1));var approved=(Application)reviewed.get(0);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO point_ledger(user_id,application_id,kind,points_delta,operator_user_id,reason,created_at) VALUES(?,?,'GRANT',12,9,'duplicate',UTC_TIMESTAMP(6))",user,approved.applicationId()));
        // Another application has a separate GRANT despite both original_ledger_id being NULL.
        var second=approve(create(user));assertNotEquals(second.grantLedgerId(),approved.grantLedgerId());
        String reverseKey=key();
        var reversedResults=race(()->{deps.actor.set(9L);return service.reverse(approved.grantLedgerId(),new Reversal("纠错","synthetic test evidence"),reverseKey);},
                ()->{deps.actor.set(9L);return service.reverse(approved.grantLedgerId(),new Reversal("纠错","synthetic test evidence"),reverseKey);});
        assertEquals(reversedResults.get(0),reversedResults.get(1));var reversed=(Ledger)reversedResults.get(0);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO point_ledger(user_id,application_id,kind,points_delta,operator_user_id,reason,original_ledger_id,created_at) VALUES(?,?,'REVERSAL',-12,9,'duplicate',?,UTC_TIMESTAMP(6))",user,approved.applicationId(),reversed.originalLedgerId()));
    }
    @Test void realHttpWireStrictInputOwnershipPagingAndRollback() throws Exception {
        long user=person();String path="/v1/users/me/points/applications";
        String payload="{\"acceptingOrganizationId\":\""+TEST_ORG+"\",\"title\":\"事项\",\"description\":\"证明\",\"fileIds\":[\"http-"+key()+"\"]}";
        var created=http("POST",path,user,payload,key(),201,"PointApplication");String id=created.get("applicationId").textValue();
        http("GET",path+"/"+id,user,null,null,200,"PointApplication");
        http("GET",path+"/"+id,person(),null,null,404,null);
        http("GET",path+"?page=1&pageSize=1&userId=9&role=ADMIN",user,null,null,200,"PointApplicationPage");
        assertEquals("query",http("GET",path+"?page=0",user,null,null,400,null).at("/errors/0/in").textValue());
        http("POST",path,user,payload.substring(0,payload.length()-1)+",\"awardedPoints\":999}",key(),400,null);
        assertEquals("header",http("POST",path,user,payload,null,400,null).at("/errors/0/in").textValue());
        http("GET","/v1/users/me/points/summary",user,null,null,200,"PointSummary");
        http("GET","/v1/users/me/points/summary",null,null,null,401,null);
        http("GET","/v1/users/me/points/eligibility",user,null,null,200,"PointEligibility");
        http("POST","/v1/users/me/points/file-upload-credentials",user,"{\"files\":[{\"fileName\":\"proof.pdf\",\"kind\":\"PDF\",\"contentType\":\"application/pdf\",\"sizeBytes\":12}]}",key(),503,"PointDependencyProblem");
        http("POST","/v1/users/me/points/files",user,"{\"uploadId\":\"synthetic-upload\",\"fileIndex\":0}",key(),503,"PointDependencyProblem");
        http("GET","/v1/users/me/points/files/synthetic-file",user,null,null,503,"PointDependencyProblem");
        http("GET","/v1/users/me/points/files/synthetic-file/access",user,null,null,503,"PointDependencyProblem");
        http("GET","/v1/admin/points/files/synthetic-file/access",9L,null,null,503,"PointDependencyProblem");
        http("GET","/v1/admin/points/files/synthetic-file/access",user,null,null,403,null);
        var approved=http("POST","/v1/admin/points/applications/"+id+"/decisions",9L,"{\"decision\":\"APPROVED\",\"awardedPoints\":12,\"reviewNote\":\"核准\"}",key(),200,"PointApplication");
        http("GET","/v1/admin/points/applications/"+id,9L,null,null,200,"PointApplication");
        http("GET","/v1/admin/points/applications/"+id,user,null,null,403,null);
        String grant=approved.get("grantLedgerId").textValue();
        http("GET","/v1/admin/points/ledger/"+grant,9L,null,null,200,"PointLedger");
        String reversalKey=key();
        var reversal=http("POST","/v1/admin/points/ledger/"+grant+":reverse",9L,"{\"reason\":\"账务纠错\"}",reversalKey,201,"PointLedger");
        assertEquals(reversal,http("POST","/v1/admin/points/ledger/"+grant+":reverse",9L,"{\"reason\":\"账务纠错\"}",reversalKey,201,"PointLedger"));
        http("GET","/v1/users/me/points/ledger?pageSize=1",user,null,null,200,"PointLedgerPage");
        http("GET","/v1/admin/points/statistics/organizations",9L,null,null,503,"PointDependencyProblem");
        http("GET","/v1/admin/points/statistics/users",9L,null,null,503,"PointDependencyProblem");
        http("GET","/v1/admin/points/applications",9L,null,null,503,"PointDependencyProblem");
    }
    com.fasterxml.jackson.databind.JsonNode http(String method,String path,Long actor,String body,String key,int expected,String schema) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(root+path)).timeout(Duration.ofSeconds(15));
        if(actor!=null) builder.header("X-Points-Test-User",actor.toString());
        if(key!=null) builder.header("Idempotency-Key",key);
        if(body!=null) builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),response.body());assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(expected>=400) assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        var value=json.readTree(response.body());var evidence=new LinkedHashMap<String,Object>();
        evidence.put("method",method);evidence.put("path",path);evidence.put("status",expected);evidence.put("schema",schema);evidence.put("body",value);
        evidence.put("location",response.headers().firstValue("Location").orElse(null));responses.add(evidence);
        return value;
    }
    List<Object> race(Callable<?> first,Callable<?> second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var one=pool.submit(()->outcome(start,first));var two=pool.submit(()->outcome(start,second));start.countDown();
            return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    Object outcome(CountDownLatch start,Callable<?> work) throws Exception { start.await(5,TimeUnit.SECONDS);try { return work.call(); } catch(ContractProblemException failure) { return failure; } finally { deps.actor.remove(); } }

    static class TestDependencies implements PointsDependencies {
        final JdbcTemplate db;
        final ThreadLocal<Long> actor=new ThreadLocal<>();
        final Set<Long> ineligible=ConcurrentHashMap.newKeySet(),denied=ConcurrentHashMap.newKeySet();
        final AtomicBoolean failBind=new AtomicBoolean(),failNotice=new AtomicBoolean(),failFiles=new AtomicBoolean();
        volatile long extraAdmin=-1;
        TestDependencies(JdbcTemplate db) { this.db=db; }
        public long actor(Action action) {
            Long value=actor.get();var request=RequestContextHolder.getRequestAttributes();
            if(request instanceof ServletRequestAttributes servlet) {
                String header=servlet.getRequest().getHeader("X-Points-Test-User");value=header==null?null:Long.parseLong(header);
            }
            if(value==null) throw new ContractProblemException(HttpStatus.UNAUTHORIZED,"/problems/unauthorized","Test identity missing");
            if((action==Action.READ || action==Action.REVIEW || action==Action.REVERSE) && value!=9 && value!=10 && value!=extraAdmin) {
                throw new ContractProblemException(HttpStatus.FORBIDDEN,"/problems/forbidden","Test scope denied");
            }
            return value;
        }
        public Acceptance acceptance(long user,String organization) {
            if(!TEST_ORG.equals(organization) || !eligible(user)) throw forbidden("ineligible");
            return new Acceptance(organization,db.queryForObject("SELECT id FROM person_membership WHERE user_id=? AND organization_id=? AND ended_event_id IS NULL",String.class,user,organization));
        }
        public void recheckAcceptance(Application application) {if(!TEST_ORG.equals(application.acceptingOrganizationId()) || application.membershipId()==null) throw unavailable(Dependency.BUSINESS_ELIGIBILITY);}
        public void requireManage(long operator,long user) { if(denied.contains(user)) throw missing(); }
        public boolean eligible(long user) { return !ineligible.contains(user); }
        public void bindFiles(long user,long app,List<String> files) {
            for(String file:files) db.update("INSERT INTO points_test_file VALUES(?,?,?)",file,user,app);
            if(failBind.get()) throw new IllegalStateException("Synthetic binding failure after write");
        }
        public void requireFiles(Application app) { if(failFiles.get()) throw unavailable(Dependency.PRIVATE_MEDIA); }
        public void acceptResult(Application app,Ledger reversal) {
            String key=reversal==null?"application:"+app.applicationId():"reversal:"+reversal.ledgerId();
            db.update("INSERT INTO points_test_notice VALUES(?,?)",key,app.applicantUserId());
            if(failNotice.get()) throw new IllegalStateException("Synthetic notice acceptance failure after write");
        }
    }
    @Configuration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses=PointsMapper.class)
    @Import({cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,PointsService.class,PointsController.class,PointsFileController.class,PointsProblemAdvice.class,cn.jualn.miniapp.module.points.PointsNoStoreFilter.class,GlobalExceptionHandler.class,JacksonConfig.class,TimeConfig.class})
    static class Config {
        @Bean DataSource dataSource() { return source(System.getProperty("points.test.jdbcUrl")); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TestDependencies dependencies(JdbcTemplate db) { return new TestDependencies(db); }
        // These old wire checks retain the explicit unavailable media fixture. Real media lifecycle
        // and the full authenticated closure are covered by their dedicated database/HTTP suites.
        @Bean PointsPrivateMaterials closedMedia() {
            var media=org.mockito.Mockito.mock(PointsPrivateMaterials.class);
            org.mockito.Mockito.doThrow(unavailable(Dependency.PRIVATE_MEDIA)).when(media).upload(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyString());
            org.mockito.Mockito.doThrow(unavailable(Dependency.PRIVATE_MEDIA)).when(media).register(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString());
            org.mockito.Mockito.doThrow(unavailable(Dependency.PRIVATE_MEDIA)).when(media).read(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
            org.mockito.Mockito.doThrow(unavailable(Dependency.PRIVATE_MEDIA)).when(media).access(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
            org.mockito.Mockito.doThrow(unavailable(Dependency.PRIVATE_MEDIA)).when(media).bound(org.mockito.ArgumentMatchers.anyString());
            return media;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
            factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/PersonMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/PointsMapper.xml"));return factory.getObject();
        }
    }
}
