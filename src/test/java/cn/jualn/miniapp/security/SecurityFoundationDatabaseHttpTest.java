package cn.jualn.miniapp.security;

import cn.jualn.miniapp.config.*;
import cn.jualn.miniapp.infrastructure.cache.*;
import cn.jualn.miniapp.module.auth.controller.AuthController;
import cn.jualn.miniapp.module.auth.service.impl.AuthServiceImpl;
import cn.jualn.miniapp.module.auth.service.LoginAttemptLimiter;
import cn.jualn.miniapp.module.user.service.*;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.enums.*;
import cn.jualn.miniapp.module.authorization.service.*;
import cn.jualn.miniapp.module.admin.auth.service.*;
import cn.jualn.miniapp.module.admin.auth.store.*;
import cn.jualn.miniapp.module.admin.auth.config.*;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.user.bo.AdminUserRestrictionBO;
import org.springframework.transaction.support.TransactionTemplate;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import java.util.Map;
import java.util.List;
import java.time.Duration;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.dev33.satoken.SaManager;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real HTTP/MySQL/Redis/Sa/QR and owner locks. Only profile projection and external WeChat are adapters.
 * The context includes only authentication, account, authorization and report-denial owners. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/p1_security_test(?:_[a-z0-9]+)?")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityFoundationDatabaseHttpTest {
    static String url;
    ServletWebServerApplicationContext context;
    JdbcTemplate jdbc;
    UserService users;
    UserAccountService userAccounts;
    UserWechatIdentityService userIdentities;
    String token, otherToken;
    final java.util.List<java.util.Map<String,Object>> responses=new java.util.ArrayList<>();
    final java.net.http.HttpClient client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
    static String setting(String property,String environment) {return System.getProperty(property,System.getenv(environment));}
    static String connectionUrl(String value) {return value+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=800&socketTimeout=1500";}

    @BeforeAll void start() {
        url=System.getProperty("event.test.jdbcUrl");
        assertTrue(url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/p1_security_test(?:_[a-z0-9]+)?"));
        var admin=new JdbcTemplate(new DriverManagerDataSource(connectionUrl(url.substring(0,url.lastIndexOf('/'))+"/mysql"),"root",""));
        admin.execute("CREATE DATABASE "+url.substring(url.lastIndexOf('/')+1));
        Flyway.configure().dataSource(connectionUrl(url),"root","").load().migrate();
        context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FoundationConfig.class)
                .run("--spring.config.location=optional:classpath:/p1-no-application.yaml", "--server.address=127.0.0.1", "--server.port=0",
                    "--spring.flyway.enabled=false", "--spring.main.banner-mode=off", "--spring.jmx.enabled=false",
                    "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port="+setting("p1.redis.port","P1_REDIS_PORT"),
                    "--spring.data.redis.timeout=600ms", "--spring.data.redis.connect-timeout=600ms",
                    "--sa-token.is-read-cookie=false", "--sa-token.is-read-body=false", "--sa-token.token-name=Authorization", "--sa-token.token-prefix=Bearer", "--sa-token.is-share=false",
                    "--sa-token.jwt-secret-key=p1-synthetic-local-test-key-not-a-production-secret-20261015",
                    "--sa-token.is-log=false", "--logging.level.root=WARN");
        assertTrue(SaManager.getSaTokenDao().getClass().getName().contains("Redis"),"Real Sa Redis DAO required");
        jdbc=new JdbcTemplate(context.getBean(DataSource.class)); users=context.getBean(UserService.class); userAccounts=context.getBean(UserAccountService.class); userIdentities=context.getBean(UserWechatIdentityService.class);
        jdbc.update("INSERT INTO user_profile(id,openid,nickname,role,status) VALUES (42,'p1-user42','P1 user',1,1),(43,'p1-user43','other',1,1),(9,'p1-admin','operator',3,1)");
        context.getBean(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class).backfillLegacy();
    }

    @BeforeEach void reset() {
        context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).execute(
            (org.springframework.data.redis.core.RedisCallback<Object>) connection->{connection.serverCommands().flushDb();return null;});
        jdbc.update("DELETE FROM rbac_authorization_change");jdbc.update("DELETE FROM rbac_user_role_assignment");
        jdbc.update("UPDATE rbac_authorization_authority SET source='LEGACY',version=UUID()");
        jdbc.update("UPDATE user_profile SET status=1,ban_reason=NULL,ban_expire_at=NULL,role=IF(id=9,3,1) WHERE id IN (42,43,9)");
        token=loginToken(42);otherToken=loginToken(43);
        // Synthetic readiness only; actual ROOT must still own the capability.
        enableRole("USER_GOVERNOR");
    }
    String loginToken(long id) {
        String token=StpUtil.getStpLogic().createTokenValue(id,"default-device",3600,
            Map.of(UserAccountService.SESSION_VERSION,Long.toString(userAccounts.sessionVersion(id))));
        StpUtil.getStpLogic().createLoginSession(id,new SaLoginParameter().setToken(token).setIsConcurrent(true).setIsShare(false));
        return token;
    }
    @Test void foundationPreUpgradeCredentialCannotReviveAtGenerationZero() throws Exception {
        String old = StpUtil.getStpLogic().createTokenValue(42L, "default-device", 3600, Map.of());
        StpUtil.getStpLogic().createLoginSession(42L, new SaLoginParameter().setToken(old).setIsConcurrent(true).setIsShare(false));
        assertEquals(0L, userAccounts.sessionVersion(42));
        assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,
                () -> userAccounts.requireSessionVersion(42, null));
        assertEquals(401, request("GET", "/v1/p1-probe/read", old, null).statusCode());
        assertEquals(204, request("POST", "/v1/auth/logout", old, null).statusCode());
        assertEquals(200, request("GET", "/v1/p1-probe/read", token, null).statusCode());
    }

    @Test void foundationLogoutInvalidatesOnlyCurrentOrdinaryTokenAndCannotReplay() throws Exception {
        assertEquals(200, request("GET", "/v1/p1-probe/read", token, null).statusCode());
        var response=request("POST", "/v1/auth/logout", token, null);
        assertEquals(204,response.statusCode());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(401,request("GET","/v1/p1-probe/read",token,null).statusCode());
        assertEquals(200,request("GET","/v1/p1-probe/read",otherToken,null).statusCode());
        assertEquals(204,request("POST","/v1/auth/logout",token,null).statusCode());
        assertEquals(204,request("POST","/v1/auth/logout",null,null).statusCode());
    }

    @Test void foundationBanGenerationSurvivesRollbackExpiryRestoreAndLateQrCandidate() throws Exception {
        String admin=activatedAdmin();
        var challenge=confirmed(9);
        var tokens=context.getBean(AdminTokenService.class);
        var store=context.getBean(AdminQrLoginStore.class);
        var snapshot=store.web("read",challenge.id(),cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.sha256(challenge.secret()),null,null);
        var candidate=tokens.prepareQrCandidate(9,challenge.id(),users.getUserProfile(9L),snapshot.now());
        assignment(42,"ROOT","AUTHORIZATION");
        long before=userAccounts.sessionVersion(9);
        var command=AdminUserRestrictionBO.builder().operatorId(42L).targetUserId(9L).reason("Synthetic emergency suspension").build();
        userAccounts.banUser(command);
        assertEquals(before+1,userAccounts.sessionVersion(9));
        assertEquals(401,request("GET","/v1/admin/p1-probe",admin,null).statusCode());
        userAccounts.restoreUser(command);
        assertEquals(401,request("GET","/v1/admin/p1-probe",admin,null).statusCode());
        assertThrows(BusinessException.class,()->tokens.validQrResult(9,challenge.id(),candidate,snapshot.now()));
        assertEquals(200,request("GET","/v1/admin/p1-probe",activatedAdmin(),null).statusCode());
        var tx=new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status->{userAccounts.banUser(command);status.setRollbackOnly();});
        assertEquals(before+1,userAccounts.sessionVersion(9));
        userAccounts.banUser(command);
        jdbc.update("UPDATE user_profile SET ban_expire_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=9");
        userAccounts.assertLoginAllowed(9L);
        assertThrows(BusinessException.class,()->userAccounts.requireSessionVersion(9,before+1));
    }

    @Test void foundationLegacyAdminCannotSuspendPeerAndOrdinaryManagementStaysClosed() throws Exception {
        jdbc.update("UPDATE user_profile SET role=3 WHERE id=43");
        assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->userAccounts.banUser(
            AdminUserRestrictionBO.builder().operatorId(9L).targetUserId(43L).reason("Untrusted legacy administrator").build()));
        String legacy=loginToken(9);
        assertEquals(403,request("GET","/v1/reports/admin",legacy,null).statusCode());
        assignment(42,"ROOT","AUTHORIZATION");
        assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->userAccounts.banUser(
            AdminUserRestrictionBO.builder().operatorId(9L).targetUserId(42L).reason("Last root protection").build()));
    }

    @Test void foundationLoginAbuseUsesSharedRedisAndNeverProviderCredentialsAsKeys() {
        var limiter=context.getBean(cn.jualn.miniapp.module.auth.service.LoginAttemptLimiter.class);
        for(int i=0;i<30;i++) limiter.require("synthetic-foundation-peer");
        assertThrows(cn.jualn.miniapp.common.exception.RateLimitedException.class,()->limiter.require("synthetic-foundation-peer"));
    }

    @Test void foundationStoreFailureNeverReportsLogoutSuccess() throws Exception {
        String admin=activatedAdmin();
        faultContainer("p1.redis.container",()->{
            assertEquals(503,request("POST","/v1/auth/logout",token,null).statusCode());
            assertEquals(503,request("POST","/v1/admin/auth/logout",admin,null).statusCode());
        });
        assertEquals(200,request("POST","/v1/admin/auth/logout",admin,null).statusCode());
        assertEquals(204,request("POST","/v1/auth/logout",token,null).statusCode());
        assertEquals(401,request("GET","/v1/p1-probe/read",token,null).statusCode());
    }

    @Test void foundationStandaloneAccountRoleCannotInventPlatformDuty() {
        assignment(43,"USER_GOVERNOR","USER");
        assertTrue(context.getBean(CurrentAuthorization.class).current(43).canUseAdmin());
        assertThrows(BusinessException.class,()->userAccounts.banUser(AdminUserRestrictionBO.builder()
            .operatorId(43L).targetUserId(42L).reason("Synthetic GLOBAL is not PLATFORM").build()));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM user_profile WHERE id=42",Integer.class));
    }

    @Test void foundationShadowAssignmentDoesNotElevateALegacyAccount() {
        assignment(43,"ROOT","AUTHORIZATION");
        jdbc.update("UPDATE rbac_authorization_authority SET source='LEGACY',version=UUID() WHERE user_id=43");
        assertFalse(context.getBean(CurrentAuthorization.class).current(43).canUseAdmin());
        userAccounts.banUser(AdminUserRestrictionBO.builder().operatorId(9L).targetUserId(43L)
            .reason("Synthetic legacy account with inactive shadow assignment").build());
        assertEquals(3,jdbc.queryForObject("SELECT status FROM user_profile WHERE id=43",Integer.class));
    }

    @Test void foundationHeaderIsTheOnlyCredentialSourceAndEmptyGrantCanStillLogout() throws Exception {
        String encoded=java.net.URLEncoder.encode("Bearer "+token,java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(401,request("GET","/v1/p1-probe/read?Authorization="+encoded,null,null).statusCode());
        var cookie=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/v1/p1-probe/read"))
            .header("Cookie","Authorization="+token).GET().build();
        assertEquals(401,client.send(cookie,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        String admin=activatedAdmin();
        jdbc.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT',version=UUID() WHERE user_id=9");
        assertEquals(200,request("POST","/v1/admin/auth/logout",admin,null).statusCode());
        assertEquals(200,request("POST","/v1/admin/auth/logout",admin,null).statusCode());
        assertEquals(401,request("GET","/v1/admin/p1-probe",admin,null).statusCode());
    }

    @Test void foundationConcurrentRootSuspensionsLeaveOneEffectiveRoot() throws Exception {
        assignment(42,"ROOT","AUTHORIZATION");assignment(43,"ROOT","AUTHORIZATION");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        java.util.function.BiFunction<Long,Long,Boolean> suspend=(actor,target)->{
            try {start.await();userAccounts.banUser(cn.jualn.miniapp.module.user.bo.AdminUserRestrictionBO.builder()
                    .operatorId(actor).targetUserId(target).reason("Synthetic competing ROOT suspension").build());return true;}
            catch(Exception rejected){return false;}
        };
        try {
            var first=pool.submit(()->suspend.apply(42L,43L));var second=pool.submit(()->suspend.apply(43L,42L));start.countDown();
            int successes=(first.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(second.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0);
            assertEquals(1,successes);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_profile WHERE id IN (42,43) AND status=1",Integer.class));
        } finally {pool.shutdownNow();}
    }

    String activatedAdmin() {
        var store=context.getBean(AdminQrLoginStore.class);var tokens=context.getBean(AdminTokenService.class);
        String id=cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.randomUrlToken(16);
        String scene=cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.sha256(id+"scene"),secret=cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.sha256(id+"secret");
        store.publish(id,scene,secret,new byte[]{(byte)137,80,78,71,13,10,26,10},Duration.ofMinutes(2),Duration.ofMinutes(5),1500);
        store.mobile("scan",scene,9L);store.mobile("confirm",scene,9L);
        var snapshot=store.web("read",id,secret,null,null);
        var candidate=tokens.prepareQrCandidate(9L,id,users.getUserProfile(9L),snapshot.now());
        store.web("reserve",id,secret,candidate,null);tokens.materializeQrCandidate(9L,candidate,snapshot.now());
        assertEquals("COMMITTED",store.web("finalize",id,secret,candidate,tokens.qrMappingKey(candidate)).outcome());
        return candidate.token();
    }

    interface Checked {void run() throws Exception;}
    void faultContainer(String property,Checked check) throws Exception {
        String name=setting(property,property.equals("p1.redis.container")?"P1_REDIS_CONTAINER":"P1_MYSQL_CONTAINER");
        assertNotNull(name,"Dedicated container name required for real failure verification");
        assertTrue(name.matches("codex-p1-(redis|mysql)-[a-f0-9]{8}"));
        assertEquals(0,new ProcessBuilder("docker","pause",name).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());
        try {check.run();} finally {assertEquals(0,new ProcessBuilder("docker","unpause",name).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());}
    }
    void enableRole(String role) {
        jdbc.update("UPDATE rbac_permission_definition p JOIN rbac_role_permission m ON m.permission_code=p.code SET p.lifecycle='ENABLED',p.readiness='READY',p.grantable=1,p.unavailable_reason=NULL WHERE m.role_code=?",role);
        jdbc.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=?",role);
    }
    String assignment(long user,String role,String space) {
        enableRole(role);String id=java.util.UUID.randomUUID().toString();
        jdbc.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT',version=UUID() WHERE user_id=?",user);
        jdbc.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(?,?,?,?,'GLOBAL',UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,UUID(),UUID())",id,user,role,space);
        return id;
    }
    record Challenge(String id,String secret,String scene) {}
    Challenge confirmed(long user) {
        var store=context.getBean(AdminQrLoginStore.class);
        String id=cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.randomUrlToken(16);
        String secret=cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.randomUrlToken(32);
        String scene=cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.randomUrlToken(24);
        store.publish(id,cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.sha256(scene),cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.sha256(secret),new byte[]{(byte)137,80,78,71,13,10,26,10},Duration.ofMinutes(2),Duration.ofMinutes(5),1500);
        var qr=context.getBean(cn.jualn.miniapp.module.admin.auth.service.AdminQrLoginService.class);
        qr.scanSession(scene,user,"synthetic-scan-"+id);qr.confirmSession(scene,user,"synthetic-confirm-"+id);
        return new Challenge(id,secret,scene);
    }
    java.net.http.HttpResponse<String> request(String method,String path,String auth,String body) throws Exception {
        var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).timeout(java.time.Duration.ofSeconds(12));
        if(auth!=null) builder.header("Authorization","Bearer "+auth);
        if(body!=null) builder.header("Content-Type","application/json");
        var response=client.send(builder.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        responses.add(Map.of("method",method,"path",path,"status",response.statusCode(),"cacheControl",response.headers().firstValue("Cache-Control").orElse(""),
                "contentType",response.headers().firstValue("Content-Type").orElse(""),"location",response.headers().firstValue("Location").orElse(""),
                "body",path.startsWith("/v1/auth") && response.statusCode()==200?"<synthetic login credential redacted>":response.body()));
        return response;
    }

    @AfterEach void clearContext() {cn.jualn.miniapp.common.constant.UserContext.clear();}
    @AfterAll void close() throws Exception {
        if(context!=null) context.close();
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/security-foundation"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/security-foundation/http.json"),new ObjectMapper().writeValueAsString(responses));
    }
    @org.springframework.web.bind.annotation.RestController
    @lombok.RequiredArgsConstructor
    static class Probe {
        @org.springframework.web.bind.annotation.GetMapping({"/v1/p1-probe/read","/v1/admin/p1-probe"})
        Map<String,Boolean> read() {return Map.of("reached",true);}
    }

    @Configuration @EnableAutoConfiguration @EnableTransactionManagement
    @MapperScan({"cn.jualn.miniapp.module.user.mapper","cn.jualn.miniapp.module.authorization.mapper",
        "cn.jualn.miniapp.module.person.mapper","cn.jualn.miniapp.module.admin.operation.mapper","cn.jualn.miniapp.module.report.mapper"})
    @Import({WebMvcConfig.class,cn.jualn.miniapp.web.ContextInterceptor.class,SaTokenConfig.class,RedisConfig.class,RedisService.class,
        UserAccountService.class,CurrentAuthorization.class,RbacAuthorizationReader.class,RbacCatalog.class,RbacGovernanceService.class,
        TransactionalAuthorization.class,BusinessAuthorizationLease.class,PersonAuthorityGate.class,AuthorizationAuthorityService.class,
        AdminTokenService.class,AdminAuthStore.class,AdminQrLoginStore.class,AdminPermissionPolicy.class,
        cn.jualn.miniapp.module.admin.auth.service.impl.AdminQrLoginServiceImpl.class,
        cn.jualn.miniapp.module.admin.auth.controller.AdminAuthController.class,
        cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class,
        AuthController.class,AuthServiceImpl.class,LoginAttemptLimiter.class,GlobalExceptionHandler.class,cn.jualn.miniapp.common.mapper.EnumConverter.class,
        cn.jualn.miniapp.config.SaPermissionProvider.class,Probe.class,
        cn.jualn.miniapp.module.report.controller.ReportController.class,
        cn.jualn.miniapp.module.report.service.impl.ReportServiceImpl.class})
    static class FoundationConfig {
        @Bean DataSource source() {return new DriverManagerDataSource(connectionUrl(url),"root","");}
        @Bean DataSourceTransactionManager transactions(DataSource source) {return new DataSourceTransactionManager(source);}
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setCacheEnabled(false);factory.setConfiguration(config);
            var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();
            global.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());
            global.getDbConfig().setLogicNotDeleteValue("NULL");global.getDbConfig().setLogicDeleteValue("NOW()");factory.setGlobalConfig(global);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
            return factory.getObject();
        }
        @Bean ObjectMapper json() {return new JacksonConfig().objectMapper(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder());}
        @Bean AdminAuthProperties properties() {return new AdminAuthProperties();}
        @Bean AdminQrLoginProperties qrProperties() {var value=new AdminQrLoginProperties();value.setEnvVersion("release");return value;}
        @Bean io.micrometer.core.instrument.MeterRegistry meters() {return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();}
        @Bean UserService profiles(UserAccountService accounts) {
            var users=mock(UserService.class);
            when(users.getUserProfile(anyLong())).thenAnswer(call->{long user=call.getArgument(0);var auth=accounts.getUserAuthInfo(user);
                return UserProfileBO.builder().id(user).nickname("Synthetic account").role(auth.getRole()).status(auth.getStatus()).build();});
            return users;
        }
        @Bean UserWechatIdentityService identities() {return mock(UserWechatIdentityService.class);}
        @Bean WxClient wx() {return mock(WxClient.class);}
        @Bean cn.jualn.miniapp.module.subject.service.SubjectExistenceService targets() {return mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class);}
        @Bean cn.jualn.miniapp.module.report.converter.ReportConverter reportConverter() {return org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.report.converter.ReportConverter.class);}
    }
}
