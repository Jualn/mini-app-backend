package cn.jualn.miniapp.security;

import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.config.*;
import cn.jualn.miniapp.module.admin.auth.store.AdminAuthStore;
import cn.jualn.miniapp.module.admin.auth.store.AdminQrLoginStore;
import cn.jualn.miniapp.infrastructure.cache.*;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.*;
import cn.jualn.miniapp.module.admin.auth.config.AdminAuthProperties;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.auth.controller.AuthController;
import cn.jualn.miniapp.module.auth.service.impl.AuthServiceImpl;
import cn.jualn.miniapp.module.comment.controller.CommentController;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.service.*;
import cn.jualn.miniapp.module.comment.service.impl.CommentServiceImpl;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.service.*;
import cn.jualn.miniapp.module.interact.controller.InteractController;
import cn.jualn.miniapp.module.interact.dto.inner.UserLikeQuery;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.service.*;
import cn.jualn.miniapp.module.user.bo.AdminUserRestrictionBO;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.user.service.impl.UserServiceImpl;
import cn.jualn.miniapp.third.wx.client.WxClient;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import static cn.jualn.miniapp.common.enums.TargetType.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real loopback HTTP + disposable MySQL/Redis. External safety/WeChat/media use doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl", matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/p1_security_test(?:_[a-z0-9]+)?")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P1SecurityDatabaseHttpTest {
    static String url;
    ServletWebServerApplicationContext context;
    JdbcTemplate jdbc;
    String token;
    String otherToken;
    UserService users;
    UserAccountService userAccounts;
    UserWechatIdentityService userIdentities;
    final List<Map<String,Object>> responses = java.util.Collections.synchronizedList(new ArrayList<>());
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll void start() {
        url=System.getProperty("event.test.jdbcUrl");
        assertTrue(url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/p1_security_test(?:_[a-z0-9]+)?"));
        var admin=new JdbcTemplate(new DriverManagerDataSource(connectionUrl(url.substring(0,url.lastIndexOf('/'))+"/mysql"),"root",""));
        admin.execute("CREATE DATABASE "+url.substring(url.lastIndexOf('/')+1));
        Flyway.configure().dataSource(connectionUrl(url),"root","").load().migrate();
        context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(Config.class)
                .run("--spring.config.location=optional:classpath:/p1-no-application.yaml", "--server.address=127.0.0.1", "--server.port=0",
                    "--spring.flyway.enabled=false", "--spring.main.banner-mode=off", "--spring.jmx.enabled=false",
                    "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port="+setting("p1.redis.port","P1_REDIS_PORT"),
                    "--spring.data.redis.timeout=600ms", "--spring.data.redis.connect-timeout=600ms",
                    "--sa-token.token-name=Authorization", "--sa-token.token-prefix=Bearer", "--sa-token.is-share=false",
                    "--sa-token.jwt-secret-key=p1-synthetic-local-test-key-not-a-production-secret-20261015",
                    "--sa-token.is-log=false", "--logging.level.root=WARN");
        assertTrue(SaManager.getSaTokenDao().getClass().getName().contains("Redis"),"Real Sa Redis DAO required");
        jdbc=new JdbcTemplate(context.getBean(DataSource.class)); users=context.getBean(UserService.class); userAccounts=context.getBean(UserAccountService.class); userIdentities=context.getBean(UserWechatIdentityService.class);
        jdbc.update("INSERT INTO user_profile(id,openid,nickname,role,status) VALUES (42,'p1-user42','P1 user',1,1),(43,'p1-user43','other',1,1),(9,'p1-admin','operator',3,1)");
        context.getBean(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class).backfillLegacy();
    }
    static String setting(String property,String env) {return System.getProperty(property,System.getenv(env));}
    static String connectionUrl(String value) { return value+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=800&socketTimeout=1500"; }
    @BeforeEach void reset() {
        // Each case owns its synthetic sessions and rate-limit budget on this dedicated Redis.
        context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).execute(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection->{connection.serverCommands().flushDb();return null;});
        jdbc.update("DELETE FROM rbac_authorization_change");jdbc.update("DELETE FROM rbac_user_role_assignment");
        jdbc.update("UPDATE rbac_authorization_authority SET source='LEGACY',version=UUID()");
        jdbc.update("UPDATE user_profile SET status=1,ban_reason=NULL,ban_expire_at=NULL,role=IF(id=9,3,1) WHERE id IN (42,43,9)");
        token=loginToken(42); otherToken=loginToken(43);
    }
    String loginToken(long id) {
        String token=StpUtil.getStpLogic().createTokenValue(id,"default-device",3600,Map.of(UserAccountService.SESSION_VERSION,Long.toString(userAccounts.sessionVersion(id))));
        StpUtil.getStpLogic().createLoginSession(id,new SaLoginParameter().setToken(token).setIsConcurrent(true).setIsShare(false));
        return token;
    }
    @AfterEach void clear() { UserContext.clear(); }
    @AfterAll void close() throws Exception {
        if(context!=null) context.close();
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/p1-security-http-responses.json"),new ObjectMapper().writeValueAsString(responses));
    }
    HttpResponse<String> request(String method,String path,String auth,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(12));
        if(auth!=null) builder.header("Authorization","Bearer "+auth);
        if(body!=null) builder.header("Content-Type","application/json");
        var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        responses.add(Map.of("method",method,"path",path,"status",response.statusCode(),"cacheControl",response.headers().firstValue("Cache-Control").orElse(""),
                "contentType",response.headers().firstValue("Content-Type").orElse(""),"location",response.headers().firstValue("Location").orElse(""),
                "body",path.startsWith("/v1/auth") && response.statusCode()==200?"<synthetic login credential redacted>":response.body()));
        return response;
    }
    long subject(cn.jualn.miniapp.common.enums.TargetType type,int publication) {
        String table=type==POST?"post":type==ACTIVITY?"activity":"public_event";
        jdbc.update(type==POST?"INSERT INTO post(user_id,title,content,status,audit_status,published_at) VALUES(43,'visible','body',?,1,NOW())":
                "INSERT INTO "+table+"(user_id,title,publish_status,lifecycle_status) VALUES(43,'visible',?,0)",publication);
        return jdbc.queryForObject("SELECT MAX(id) FROM "+table,Long.class);
    }
    String interaction(cn.jualn.miniapp.common.enums.TargetType type,long id) {return "{\"targetType\":\""+type+"\",\"targetId\":\""+id+"\"}";}
    String comments(cn.jualn.miniapp.common.enums.TargetType type,long id) {return "/v1/comment?targetType="+type+"&targetId="+id;}
    void published(cn.jualn.miniapp.common.enums.TargetType type,long id,int state) {
        jdbc.update("UPDATE "+(type==POST?"post SET status":""+(type==ACTIVITY?"activity":"public_event")+" SET publish_status")+"=? WHERE id=?",state,id);
    }

    @Test void currentSubjectsGuardAllOldInteractionsCommentsAndHistory() throws Exception {
        for(var type:List.of(POST,ACTIVITY,EXAM)) {
            long id=subject(type,type==POST?2:1);
            assertEquals(200,request("POST","/v1/interact/like",token,interaction(type,id)).statusCode());
            assertEquals(200,request("GET","/v1/interact/liked?targetType="+type+"&targetId="+id,otherToken,null).statusCode());
            assertEquals(200,request("POST","/v1/interact/view",token,interaction(type,id)).statusCode());
            assertEquals(200,request("POST","/v1/interact/share",token,interaction(type,id).replace("}",",\"platform\":1}")).statusCode());
            assertEquals(200,request("POST","/v1/comment",token,interaction(type,id).replace("}",",\"content\":\"legal comment\"}")).statusCode());
            long root=jdbc.queryForObject("SELECT MAX(id) FROM comment WHERE target_type=? AND target_id=?",Long.class,type.getCode(),id);
            jdbc.update("UPDATE comment SET status=1,audit_status=1 WHERE id=?",root);
            assertEquals(200,request("GET",comments(type,id),otherToken,null).statusCode());
            assertEquals(200,request("GET","/v1/comment/replies?targetType="+type+"&targetId="+id+"&parentId="+root,otherToken,null).statusCode());
            assertEquals(200,request("POST","/v1/interact/like",otherToken,interaction(COMMENT,root)).statusCode());
            for(String counter:List.of("like","share","view")) {
                var response=request("GET","/v1/interact/"+counter+"/count?targetType="+type+"&targetId="+id,token,null);
                assertEquals(200,response.statusCode());assertEquals(1,new ObjectMapper().readTree(response.body()).path("data").path("count").asInt());
            }
            published(type,id,0);
            for(String path:List.of("/v1/interact/liked","/v1/interact/like/count","/v1/interact/share/count","/v1/interact/view/count")) {
                assertEquals(404,request("GET",path+"?targetType="+type+"&targetId="+id,token,null).statusCode());
            }
            for(String path:List.of("/v1/interact/like","/v1/interact/unlike","/v1/interact/view")) {
                assertEquals(404,request("POST",path,token,interaction(type,id)).statusCode());
            }
            assertEquals(404,request("POST","/v1/interact/share",token,interaction(type,id).replace("}",",\"platform\":1}")).statusCode());
            assertEquals(404,request("GET",comments(type,id),otherToken,null).statusCode());
            assertEquals(404,request("GET","/v1/comment/replies?targetType="+type+"&targetId="+id+"&parentId="+root,otherToken,null).statusCode());
            assertEquals(404,request("POST","/v1/comment",token,interaction(type,id).replace("}",",\"content\":\"hidden\"}")).statusCode());
            assertEquals(404,request("POST","/v1/interact/like",token,interaction(COMMENT,root)).statusCode());
            UserContext.setUserId(42L);
            assertFalse(context.getBean(InteractionLikeQueries.class).pageUserLikes(UserLikeQuery.builder().targetType(type).build()).stream().anyMatch(r->r.getTargetId()==id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM like_record WHERE user_id=42 AND target_type=? AND target_id=?",Integer.class,type.getCode(),id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE id=? AND deleted_at IS NULL",Integer.class,root));
            for(int hidden:type==POST?List.of(1,3,4):List.of(2)) {
                published(type,id,hidden);
                assertEquals(404,request("POST","/v1/interact/like",token,interaction(type,id)).statusCode());
                assertEquals(404,request("GET",comments(type,id),otherToken,null).statusCode());
            }
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM share_record WHERE user_id=42 AND target_type=? AND target_id=?",Integer.class,type.getCode(),id));
            published(type,id,type==POST?2:1);
            assertEquals(200,request("GET",comments(type,id),token,null).statusCode());
            jdbc.update("UPDATE "+(type==POST?"post":type==ACTIVITY?"activity":"public_event")+" SET deleted_at=NOW() WHERE id=?",id);
            assertEquals(404,request("POST","/v1/interact/like",token,interaction(type,id)).statusCode());
        }
    }

    @Test void withdrawalAndInteractionSerializeAtTheOwningSubject() throws Exception {
        var pool=Executors.newSingleThreadExecutor();
        try {
            for(var type:List.of(POST,ACTIVITY,EXAM)) {
                long id=subject(type,type==POST?2:1);
                var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
                var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
                var hidden=pool.submit(()->tx.executeWithoutResult(status->{
                    published(type,id,0);locked.countDown();
                    try {if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test barrier elapsed");}
                    catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                }));
                assertTrue(locked.await(3,TimeUnit.SECONDS));
                var pending=client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/v1/interact/like"))
                        .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(interaction(type,id))).build(),HttpResponse.BodyHandlers.ofString());
                try {
                    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Integer.class)>0);
                    assertFalse(pending.isDone());
                } finally {release.countDown();}
                hidden.get(3,TimeUnit.SECONDS);assertEquals(404,pending.get(4,TimeUnit.SECONDS).statusCode());
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM like_record WHERE user_id=42 AND target_type=? AND target_id=?",Integer.class,type.getCode(),id));
            }
        } finally {pool.shutdownNow();}
    }

    @Test void auditVisibilityAndLifecycleDoNotBecomeOneGenericStateRule() throws Exception {
        long post=subject(POST,2);
        for(int audit:List.of(0,2,3)) {
            jdbc.update("UPDATE post SET audit_status=?,published_at=DATE_SUB(NOW(),INTERVAL 30 MINUTE) WHERE id=?",audit,post);
            assertEquals(404,request("POST","/v1/interact/like",token,interaction(POST,post)).statusCode());
        }
        jdbc.update("UPDATE post SET audit_status=0,published_at=? WHERE id=?",java.time.LocalDateTime.now(),post);
        // Codes below are taken from the actual AuditStatus enum in the owner policy.
        jdbc.update("UPDATE post SET audit_status=? WHERE id=?",cn.jualn.miniapp.module.audit.enums.AuditStatus.PENDING.getCode(),post);
        assertEquals(200,request("POST","/v1/interact/like",token,interaction(POST,post)).statusCode());
        for(var type:List.of(ACTIVITY,EXAM)) {
            long id=subject(type,1);
            jdbc.update("UPDATE "+(type==ACTIVITY?"activity":"public_event")+" SET lifecycle_status=2 WHERE id=?",id);
            assertEquals(200,request("POST","/v1/interact/like",token,interaction(type,id)).statusCode());
            published(type,id,2);
            assertEquals(404,request("POST","/v1/interact/like",token,interaction(type,id)).statusCode());
        }
    }

    @Test void banCommitSurvivesKickoutFailureAndDoesNotEraseHistory() throws Exception {
        long id=subject(POST,2);
        request("POST","/v1/interact/like",token,interaction(POST,id));
        // Inject only the post-commit remote failure; Sa mappings remain real and readable in Redis.
        try(var stp=mockStatic(StpUtil.class)) {
            stp.when(()->StpUtil.kickout(42L)).thenThrow(new IllegalStateException("injected kickout failure"));
            userAccounts.banUser(restriction());
            stp.verify(()->StpUtil.kickout(42L));
        }
        assertEquals("42",String.valueOf(StpUtil.getStpLogic().getLoginIdByToken(token)));
        context.getBean(RedisService.class).set(cn.jualn.miniapp.common.constant.RedisKeyConstant.userAuthProfile(42L),
                cn.jualn.miniapp.module.user.bo.UserAuthBO.builder().id(42L).status(cn.jualn.miniapp.module.user.enums.UserStatus.NORMAL).role(cn.jualn.miniapp.module.user.enums.UserRole.USER).build(),Duration.ofMinutes(5));
        for(String path:List.of(comments(POST,id),"/v1/p1-probe/read","/v1/activities/10/subscription","/v1/activities/10/registrations/me")) {
            assertEquals(403,request("GET",path,token,null).statusCode());
        }
        assertEquals(403,request("GET","/v1/interact/like/count?targetType=ARTICLE&targetId=1",token,null).statusCode());
        jdbc.update("INSERT INTO comment(target_type,target_id,user_id,content,status,audit_status) VALUES(8,1,43,'article alias account guard',1,1)");
        long articleComment=jdbc.queryForObject("SELECT MAX(id) FROM comment WHERE target_type=8",Long.class);
        assertEquals(403,request("GET","/v1/interact/like/count?targetType=COMMENT&targetId="+articleComment,token,null).statusCode());
        assertEquals(401,request("GET","/v1/interact/like/count?targetType=ARTICLE&targetId=1",null,null).statusCode());
        assertEquals(200,request("GET","/v1/interact/like/count?targetType=POST&targetId="+id,null,null).statusCode());
        assertEquals(403,request("PUT","/v1/activities/10/subscription",token,null).statusCode());
        assertEquals(403,request("POST","/v1/activities/10/registrations",token,"{}").statusCode());
        assertEquals(403,request("POST","/v1/interact/like",token,interaction(POST,id)).statusCode());
        assertEquals(403,request("POST","/v1/auth/login",null,"{\"code\":\"p1-synthetic-code\"}").statusCode());
        assertEquals(200,request("GET",comments(POST,id),otherToken,null).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM like_record WHERE user_id=42 AND target_type=1 AND target_id=?",Integer.class,id));
        userAccounts.restoreUser(restriction());
        assertEquals(401,request("GET",comments(POST,id),token,null).statusCode());
    }

    @Test void rollbackDoesNotKickAndMuteExpiryUnknownStateRemainDistinct() throws Exception {
        try(var stp=mockStatic(StpUtil.class)) {
            var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.executeWithoutResult(s->{userAccounts.banUser(restriction());s.setRollbackOnly();});
            stp.verify(()->StpUtil.kickout(42L),never());
        }
        assertEquals(200,request("GET","/v1/p1-probe/read",token,null).statusCode());
        assertEquals(200,request("PUT","/v1/activities/10/subscription",token,null).statusCode());
        assertEquals(404,request("GET","/v1/activities/10/registrations/me",token,null).statusCode());
        jdbc.update("UPDATE user_profile SET status=2 WHERE id=42");
        assertEquals(200,request("PUT","/v1/activities/10/subscription",token,null).statusCode());
        assertEquals(404,request("GET","/v1/activities/10/registrations/me",token,null).statusCode());
        assertEquals(200,request("GET","/v1/p1-probe/read",token,null).statusCode());
        long id=subject(ACTIVITY,1);
        assertEquals(200,request("POST","/v1/interact/like",token,interaction(ACTIVITY,id)).statusCode());
        assertEquals(403,request("POST","/v1/comment",token,interaction(ACTIVITY,id).replace("}",",\"content\":\"muted\"}")).statusCode());
        jdbc.update("UPDATE user_profile SET status=3,ban_expire_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=42");
        assertEquals(200,request("GET","/v1/p1-probe/read",token,null).statusCode());
        jdbc.update("UPDATE user_profile SET status=99 WHERE id=42");
        assertEquals(503,request("GET","/v1/p1-probe/read",token,null).statusCode());
    }

    @Test void successfulKickoutRequiresFreshLoginAfterRestoreAndNewLoginRejectsBan() throws Exception {
        assertEquals(200,request("POST","/v1/auth/login",null,"{\"code\":\"p1-normal-code\"}").statusCode());
        jdbc.update("UPDATE user_profile SET status=2 WHERE id=42");
        assertEquals(200,request("POST","/v1/auth/login",null,"{\"code\":\"p1-muted-code\"}").statusCode());
        userAccounts.banUser(restriction());
        assertEquals(401,request("GET","/v1/p1-probe/read",token,null).statusCode());
        assertEquals(403,request("POST","/v1/auth/login",null,"{\"code\":\"p1-banned-code\"}").statusCode());
        userAccounts.restoreUser(restriction());
        assertEquals(401,request("GET","/v1/p1-probe/read",token,null).statusCode());
        assertEquals(200,request("POST","/v1/auth/login",null,"{\"code\":\"p1-restored-code\"}").statusCode());
    }

    @Test void validAdminQrActivationUsesTheSameCurrentAccountFactsAndSeparateTokens() throws Exception {
        String admin=activatedAdmin();
        assertEquals(200,request("GET","/v1/admin/p1-probe",admin,null).statusCode());
        assertEquals(401,request("GET","/v1/interact/like/count?targetType=ARTICLE&targetId=1",admin,null).statusCode());
        assertEquals(401,request("GET","/v1/p1-probe/read",admin,null).statusCode());
        jdbc.update("UPDATE user_profile SET status=3 WHERE id=9");
        assertEquals(401,request("GET","/v1/admin/p1-probe",admin,null).statusCode());
        jdbc.update("UPDATE user_profile SET ban_expire_at=? WHERE id=9",java.time.LocalDateTime.now().minusSeconds(2));
        // The expired account restriction can recover; revoked admin credentials cannot revive.
        assertEquals(401,request("GET","/v1/admin/p1-probe",admin,null).statusCode());
        assertEquals(200,request("GET","/v1/admin/p1-probe",activatedAdmin(),null).statusCode());
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

    @Test void tokenSpacesAndAuthenticationStorageFailuresNeverAllow() throws Exception {
        assertEquals(401,request("GET","/v1/admin/p1-probe",token,null).statusCode());
        String admin=AdminStpUtil.STP_LOGIC.createTokenValue(9,"default-device",3600,Map.of());
        AdminStpUtil.STP_LOGIC.createLoginSession(9,new SaLoginParameter().setToken(admin));
        assertEquals(401,request("GET","/v1/p1-probe/read",admin,null).statusCode());
        assertEquals(401,request("GET","/v1/admin/p1-probe",admin,null).statusCode()); // no QR activation
        String activated=activatedAdmin();
        faultContainer("p1.redis.container",()->assertTrue(request("GET","/v1/admin/p1-probe",activated,null).statusCode()>=500));
        assertEquals(200,request("GET","/v1/admin/p1-probe",activated,null).statusCode());
        faultContainer("p1.mysql.container",()->assertTrue(request("GET","/v1/admin/p1-probe",activated,null).statusCode()>=500));
        assertEquals(200,request("GET","/v1/admin/p1-probe",activated,null).statusCode());
        faultContainer("p1.redis.container",()->assertTrue(request("GET","/v1/p1-probe/read",token,null).statusCode()>=500));
        assertEquals(200,request("GET","/v1/p1-probe/read",token,null).statusCode());
        faultContainer("p1.mysql.container",()->assertTrue(request("GET","/v1/p1-probe/read",token,null).statusCode()>=500));
        assertEquals(200,request("GET","/v1/p1-probe/read",token,null).statusCode());
    }
    interface Checked {void run() throws Exception;}
    void faultContainer(String property,Checked check) throws Exception {
        String name=setting(property,property.equals("p1.redis.container")?"P1_REDIS_CONTAINER":"P1_MYSQL_CONTAINER");
        assertNotNull(name,"Dedicated container name required for real failure verification");
        assertTrue(name.matches("codex-p1-(redis|mysql)-[a-f0-9]{8}"));
        assertEquals(0,new ProcessBuilder("docker","pause",name).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());
        try {check.run();} finally {assertEquals(0,new ProcessBuilder("docker","unpause",name).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());}
    }
    AdminUserRestrictionBO restriction() {return AdminUserRestrictionBO.builder().operatorId(9L).targetUserId(42L).reason("P1 isolated verification").build();}

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
    HttpResponse<String> consume(Challenge c,String representation) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/v1/admin/auth/qr-login-sessions/"+c.id()+":consume"))
                .header("X-Admin-Login-Secret",c.secret()).header("X-Admin-Identity-Representation",representation).POST(HttpRequest.BodyPublishers.noBody()).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        responses.add(Map.of("method","POST","path","/v1/admin/auth/qr-login-sessions/{id}:consume","status",response.statusCode(),"body",response.statusCode()==200?"<credential redacted>":response.body()));
        return response;
    }
    @Test void assignmentQrIdentityIsExplicitAndReplayNeverChangesRepresentation() throws Exception {
        String grant=assignment(42,"NOTICE_MONITOR","NOTIFICATION");
        Challenge c=confirmed(42);
        assertEquals(409,consume(c,"LEGACY").statusCode());
        var store=context.getBean(AdminQrLoginStore.class);
        assertEquals(cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status.CONFIRMED,
                store.web("read",c.id(),cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto.sha256(c.secret()),null,null).session().status());
        var first=consume(c,"RBAC_V1");assertEquals(200,first.statusCode());
        var body=new ObjectMapper().readTree(first.body());String admin=body.get("token").asText();
        assertEquals("ASSIGNMENT",body.at("/profile/authorizationModel").asText());assertFalse(body.get("profile").has("roleCode"));assertFalse(body.get("profile").has("permissions"));
        assertEquals(first.body(),consume(c,"RBAC_V1").body());assertEquals(409,consume(c,"LEGACY").statusCode());
        assertEquals(200,request("GET","/v1/admin/p1-probe",admin,null).statusCode());
        assertEquals(409,request("GET","/v1/admin/auth/me",admin,null).statusCode());
        assertEquals(200,request("GET","/rbac-diagnostic-probe",admin,null).statusCode());
        assertEquals(401,request("GET","/rbac-diagnostic-probe",token,null).statusCode());
        jdbc.update("UPDATE rbac_user_role_assignment SET revoked_at=UTC_TIMESTAMP(6),revoke_change_id=UUID(),current_slot=NULL WHERE id=?",grant);
        var diagnosis=request("GET","/rbac-diagnostic-probe",admin,null);assertEquals(200,diagnosis.statusCode());
        assertFalse(new ObjectMapper().readTree(diagnosis.body()).get("canUseAdmin").asBoolean());
        assertEquals(401,request("GET","/v1/admin/p1-probe",admin,null).statusCode());
        assertEquals(200,request("GET","/rbac-diagnostic-probe",admin,null).statusCode());
        assertEquals(403,consume(c,"RBAC_V1").statusCode());
    }
    @Test void legacyQrRepresentationAndOldOrdinaryManagementRemainBounded() throws Exception {
        var c=confirmed(9);assertEquals(409,consume(c,"RBAC_V1").statusCode());
        var result=consume(c,"LEGACY");assertEquals(200,result.statusCode());
        var profile=new ObjectMapper().readTree(result.body()).get("profile");assertEquals("admin",profile.get("roleCode").asText());assertFalse(profile.has("authorizationModel"));
        for(int role:List.of(2,3)) {
            jdbc.update("UPDATE user_profile SET role=? WHERE id=42",role);
            long post=subject(POST,2);assertEquals(403,request("DELETE","/v1/post/"+post,token,null).statusCode());
            assertEquals(403,request("GET","/v1/reports/admin",token,null).statusCode());
            long target=subject(POST,2);
            jdbc.update("INSERT INTO report(reporter_id,target_type,target_id,reason,status) VALUES(43,1,?,1,0)",target);
            long report=jdbc.queryForObject("SELECT MAX(id) FROM report",Long.class);
            assertEquals(403,request("PUT","/v1/reports/admin/"+report+"/handle",token,"{\"status\":\"NORMAL_HANDLED\",\"handleRemark\":\"legacy conclusion\"}").statusCode());
            assertEquals(0,jdbc.queryForObject("SELECT status FROM report WHERE id=?",Integer.class,report));
            assertEquals(2,jdbc.queryForObject("SELECT status FROM post WHERE id=?",Integer.class,target));
        }
        jdbc.update("UPDATE user_profile SET role=2,status=2 WHERE id=42");
        assertEquals(403,request("DELETE","/v1/post/"+subject(POST,2),token,null).statusCode());
        jdbc.update("UPDATE user_profile SET status=1 WHERE id=42");
        jdbc.update("UPDATE user_profile SET role=1 WHERE id=42");
        assertEquals(403,request("DELETE","/v1/post/"+subject(POST,2),token,null).statusCode());
        assertEquals(403,request("GET","/v1/reports/admin",token,null).statusCode());
    }
    @Test void oldPostCommentReportPathsCannotBorrowLegacyRoleOrAnotherNamespace() throws Exception {
        jdbc.update("UPDATE user_profile SET role=3 WHERE id=42");
        assignment(42,"CONTENT_OPERATOR","ACTIVITY");
        long post=subject(POST,2);
        jdbc.update("INSERT INTO comment(target_type,target_id,user_id,content,status,audit_status) VALUES(1,?,43,'source test',1,1)",post);
        long comment=jdbc.queryForObject("SELECT MAX(id) FROM comment",Long.class);
        jdbc.update("UPDATE post SET comment_count=1 WHERE id=?",post);
        assertEquals(403,request("DELETE","/v1/post/"+post,token,null).statusCode());
        assertEquals(403,request("DELETE","/v1/comment/"+comment,token,null).statusCode());
        assertEquals(403,request("GET","/v1/reports/admin",token,null).statusCode());
        String grant=assignment(42,"CONTENT_OPERATOR","POST");
        assertEquals(403,request("DELETE","/v1/comment/"+comment,token,null).statusCode());
        assertEquals(403,request("DELETE","/v1/post/"+post,token,null).statusCode());
        assignment(42,"REPORT_HANDLER","REPORT");assertEquals(403,request("GET","/v1/reports/admin",token,null).statusCode());
        jdbc.update("UPDATE rbac_user_role_assignment SET revoked_at=UTC_TIMESTAMP(6),revoke_change_id=UUID(),current_slot=NULL WHERE user_id=42");
        assertEquals(403,request("DELETE","/v1/post/"+subject(POST,2),token,null).statusCode());
        assertEquals(403,request("GET","/v1/reports/admin",token,null).statusCode());
    }
    @Test void explicitAuthorityBackfillPreservesAssignmentAndBlocksIncompleteStartup() {
        var authorities=context.getBean(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class);
        jdbc.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT' WHERE user_id=42");
        String original=jdbc.queryForObject("SELECT version FROM rbac_authorization_authority WHERE user_id=42",String.class);
        jdbc.update("DELETE FROM rbac_authorization_authority WHERE user_id=43");
        var gate=new cn.jualn.miniapp.module.authorization.bootstrap.AuthorizationReadinessGate(authorities);
        assertThrows(IllegalStateException.class,gate::afterSingletonsInstantiated);
        assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->context.getBean(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class).current(43));
        var receipt=authorities.backfillLegacy();assertEquals(1,receipt.initialized());assertEquals(0,receipt.after().missing());
        assertEquals(original,jdbc.queryForObject("SELECT version FROM rbac_authorization_authority WHERE user_id=42",String.class));
        assertEquals("ASSIGNMENT",jdbc.queryForObject("SELECT source FROM rbac_authorization_authority WHERE user_id=42",String.class));
        assertEquals(0,authorities.backfillLegacy().initialized());assertDoesNotThrow(gate::afterSingletonsInstantiated);
        jdbc.update("UPDATE rbac_authorization_authority SET version=' ' WHERE user_id=43");
        assertEquals(1,authorities.backfillLegacy().after().corrupt());assertThrows(IllegalStateException.class,gate::afterSingletonsInstantiated);
    }
    @Test void newUserAuthorityCommitsAtomicallyAndBackfillCanRunConcurrently() throws Exception {
        String rolled="rbac-rollback-"+java.util.UUID.randomUUID();
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(s->{userIdentities.getUserInfo(rolled);s.setRollbackOnly();});
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_profile WHERE openid=?",Integer.class,rolled));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var create=pool.submit(()->userIdentities.getUserInfo("rbac-create-"+java.util.UUID.randomUUID()));
            var fill=pool.submit(()->context.getBean(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class).backfillLegacy());
            create.get(10,java.util.concurrent.TimeUnit.SECONDS);fill.get(10,java.util.concurrent.TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(0,context.getBean(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class).coverage().missing());
    }
    @Test void realUserOwnerRootGuardSerializesAccountChangesAndRejectsLegacyRoleBackdoor() throws Exception {
        assignment(42,"ROOT","AUTHORIZATION");
        jdbc.update("UPDATE rbac_authorization_authority SET source='LEGACY' WHERE user_id=42");
        assertDoesNotThrow(()->userAccounts.banUser(restriction())); // Shadow ROOT never changes LEGACY governance.
        jdbc.update("UPDATE user_profile SET status=1,ban_expire_at=NULL WHERE id=42");
        for(long id:List.of(60L,61L)) jdbc.update("INSERT INTO user_profile(id,openid,nickname,role,status) VALUES(?,?,?,1,1) ON DUPLICATE KEY UPDATE status=1",id,"root-test-"+id,"synthetic");
        context.getBean(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class).backfillLegacy();
        for(long id:List.of(60L,61L)){assignment(id,"ROOT","AUTHORIZATION");assignment(id,"USER_GOVERNOR","USER");}
        var forbidden=restriction();forbidden.setTargetUserId(60L);assertEquals("/problems/authorization-management-forbidden",
                assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->userAccounts.banUser(forbidden)).getType());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(()->banRoot(60,61,start));var b=pool.submit(()->banRoot(61,60,start));start.countDown();
            assertEquals(1,(a.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(b.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0));
        } finally {pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_profile WHERE id IN (60,61) AND status=1",Integer.class));
        long survivor=jdbc.queryForObject("SELECT id FROM user_profile WHERE id IN (60,61) AND status=1",Long.class);
        long banned=survivor==60?61:60;
        userAccounts.restoreUser(cn.jualn.miniapp.module.user.bo.AdminUserRestrictionBO.builder().operatorId(survivor)
                .targetUserId(banned).reason("synthetic restore does not remove the surviving root").build());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM user_profile WHERE id IN (60,61) AND status=1",Integer.class));
        var role=cn.jualn.miniapp.module.user.bo.AdminUserRoleChangeBO.builder().operatorId(9L).targetUserId(60L).targetRole(cn.jualn.miniapp.module.user.enums.UserRole.ADMIN).reason("synthetic forbidden conversion").build();
        assertThrows(RuntimeException.class,()->userAccounts.changeUserRole(role));assertEquals(1,jdbc.queryForObject("SELECT role FROM user_profile WHERE id=60",Integer.class));
    }
    @Test void legacyRoleWriteIsFrozenForEverySourceWithoutChangingFacts() throws Exception {
        var change=cn.jualn.miniapp.module.user.bo.AdminUserRoleChangeBO.builder().operatorId(9L).targetUserId(43L)
                .targetRole(cn.jualn.miniapp.module.user.enums.UserRole.OPR).reason("synthetic legacy compatibility").build();
        for(boolean switched:new boolean[]{false,true}) {
            if(switched) assignment(43,"NOTICE_MONITOR","NOTIFICATION");
            var before=jdbc.queryForList("SELECT * FROM rbac_authorization_authority ORDER BY user_id");
            var grants=jdbc.queryForList("SELECT * FROM rbac_user_role_assignment ORDER BY id");
            int audit=jdbc.queryForObject("SELECT COUNT(*) FROM admin_operation_log",Integer.class);
            assertEquals("/problems/authorization-source-migration-required",assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                    ()->userAccounts.changeUserRole(change)).getType());
            String admin=activatedAdmin();
            assertEquals(409,request("PUT","/v1/admin/users/43/role",admin,"{\"role\":\"operator\",\"reason\":\"synthetic obsolete role request\"}").statusCode());
            assertEquals(1,jdbc.queryForObject("SELECT role FROM user_profile WHERE id=43",Integer.class));
            assertEquals(before,jdbc.queryForList("SELECT * FROM rbac_authorization_authority ORDER BY user_id"));
            assertEquals(grants,jdbc.queryForList("SELECT * FROM rbac_user_role_assignment ORDER BY id"));
            assertEquals(audit,jdbc.queryForObject("SELECT COUNT(*) FROM admin_operation_log",Integer.class));
        }
    }
    boolean banRoot(long actor,long target,java.util.concurrent.CountDownLatch start) throws Exception {
        start.await();try {userAccounts.banUser(cn.jualn.miniapp.module.user.bo.AdminUserRestrictionBO.builder().operatorId(actor).targetUserId(target).reason("synthetic root race").build());return true;}
        catch(BusinessException|cn.jualn.miniapp.common.exception.ContractProblemException rejected){return false;}
    }
    @Test void reportResolutionRequiresDispositionOnlyWhenItActuallyTakesContentDown() throws Exception {
        assignment(42,"REPORT_HANDLER","REPORT");String admin=new ObjectMapper().readTree(consume(confirmed(42),"RBAC_V1").body()).get("token").asText();
        long normal=subject(POST,2),violation=subject(POST,2);
        for(long id:List.of(normal,violation)) jdbc.update("INSERT INTO report(reporter_id,target_type,target_id,reason,status) VALUES(43,1,?,1,0)",id);
        assertEquals(200,request("POST","/v1/admin/report-cases/post/"+normal+"/decision",admin,"{\"decision\":\"normal\",\"remark\":\"synthetic normal conclusion\"}").statusCode());
        assertEquals(2,jdbc.queryForObject("SELECT status FROM post WHERE id=?",Integer.class,normal));
        String path="/v1/admin/report-cases/post/"+violation+"/decision",body="{\"decision\":\"violation\",\"remark\":\"synthetic confirmed violation\"}";
        assertEquals(403,request("POST",path,admin,body).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT status FROM report WHERE target_id=? AND target_type=1",Integer.class,violation));
        assertEquals(2,jdbc.queryForObject("SELECT status FROM post WHERE id=?",Integer.class,violation));
        assignment(42,"CONTENT_OPERATOR","ACTIVITY");assertEquals(403,request("POST",path,admin,body).statusCode());
        assignment(42,"CONTENT_OPERATOR","POST");assertEquals(200,request("POST",path,admin,body).statusCode());
        assertNotEquals(2,jdbc.queryForObject("SELECT status FROM post WHERE id=?",Integer.class,violation));
        assertNotEquals(0,jdbc.queryForObject("SELECT status FROM report WHERE target_id=? AND target_type=1",Integer.class,violation));
    }
    @Test void scopedContentRowsAndSummaryExcludeOtherNamespacesAndWarmRoleFacts() throws Exception {
        assignment(42,"CONTENT_OPERATOR","ACTIVITY");
        jdbc.update("UPDATE user_profile SET role=3 WHERE id=42");
        StpUtil.getStpLogic().getSessionByLoginId(42,false).set("role",cn.jualn.miniapp.module.user.enums.UserRole.ADMIN);
        long activity=subject(ACTIVITY,1);long post=subject(POST,2);
        jdbc.update("INSERT INTO comment(target_type,target_id,user_id,content,status,audit_status) VALUES(2,?,43,'scoped activity comment',1,1),(1,?,43,'other namespace comment',1,1)",activity,post);
        String admin=new ObjectMapper().readTree(consume(confirmed(42),"RBAC_V1").body()).get("token").asText();
        var list=request("GET","/v1/admin/contents",admin,null);assertEquals(200,list.statusCode());
        var data=new ObjectMapper().readTree(list.body()).get("data");
        assertTrue(data.get("items").size()>0);for(var row:data.get("items")) assertEquals("comment",row.get("type").asText());
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE target_type=2 AND status=1 AND deleted_at IS NULL",Long.class).longValue(),data.at("/summary/published").asLong());
        assertEquals(403,request("GET","/v1/admin/contents?type=post",admin,null).statusCode());
        jdbc.update("DELETE FROM rbac_authorization_authority WHERE user_id=42");
        assertEquals(503,request("GET","/v1/admin/p1-probe",admin,null).statusCode());
        assertEquals(503,request("DELETE","/v1/post/"+post,token,null).statusCode());
        context.getBean(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class).backfillLegacy();
    }

    @Test void mixedReviewRowsCountsCursorAndSummaryFollowPersistentCommentSubject() throws Exception {
        jdbc.update("DELETE FROM content_audit_log");
        long post=subject(POST,2), activity=subject(ACTIVITY,1), event=subject(EXAM,1);
        long postTask=reviewComment(1,post), activityTask=reviewComment(2,activity);
        reviewComment(3,event);reviewComment(99,post);reviewComment(1,999999999L);
        jdbc.update("INSERT INTO article_publisher(id,display_name,updated_by,updated_at) VALUES(98765,'review gate fixture',9,NOW(6))");
        jdbc.update("INSERT INTO article(title,summary,body,plain_text,publisher_id,created_by,updated_by,created_at,updated_at) VALUES('review gate','fixture',JSON_OBJECT(),'fixture',98765,9,9,NOW(6),NOW(6))");
        long articleTask=reviewComment(8,jdbc.queryForObject("SELECT MAX(id) FROM article",Long.class));
        String grant=assignment(42,"CONTENT_REVIEWER","POST");
        String admin=new ObjectMapper().readTree(consume(confirmed(42),"RBAC_V1").body()).get("token").asText();
        var response=request("GET","/v1/admin/reviews?targetType=comment&pageSize=1",admin,null);
        assertEquals(200,response.statusCode());var data=new ObjectMapper().readTree(response.body()).get("data");
        assertEquals(1,data.get("total").asLong());assertEquals(1,data.get("items").size());
        assertEquals(1,data.get("summary").get("pending").asLong());assertFalse(data.get("hasMore").asBoolean());
        assertEquals(200,request("GET","/v1/admin/reviews/"+postTask,admin,null).statusCode());
        assertEquals(403,request("GET","/v1/admin/reviews/"+activityTask,admin,null).statusCode());
        assignment(42,"CONTENT_REVIEWER","ACTIVITY");
        var both=request("GET","/v1/admin/reviews?targetType=comment&pageSize=1",admin,null);
        data=new ObjectMapper().readTree(both.body()).get("data");assertEquals(2,data.get("total").asLong());
        assertTrue(data.get("hasMore").asBoolean());
        var next=request("GET","/v1/admin/reviews?targetType=comment&pageSize=1&cursor="+data.get("nextCursor").asText(),admin,null);
        assertEquals(1,new ObjectMapper().readTree(next.body()).get("data").get("items").size());
        jdbc.update("UPDATE rbac_user_role_assignment SET revoked_at=UTC_TIMESTAMP(6),revoke_change_id=UUID(),current_slot=NULL WHERE id=?",grant);
        assertEquals(403,request("GET","/v1/admin/reviews/"+postTask,admin,null).statusCode());
        assertEquals(200,request("GET","/v1/admin/reviews/"+activityTask,admin,null).statusCode());
        data=new ObjectMapper().readTree(request("GET","/v1/admin/reviews?targetType=comment",admin,null).body()).get("data");
        assertEquals(1,data.get("total").asLong());assertEquals(1,data.get("summary").get("pending").asLong());
        String legacyAdmin=new ObjectMapper().readTree(consume(confirmed(9),"LEGACY").body()).get("token").asText();
        var legacy=request("GET","/v1/admin/reviews?targetType=comment",legacyAdmin,null);
        assertEquals(3,new ObjectMapper().readTree(legacy.body()).get("data").get("total").asLong());
        assertEquals(404,request("GET","/v1/admin/reviews/"+articleTask,legacyAdmin,null).statusCode());
        assertEquals(403,reviewDecision(articleTask,legacyAdmin));
    }
    @Test void mixedReviewDecisionChecksBothPermissionsAndCommitsOnlyItsOwnNamespace() throws Exception {
        long postTask=reviewComment(1,subject(POST,2)), activityTask=reviewComment(2,subject(ACTIVITY,1));
        assignment(42,"CONTENT_REVIEWER","POST");
        String admin=new ObjectMapper().readTree(consume(confirmed(42),"RBAC_V1").body()).get("token").asText();
        assertEquals(403,reviewDecision(activityTask,admin));
        assertEquals(404,reviewDecision(reviewComment(1,999999998L),admin));
        assertEquals(200,reviewDecision(postTask,admin));
        long comment=jdbc.queryForObject("SELECT target_id FROM content_audit_log WHERE id=?",Long.class,postTask);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE target_type=4 AND target_id=? AND audit_source=2",Integer.class,comment));
        long other=jdbc.queryForObject("SELECT target_id FROM content_audit_log WHERE id=?",Long.class,activityTask);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE target_type=4 AND target_id=? AND audit_source=2",Integer.class,other));
    }
    int reviewDecision(long task,String admin) throws Exception {
        String path="/v1/admin/reviews/"+task+"/decisions";
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path))
                .header("Authorization","Bearer "+admin).header("Content-Type","application/json")
                .header("Idempotency-Key","review-final-"+java.util.UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"reject\",\"reasonCode\":\"other\"}")).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        responses.add(Map.of("method","POST","path",path,"status",response.statusCode(),"body",response.body()));
        return response.statusCode();
    }
    long reviewComment(int type,long id) {
        jdbc.update("INSERT INTO comment(target_type,target_id,user_id,content,status,audit_status) VALUES(?,?,43,'review fixture',0,2)",type,id);
        long comment=jdbc.queryForObject("SELECT MAX(id) FROM comment",Long.class);
        jdbc.update("INSERT INTO content_audit_log(target_type,target_id,audit_source,wx_result,final_result) VALUES(4,?,1,1,2)",comment);
        return jdbc.queryForObject("SELECT MAX(id) FROM content_audit_log",Long.class);
    }
    @Test void unboundRegisteredMediaRequiresExactUploaderPurposeAndDurableRegistry() {
        jdbc.update("INSERT INTO media_attachment(type,kind,registered,registered_by,object_key,url) VALUES(1,'IMAGE',1,42,'activity/42/finalization.png','https://synthetic.invalid/finalization.png')");
        long id=jdbc.queryForObject("SELECT MAX(id) FROM media_attachment",Long.class);
        jdbc.update("INSERT INTO media_upload_record(object_key,user_id,target_type,status,bound_attachment_id,cleanup_after) VALUES('activity/42/finalization.png',42,2,1,?,NOW())",id);
        var records=new cn.jualn.miniapp.module.media.service.MediaUploadRecordService(
                context.getBean(cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper.class),mock(cn.jualn.miniapp.third.cos.service.CosService.class));
        assertEquals(ACTIVITY,records.registeredPurpose(id,42));assertNull(records.registeredPurpose(id,43));
        jdbc.update("UPDATE media_upload_record SET cleanup_started_at=NOW() WHERE bound_attachment_id=?",id);
        assertNull(records.registeredPurpose(id,42));
        jdbc.update("UPDATE media_upload_record SET cleanup_started_at=NULL,target_type=6 WHERE bound_attachment_id=?",id);
        assertNull(records.registeredPurpose(id,42));
        jdbc.update("UPDATE media_upload_record SET target_type=2,object_key='activity/42/other.png' WHERE bound_attachment_id=?",id);
        assertNull(records.registeredPurpose(id,42));
    }

    static final String RBAC="/v1/admin/authorization";
    String rbacFixture() throws Exception {
        // Only ROOT and the complete read-only NOTICE_MONITOR are enabled by this fixture.
        var artifact=new ObjectMapper().readTree(Files.readString(Path.of("src/main/resources/rbac/rbac-v1.json")));
        for(var p:artifact.get("permissions")) jdbc.update("UPDATE rbac_permission_definition SET lifecycle=?,readiness='NOT_READY',grantable=0,unavailable_reason=? WHERE code=?",
                p.get("lifecycle").asText(),p.get("unavailableReason").asText(),p.get("code").asText());
        jdbc.update("UPDATE rbac_role SET lifecycle='REGISTERED',readiness='NOT_READY',grantable=0,unavailable_reason='PROVIDER_NOT_VERIFIED'");
        assignment(42,"ROOT","AUTHORIZATION");enableRole("NOTICE_MONITOR");
        jdbc.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT',version=UUID() WHERE user_id=43");
        return new ObjectMapper().readTree(consume(confirmed(42),"RBAC_V1").body()).get("token").asText();
    }
    String grantBody(long user,String space) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("roleCode","NOTICE_MONITOR","namespace",space,"scope",Map.of("type","GLOBAL"),
                "reason","HTTP synthetic approved purpose","expectedAuthorizationVersion",jdbc.queryForObject("SELECT version FROM rbac_authorization_authority WHERE user_id=?",String.class,user)));
    }
    HttpResponse<String> rbacWrite(String path,String admin,String key,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(12))
                .header("Content-Type","application/json");
        if(admin!=null) builder.header("Authorization","Bearer "+admin);
        if(key!=null) builder.header("Idempotency-Key",key);
        var response=client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        responses.add(Map.of("method","POST","path",path,"status",response.statusCode(),"cacheControl",response.headers().firstValue("Cache-Control").orElse(""),
                "contentType",response.headers().firstValue("Content-Type").orElse(""),"location",response.headers().firstValue("Location").orElse(""),"requestBody",body,"body",response.body()));
        return response;
    }
    com.fasterxml.jackson.databind.JsonNode data(HttpResponse<String> response,int status) throws Exception {
        assertEquals(status,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
        return new ObjectMapper().readTree(response.body());
    }
    @Test void rbacNineOperationsUseCanonicalWireAndFixedSuccessfulReceipts() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments";
        assertEquals(73,data(request("GET",RBAC+"/permissions",admin,null),200).get("items").size());
        assertEquals(18,data(request("GET",RBAC+"/roles",admin,null),200).get("items").size());
        assertEquals(2,data(request("GET",RBAC+"/roles/NOTICE_MONITOR",admin,null),200).get("permissionCodes").size());
        assertTrue(data(request("GET",path,admin,null),200).get("items").isEmpty());
        String body=grantBody(43,"NOTIFICATION"),key="http-rbac-grant-0001";
        var first=rbacWrite(path,admin,key,body);var grant=data(first,201);
        assertTrue(first.headers().firstValue("Location").orElseThrow().endsWith(grant.get("assignment").get("assignmentId").asText()));
        assertEquals(first.body(),rbacWrite(path,admin,key,body).body());
        String detail=first.headers().firstValue("Location").orElseThrow();
        var assignment=data(request("GET",detail,admin,null),200).get("assignment");assertEquals("ACTIVE",assignment.get("status").asText());
        String revoke=new ObjectMapper().writeValueAsString(Map.of("reason","HTTP responsibility ended","expectedAuthorizationVersion",grant.get("authorizationVersion").asText(),"expectedAssignmentVersion",assignment.get("assignmentVersion").asText()));
        var revoked=rbacWrite(detail+":revoke",admin,"http-rbac-revoke-0001",revoke);data(revoked,200);
        assertEquals(revoked.body(),rbacWrite(detail+":revoke",admin,"http-rbac-revoke-0001",revoke).body());
        assertEquals(first.body(),rbacWrite(path,admin,key,body).body());
        assertEquals("REVOKED",data(request("GET",detail,admin,null),200).get("assignment").get("status").asText());
        assertEquals(1,data(request("GET",path+"?status=REVOKED",admin,null),200).get("items").size());
        var audit=data(request("GET",RBAC+"/changes?targetUserId=43",admin,null),200);assertEquals(2,audit.get("items").size());
        assertFalse(audit.toString().contains("idempotencyKey"));assertFalse(audit.toString().contains("requestFingerprint"));assertFalse(audit.toString().contains("Snapshot"));
        var caps=data(request("GET",RBAC+"/me/capabilities",admin,null),200);assertTrue(caps.get("canUseAdmin").asBoolean());assertEquals("ROOT",caps.get("grants").get(0).get("roleCode").asText());
    }
    String expiryBody(String body,String jsonValue) {return body.substring(0,body.length()-1)+",\"expiresAt\":"+jsonValue+"}";}
    @Test void rbacTimedGrantNormalizesOffsetsRecoversTheSameReceiptAndNeverRegrantsAfterRevocation() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments",base=grantBody(43,"NOTIFICATION"),key="http-d06-offset-0001";
        String offset=expiryBody(base,"\"2099-11-15T10:00:00.123456+08:00\"");
        var first=rbacWrite(path,admin,key,offset);var granted=data(first,201);
        String instant="2099-11-15T02:00:00.123456Z",detail=first.headers().firstValue("Location").orElseThrow();
        assertEquals(instant,granted.get("assignment").get("expiresAt").asText());
        for(String equivalent:List.of(instant,"2099-11-14T21:00:00.123456-05:00")) {
            var replay=rbacWrite(path,admin,key,expiryBody(base,"\""+equivalent+"\""));data(replay,201);
            assertEquals(first.body(),replay.body());assertEquals(first.headers().firstValue("Location"),replay.headers().firstValue("Location"));
        }
        var conflict=data(rbacWrite(path,admin,key,expiryBody(base,"\"2099-11-15T02:00:00.123457Z\"")),409);
        assertEquals("/problems/idempotency-key-conflict",conflict.get("type").asText());
        assertEquals("2099-11-15 02:00:00.123456",jdbc.queryForObject("SELECT DATE_FORMAT(expires_at,'%Y-%m-%d %H:%i:%s.%f') FROM rbac_user_role_assignment WHERE user_id=43",String.class));
        assertEquals(instant,context.getBean(cn.jualn.miniapp.module.authorization.mapper.RbacMapper.class).assignments(43).get(0).getExpiresAt().toInstant(java.time.ZoneOffset.UTC).toString());
        var assignment=data(request("GET",detail,admin,null),200).get("assignment");
        assertEquals(instant,assignment.get("expiresAt").asText());
        assertEquals(instant,data(request("GET",path,admin,null),200).get("items").get(0).get("expiresAt").asText());
        String targetAdmin=new ObjectMapper().readTree(consume(confirmed(43),"RBAC_V1").body()).get("token").asText();
        var capabilities=data(request("GET",RBAC+"/me/capabilities",targetAdmin,null),200);
        assertEquals(instant,capabilities.get("grants").get(0).get("expiresAt").asText());
        String revoke=new ObjectMapper().writeValueAsString(Map.of("reason","D06 ended","expectedAuthorizationVersion",granted.get("authorizationVersion").asText(),"expectedAssignmentVersion",assignment.get("assignmentVersion").asText()));
        data(rbacWrite(detail+":revoke",admin,"http-d06-revoke-0001",revoke),200);
        var replay=rbacWrite(path,admin,key,offset);data(replay,201);assertEquals(first.body(),replay.body());
        assertEquals("REVOKED",data(request("GET",detail,admin,null),200).get("assignment").get("status").asText());
        assertFalse(data(request("GET",RBAC+"/me/capabilities",targetAdmin,null),200).get("canUseAdmin").asBoolean());
        var audit=data(request("GET",RBAC+"/changes?targetUserId=43",admin,null),200);assertEquals(2,audit.get("items").size());
        for(var change:audit.get("items")) assertEquals(instant,change.get("after").get("expiresAt").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=43",Integer.class));
    }
    @Test void rbacPublicExpiryRejectsAllExcessPrecisionBeforeAnySuccessAndAcceptsZeroThroughSixDigits() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments",base=grantBody(43,"NOTIFICATION");
        int n=0;
        for(String value:List.of("\"2099-01-01T00:00:00.1234567Z\"","\"2099-01-01T00:00:00.123456000Z\"",
                "\"2099-01-01T00:00:00.0000000Z\"","\"2099-01-01T00:00:00.123456789123456789Z\"",
                "\"2099-01-01T00:00:00\"","\"2099-02-29T00:00:00Z\"","\"2099-01-01T00:00:00+24:00\"",
                "\"2099-01-01T00:00:00+08:60\"","\"2099-01-01T00:00:00.Z\"","\"1000-01-01T00:00:00+00:01\"",
                "\"9999-12-31T23:59:59-00:01\"","\"+10000-01-01T00:00:00Z\"","\"2020-01-01T00:00:00Z\"",
                "\"\"","null","false","123","[]","{}")) {
            var invalid=data(rbacWrite(path,admin,"http-d06-invalid-"+(++n),expiryBody(base,value)),400);
            assertEquals("/problems/validation-error",invalid.get("type").asText());
        }
        data(rbacWrite(path,admin,"http-d06-root-expiry",expiryBody(base.replace("NOTICE_MONITOR","ROOT").replace("NOTIFICATION","AUTHORIZATION"),"\"2099-01-01T00:00:00Z\"")),400);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=43",Integer.class));
        for(int digits=0;digits<=6;digits++) {
            String fraction=digits==0?"":"."+"123456".substring(0,digits),key="http-d06-valid-000"+digits;
            String body=grantBody(43,"NOTIFICATION"),local="2099-01-01T08:00:00"+fraction+"+08:00";
            var first=rbacWrite(path,admin,key,expiryBody(body,"\""+local+"\""));var granted=data(first,201);
            String normalized=java.time.LocalDateTime.parse("2099-01-01T00:00:00"+fraction).toInstant(java.time.ZoneOffset.UTC).toString();
            assertEquals(normalized,granted.get("assignment").get("expiresAt").asText());
            String trailing=digits==0?".000000":fraction+"0".repeat(6-digits);
            var same=rbacWrite(path,admin,key,expiryBody(body,"\"2099-01-01T00:00:00"+trailing+"Z\""));data(same,201);assertEquals(first.body(),same.body());
            var a=granted.get("assignment");String revoke=new ObjectMapper().writeValueAsString(Map.of("reason","end precision case","expectedAuthorizationVersion",granted.get("authorizationVersion").asText(),"expectedAssignmentVersion",a.get("assignmentVersion").asText()));
            data(rbacWrite(first.headers().firstValue("Location").orElseThrow()+":revoke",admin,"http-d06-valid-revoke-"+digits,revoke),200);
        }
    }
    @Test void rbacDatabaseTimeExpiryRevokesOldTokensAndPreservesHistoricalReceiptsAndAudit() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments";
        var mapper=context.getBean(cn.jualn.miniapp.module.authorization.mapper.RbacMapper.class);
        var expiry=mapper.now().plusSeconds(3).withNano(654321000);
        String value=cn.jualn.miniapp.module.authorization.support.RbacTime.wire(expiry),key="http-d06-real-expiry-01";
        String body=expiryBody(grantBody(43,"NOTIFICATION"),"\""+value+"\"");
        var first=rbacWrite(path,admin,key,body);data(first,201);String detail=first.headers().firstValue("Location").orElseThrow();
        String targetAdmin=new ObjectMapper().readTree(consume(confirmed(43),"RBAC_V1").body()).get("token").asText();
        assertTrue(data(request("GET",RBAC+"/me/capabilities",targetAdmin,null),200).get("canUseAdmin").asBoolean());
        long deadline=System.nanoTime()+Duration.ofSeconds(6).toNanos();
        while(mapper.now().isBefore(expiry) && System.nanoTime()<deadline) Thread.sleep(25);
        assertFalse(mapper.now().isBefore(expiry));
        assertFalse(context.getBean(cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader.class).read(43).canUseAdmin());
        assertFalse(data(request("GET",RBAC+"/me/capabilities",targetAdmin,null),200).get("canUseAdmin").asBoolean());
        assertEquals("EXPIRED",data(request("GET",detail,admin,null),200).get("assignment").get("status").asText());
        assertEquals(1,data(request("GET",path+"?status=EXPIRED",admin,null),200).get("items").size());
        var replay=rbacWrite(path,admin,key,body);data(replay,201);assertEquals(first.body(),replay.body());
        assertEquals(1,data(request("GET",RBAC+"/changes?targetUserId=43",admin,null),200).get("items").size());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=43",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change",Integer.class));
    }
    @Test void rbacAuthenticationAuthorityFailuresAndNoGrantDiagnosticRemainDistinct() throws Exception {
        String admin=rbacFixture();
        data(request("GET",RBAC+"/roles",null,null),401);data(request("GET",RBAC+"/roles",token,null),401);
        String unactivated=AdminStpUtil.STP_LOGIC.createTokenValue(42,"default-device",3600,Map.of());
        AdminStpUtil.STP_LOGIC.createLoginSession(42,new SaLoginParameter().setToken(unactivated));
        data(request("GET",RBAC+"/roles",unactivated,null),401);
        String legacy=activatedAdmin();data(request("GET",RBAC+"/roles",legacy,null),403);
        data(request("GET",RBAC+"/users/does-not-exist/role-assignments",legacy,null),403);
        data(request("GET",RBAC+"/me/capabilities",legacy,null),409);
        String witness=assignment(43,"NOTICE_MONITOR","NOTIFICATION");
        String readerAdmin=new ObjectMapper().readTree(consume(confirmed(43),"RBAC_V1").body()).get("token").asText();
        jdbc.update("UPDATE rbac_user_role_assignment SET revoked_at=UTC_TIMESTAMP(6),revoke_change_id=UUID(),current_slot=NULL WHERE id=?",witness);
        var empty=data(request("GET",RBAC+"/me/capabilities",readerAdmin,null),200);assertFalse(empty.get("canUseAdmin").asBoolean());assertTrue(empty.get("grants").isEmpty());
        data(request("GET",RBAC+"/roles",readerAdmin,null),403);
        assertEquals(401,request("GET","/v1/admin/p1-probe",readerAdmin,null).statusCode());
        data(request("GET",RBAC+"/me/capabilities",readerAdmin,null),200);
        jdbc.update("DELETE FROM rbac_authorization_authority WHERE user_id=42");data(request("GET",RBAC+"/roles",admin,null),503);
        context.getBean(cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class).backfillLegacy();
    }
    @Test void rbacStrictBodiesVersionConflictsAndPublicExpiryGateNeverMutateOnFailure() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments",body=grantBody(43,"NOTIFICATION");
        data(rbacWrite(path,admin,null,body),400);
        data(rbacWrite(path,admin,"http-rbac-input-0001",body.replace("\"scope\":{\"type\":\"GLOBAL\"}","\"scope\":{\"type\":\"ORGANIZATION\"}")),400);
        data(rbacWrite(path,admin,"http-rbac-input-0002",body.substring(0,body.length()-1)+",\"permissionCodes\":[\"*\"]}"),400);
        data(rbacWrite(path,admin,"http-rbac-input-0003",body.substring(0,body.length()-1)+",\"expiresAt\":null}"),400);
        data(rbacWrite(path,admin,"http-rbac-input-0004",body.substring(0,body.length()-1)+",\"expiresAt\":\"2099-01-01T08:00:00.123456789123+08:00\"}"),400);
        data(rbacWrite(path,admin,"http-rbac-input-0005",body.replace("NOTIFICATION","POST")),403);
        data(rbacWrite(path,admin,"K".repeat(129),body),400);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE user_id=43",Integer.class));
        var good=data(rbacWrite(path,admin,"http-rbac-fixed-0001",body),201);
        data(rbacWrite(path,admin,"http-rbac-fixed-0001",body.replace("HTTP synthetic approved purpose","different purpose")),409);
        data(rbacWrite(path,admin,"http-rbac-stale-0001",body),409);
        data(request("GET",RBAC+"/users/42/role-assignments/"+good.get("assignment").get("assignmentId").asText(),admin,null),404);
        data(rbacWrite(RBAC+"/users/42/role-assignments",admin,"http-rbac-self-00001",grantBody(42,"SYSTEM")),403);
    }
    @Test void rbacHttpReasonCodePointsAndEquivalentJsonKeepTheSameIntent() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments";
        String original=grantBody(43,"NOTIFICATION");
        String reason="\uD83D\uDE00".repeat(500);
        String body=original.replace("HTTP synthetic approved purpose",reason);
        var first=rbacWrite(path,admin,"unicode-http-receipt-001",body);data(first,201);
        assertEquals(first.body(),rbacWrite(path,admin,"unicode-http-receipt-001",body.replace("\uD83D\uDE00","\\uD83D\\uDE00")).body());
        assertEquals(500,jdbc.queryForObject("SELECT CHAR_LENGTH(reason) FROM rbac_authorization_change WHERE target_user_id=43",Integer.class));
        data(rbacWrite(path,admin,"unicode-http-invalid-001",original.replace("HTTP synthetic approved purpose",reason+"x")),400);
        data(rbacWrite(path,admin,"unicode-http-invalid-002",original.replace("HTTP synthetic approved purpose","\u00a0\ufeff")),400);
    }
    @Test void rbacHttpPostCommitUnknownResponseRecoversWithoutExecutingAgain() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments",body=grantBody(43,"NOTIFICATION"),key="postcommit-http-key-001";
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path))
                .header("Authorization","Bearer "+admin).header("Content-Type","application/json").header("Idempotency-Key",key)
                .header("X-P1-Post-Commit-Unknown","true").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var unknown=client.send(request,HttpResponse.BodyHandlers.ofString());data(unknown,503);
        responses.add(Map.of("method","POST","path",path,"status",unknown.statusCode(),"cacheControl",unknown.headers().firstValue("Cache-Control").orElse(""),
                "contentType",unknown.headers().firstValue("Content-Type").orElse(""),"location","","requestBody",body,"body",unknown.body()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change WHERE target_user_id=43",Integer.class));
        var recovered=rbacWrite(path,admin,key,body);data(recovered,201);
        assertEquals(recovered.body(),rbacWrite(path,admin,key,body).body());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change WHERE target_user_id=43",Integer.class));
    }

    String revokeBody(String admin,String detail) throws Exception {
        var d=data(request("GET",detail,admin,null),200);
        return new ObjectMapper().writeValueAsString(Map.of("reason","synthetic HTTP revocation","expectedAuthorizationVersion",d.get("authorizationVersion").asText(),
                "expectedAssignmentVersion",d.get("assignment").get("assignmentVersion").asText()));
    }
    @Test void rbacAssignmentCursorBindsActorTargetFiltersAndCurrentVersions() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments";
        var one=rbacWrite(path,admin,"cursor-http-grant-0001",grantBody(43,"NOTIFICATION"));data(one,201);
        String detail=one.headers().firstValue("Location").orElseThrow();
        data(rbacWrite(detail+":revoke",admin,"cursor-http-revoke-0001",revokeBody(admin,detail)),200);
        data(rbacWrite(path,admin,"cursor-http-grant-0002",grantBody(43,"NOTIFICATION")),201);
        var first=data(request("GET",path+"?pageSize=1",admin,null),200);String cursor=first.get("nextCursor").asText();
        var second=data(request("GET",path+"?pageSize=1&cursor="+cursor,admin,null),200);
        assertEquals(first.get("evaluatedAt"),second.get("evaluatedAt"));
        assertNotEquals(first.get("items").get(0).get("assignmentId"),second.get("items").get(0).get("assignmentId"));
        data(request("GET",path+"?pageSize=1&namespace=SYSTEM&cursor="+cursor,admin,null),400);
        data(request("GET",RBAC+"/users/42/role-assignments?pageSize=1&cursor="+cursor,admin,null),400);
        data(request("GET",path+"?pageSize=1&cursor="+cursor+"x",admin,null),400);
        assignment(9,"ROOT","AUTHORIZATION");
        String another=new ObjectMapper().readTree(consume(confirmed(9),"RBAC_V1").body()).get("token").asText();
        data(request("GET",path+"?pageSize=1&cursor="+cursor,another,null),400);
        data(rbacWrite(path,admin,"cursor-http-grant-system",grantBody(43,"SYSTEM")),201);
        var stale=data(request("GET",path+"?pageSize=1&cursor="+cursor,admin,null),400);
        assertTrue(stale.toString().contains("CURSOR_STALE"));
        var fresh=data(request("GET",path+"?pageSize=1",admin,null),200);
        jdbc.update("UPDATE rbac_role SET lifecycle='DISABLED',grantable=0 WHERE code='NOTICE_MONITOR'");
        data(request("GET",path+"?pageSize=1&cursor="+fresh.get("nextCursor").asText(),admin,null),400);
    }
    @Test void rbacAuditHighWaterExcludesLaterCommitsAndOffsetFiltersKeepExactBoundaries() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments";
        var one=rbacWrite(path,admin,"audit-http-grant-0001",grantBody(43,"NOTIFICATION"));data(one,201);
        String detail=one.headers().firstValue("Location").orElseThrow();
        data(rbacWrite(detail+":revoke",admin,"audit-http-revoke-0001",revokeBody(admin,detail)),200);
        var first=data(request("GET",RBAC+"/changes?targetUserId=43&pageSize=1",admin,null),200);
        java.util.Set<String> seen=new java.util.HashSet<>();seen.add(first.get("items").get(0).get("changeId").asText());
        var later=data(rbacWrite(path,admin,"audit-http-later-0001",grantBody(43,"SYSTEM")),201);
        var next=data(request("GET",RBAC+"/changes?targetUserId=43&pageSize=1&cursor="+first.get("nextCursor").asText(),admin,null),200);
        seen.add(next.get("items").get(0).get("changeId").asText());assertEquals(2,seen.size());assertFalse(seen.contains(later.get("changeId").asText()));assertFalse(next.has("nextCursor"));
        String instant=later.get("committedAt").asText();
        String offset=java.time.OffsetDateTime.parse(instant).withOffsetSameInstant(java.time.ZoneOffset.ofHours(8)).toString();
        String boundary=java.net.URLEncoder.encode(offset,java.nio.charset.StandardCharsets.UTF_8);
        var after=data(request("GET",RBAC+"/changes?targetUserId=43&occurredAfter="+boundary,admin,null),200);
        assertEquals(1,after.get("items").size());
        var before=data(request("GET",RBAC+"/changes?targetUserId=43&occurredBefore="+boundary,admin,null),200);
        assertEquals(2,before.get("items").size());
        data(request("GET",RBAC+"/changes?occurredAfter=2099-01-01T00:00:00Z&occurredBefore=2000-01-01T00:00:00Z",admin,null),400);
        assertTrue(data(request("GET",RBAC+"/changes?actorUserId=opaque-unknown",admin,null),200).get("items").isEmpty());
    }
    @Test void rbacHttpConcurrentGrantAndRevokeCommitOnlyOneFactPerIntent() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments",body=grantBody(43,"NOTIFICATION");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->rbacWrite(path,admin,"concurrent-http-same-key",body));
            var b=pool.submit(()->rbacWrite(path,admin,"concurrent-http-same-key",body));
            var one=a.get(20,java.util.concurrent.TimeUnit.SECONDS);var two=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            data(one,201);data(two,201);assertEquals(one.body(),two.body());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change WHERE target_user_id=43",Integer.class));
            String detail=one.headers().firstValue("Location").orElseThrow(),revoke=revokeBody(admin,detail);
            var r1=pool.submit(()->rbacWrite(detail+":revoke",admin,"concurrent-revoke-key-1",revoke));
            var r2=pool.submit(()->rbacWrite(detail+":revoke",admin,"concurrent-revoke-key-2",revoke));
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(r1.get(20,java.util.concurrent.TimeUnit.SECONDS).statusCode(),r2.get(20,java.util.concurrent.TimeUnit.SECONDS).statusCode()));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_authorization_change WHERE target_user_id=43",Integer.class));
            String newBody=grantBody(43,"SYSTEM");
            var g1=pool.submit(()->rbacWrite(path,admin,"concurrent-grant-key-1",newBody));
            var g2=pool.submit(()->rbacWrite(path,admin,"concurrent-grant-key-2",newBody));
            assertEquals(java.util.Set.of(201,409),java.util.Set.of(g1.get(20,java.util.concurrent.TimeUnit.SECONDS).statusCode(),g2.get(20,java.util.concurrent.TimeUnit.SECONDS).statusCode()));
        } finally {pool.shutdownNow();}
    }
    @Test void rbacHttpTwoRootsCannotConcurrentlyRemoveEachOther() throws Exception {
        String first=rbacFixture();String secondId=assignment(9,"ROOT","AUTHORIZATION");
        String second=new ObjectMapper().readTree(consume(confirmed(9),"RBAC_V1").body()).get("token").asText();
        String firstId=jdbc.queryForObject("SELECT id FROM rbac_user_role_assignment WHERE user_id=42 AND role_code='ROOT'",String.class);
        String path1=RBAC+"/users/9/role-assignments/"+secondId,path2=RBAC+"/users/42/role-assignments/"+firstId;
        String body1=revokeBody(first,path1),body2=revokeBody(second,path2);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->rbacWrite(path1+":revoke",first,"root-http-revoke-0001",body1));
            var b=pool.submit(()->rbacWrite(path2+":revoke",second,"root-http-revoke-0002",body2));
            assertEquals(java.util.Set.of(200,403),java.util.Set.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS).statusCode(),b.get(20,java.util.concurrent.TimeUnit.SECONDS).statusCode()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rbac_user_role_assignment WHERE role_code='ROOT' AND revoked_at IS NULL",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void rbacCatalogReadinessExpiryAndStorageFailuresCloseTheFormalSurface() throws Exception {
        String admin=rbacFixture(),path=RBAC+"/users/43/role-assignments";
        jdbc.update("UPDATE rbac_permission_definition SET lifecycle='DISABLED',grantable=0 WHERE code='system:read'");
        var role=data(request("GET",RBAC+"/roles/NOTICE_MONITOR",admin,null),200);
        assertFalse(role.get("grantable").asBoolean());assertEquals(2,role.get("permissionCodes").size());
        data(rbacWrite(path,admin,"disabled-role-http-0001",grantBody(43,"NOTIFICATION")),409);
        enableRole("NOTICE_MONITOR");
        var granted=data(rbacWrite(path,admin,"expiry-fixture-http-0001",grantBody(43,"NOTIFICATION")),201);
        String readerAdmin=new ObjectMapper().readTree(consume(confirmed(43),"RBAC_V1").body()).get("token").asText();
        var live=data(request("GET",RBAC+"/me/capabilities",readerAdmin,null),200);
        assertEquals("notice:read",live.get("grants").get(0).get("permissionCodes").get(0).asText());
        assertEquals(1,live.get("grants").get(0).get("permissionCodes").size());
        jdbc.update("UPDATE rbac_user_role_assignment SET granted_at=UTC_TIMESTAMP(6)-INTERVAL 2 SECOND,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",granted.get("assignment").get("assignmentId").asText());
        assertFalse(data(request("GET",RBAC+"/me/capabilities",readerAdmin,null),200).get("canUseAdmin").asBoolean());
        faultContainer("p1.mysql.container",()->data(request("GET",RBAC+"/roles",admin,null),503));
        faultContainer("p1.redis.container",()->data(request("GET",RBAC+"/me/capabilities",admin,null),503));
        jdbc.update("UPDATE rbac_authorization_authority SET version=' ' WHERE user_id=42");
        data(request("GET",RBAC+"/roles",admin,null),503);
    }

    @RestController @lombok.RequiredArgsConstructor static class Probe {
        private final AdminTokenService tokens;
        @GetMapping({"/v1/p1-probe/read","/v1/admin/p1-probe"})
        Map<String,Boolean> read() {return Map.of("reached",true);}
        @GetMapping("/rbac-diagnostic-probe") Map<String,Boolean> diagnostic(){return Map.of("canUseAdmin",tokens.diagnoseCurrent().canUseAdmin());}
    }
    @Configuration @EnableAutoConfiguration @EnableTransactionManagement
    @MapperScan({"cn.jualn.miniapp.module.user.mapper","cn.jualn.miniapp.module.post.mapper","cn.jualn.miniapp.module.comment.mapper",
            "cn.jualn.miniapp.module.activity.mapper","cn.jualn.miniapp.module.publicevent.mapper","cn.jualn.miniapp.module.interact.mapper",
            "cn.jualn.miniapp.module.authorization","cn.jualn.miniapp.module.report.mapper","cn.jualn.miniapp.module.content.mapper",
            "cn.jualn.miniapp.module.audit.mapper","cn.jualn.miniapp.module.media.mapper"})
    @Import({cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,WebMvcConfig.class,cn.jualn.miniapp.web.ContextInterceptor.class,SaTokenConfig.class,RedisConfig.class,RedisService.class,
        AdminTokenService.class,AdminAuthStore.class,AdminQrLoginStore.class,AdminPermissionPolicy.class,UserServiceImpl.class,UserAccountService.class,UserWechatIdentityService.class,
        cn.jualn.miniapp.module.user.controller.admin.AdminUserController.class,
        cn.jualn.miniapp.module.user.converter.AdminUserConverter.class,
        cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class,cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader.class,
        cn.jualn.miniapp.module.authorization.web.RbacController.class,cn.jualn.miniapp.module.authorization.service.RbacQueryService.class,
        cn.jualn.miniapp.module.authorization.service.RbacCursorCodec.class,
        cn.jualn.miniapp.module.authorization.web.RbacHttpConverter.class,cn.jualn.miniapp.module.authorization.web.RbacExceptionHandler.class,
        cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class,cn.jualn.miniapp.module.authorization.service.RbacGovernanceService.class,
        cn.jualn.miniapp.config.SaPermissionProvider.class,cn.jualn.miniapp.module.admin.auth.service.impl.AdminQrLoginServiceImpl.class,
        cn.jualn.miniapp.module.admin.auth.controller.AdminQrLoginController.class,cn.jualn.miniapp.module.admin.auth.converter.AdminQrLoginConverter.class,
        cn.jualn.miniapp.module.admin.auth.controller.AdminAuthController.class,
        cn.jualn.miniapp.module.post.service.impl.PostServiceImpl.class,cn.jualn.miniapp.module.post.service.PostOrganizationService.class,
        cn.jualn.miniapp.module.post.service.PostDictionaryAuthorization.class,cn.jualn.miniapp.module.post.service.PostCursorCodec.class,
        cn.jualn.miniapp.module.post.service.PostRepresentationTags.class,
        cn.jualn.miniapp.module.post.controller.PostController.class,
        cn.jualn.miniapp.module.report.controller.ReportController.class,cn.jualn.miniapp.module.report.service.impl.ReportServiceImpl.class,
        cn.jualn.miniapp.module.report.controller.admin.AdminReportCaseController.class,cn.jualn.miniapp.module.report.converter.AdminReportCaseConverter.class,
        cn.jualn.miniapp.module.report.service.impl.AdminReportCaseServiceImpl.class,cn.jualn.miniapp.module.content.service.impl.AdminContentServiceImpl.class,
        cn.jualn.miniapp.module.post.service.PostOrganizationService.class,cn.jualn.miniapp.module.post.service.PostDictionaryAuthorization.class,
        cn.jualn.miniapp.module.post.service.PostRepresentationTags.class,cn.jualn.miniapp.module.post.service.PostCursorCodec.class,
        cn.jualn.miniapp.module.content.controller.admin.AdminContentController.class,cn.jualn.miniapp.module.content.converter.AdminContentConverter.class,
        cn.jualn.miniapp.module.admin.auth.controller.AdminQrLoginCacheFilter.class,
        cn.jualn.miniapp.module.audit.controller.admin.AdminReviewController.class,
        cn.jualn.miniapp.module.audit.converter.AdminReviewConverter.class,cn.jualn.miniapp.module.audit.service.impl.AdminReviewServiceImpl.class,
        cn.jualn.miniapp.module.interact.service.InteractionLikes.class,cn.jualn.miniapp.module.interact.service.InteractionLikeQueries.class,cn.jualn.miniapp.module.interact.service.InteractionCounts.class,cn.jualn.miniapp.module.interact.service.InteractionShares.class,cn.jualn.miniapp.module.interact.service.InteractionViews.class,cn.jualn.miniapp.module.interact.service.impl.InteractionAccess.class,cn.jualn.miniapp.module.interact.service.impl.LikeNotifications.class,cn.jualn.miniapp.module.interact.service.impl.LikeCache.class,CommentTargetAccess.class,ActivityPublicAccess.class,PublicEventPublicAccess.class,PostPublicAccess.class,
        cn.jualn.miniapp.module.post.service.PostQueries.class,cn.jualn.miniapp.module.post.service.PostGovernanceService.class,
        cn.jualn.miniapp.module.comment.service.CommentQueries.class,cn.jualn.miniapp.module.comment.service.CommentGovernanceService.class,
        cn.jualn.miniapp.module.comment.service.CommentCounters.class,cn.jualn.miniapp.module.post.service.PostInteractionSubject.class,
        CommentServiceImpl.class,PostQaCommentPolicy.class,PostCommentPermissions.class,UnavailableMentorQualification.class,
        AuthServiceImpl.class,AuthController.class,cn.jualn.miniapp.module.auth.service.LoginAttemptLimiter.class,InteractController.class,CommentController.class,GlobalExceptionHandler.class,Probe.class,
        cn.jualn.miniapp.module.activity.controller.CanonicalActivityController.class,cn.jualn.miniapp.module.activity.controller.CanonicalActivityRegistrationController.class})
    static class Config {
        @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> postCommitUnknown() {
            var filter=new org.springframework.web.filter.OncePerRequestFilter() {
                @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,
                        jakarta.servlet.FilterChain chain) throws java.io.IOException,jakarta.servlet.ServletException {
                    if(!"true".equals(request.getHeader("X-P1-Post-Commit-Unknown"))) {chain.doFilter(request,response);return;}
                    var cached=new org.springframework.web.util.ContentCachingResponseWrapper(response);chain.doFilter(request,cached);
                    assertEquals(201,cached.getStatus()); // Governance commit and successful controller return already occurred.
                    response.reset();response.setStatus(503);response.setHeader("Cache-Control","no-store");response.setContentType("application/problem+json");
                    response.getWriter().write("{\"type\":\"/problems/authorization-source-unavailable\",\"title\":\"Synthetic post-commit response loss\",\"status\":503}");
                }
            };
            return new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        }
        @Bean cn.jualn.miniapp.module.authorization.service.RbacCatalog catalog(){return cn.jualn.miniapp.module.authorization.service.RbacIntegrationCatalog.create();}
        @Bean cn.jualn.miniapp.module.admin.auth.config.AdminQrLoginProperties qrProperties(){return new cn.jualn.miniapp.module.admin.auth.config.AdminQrLoginProperties();}
        @Bean cn.jualn.miniapp.module.post.converter.PostConverter postConverter(){return Mappers.getMapper(cn.jualn.miniapp.module.post.converter.PostConverter.class);}
        @Bean cn.jualn.miniapp.module.report.converter.ReportConverter reportConverter(){return Mappers.getMapper(cn.jualn.miniapp.module.report.converter.ReportConverter.class);}
        @Bean cn.jualn.miniapp.module.post.service.PostEditingService editing(){return mock(cn.jualn.miniapp.module.post.service.PostEditingService.class);}
        @Bean DataSource source() {return new DriverManagerDataSource(connectionUrl(url),"root","");}
        @Bean DataSourceTransactionManager transactions(DataSource source) {return new DataSourceTransactionManager(source);}
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
            var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();global.setMetaObjectHandler(new MybatisPlusConfig().metaObjectHandler());
            global.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());global.getDbConfig().setLogicNotDeleteValue("NULL");global.getDbConfig().setLogicDeleteValue("NOW()");factory.setGlobalConfig(global);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));return factory.getObject();
        }
        @Bean ObjectMapper json() {return new JacksonConfig().objectMapper(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder());}
        @Bean AdminAuthProperties properties() {return new AdminAuthProperties();}
        @Bean io.micrometer.core.instrument.MeterRegistry meters() {return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();}
        @Bean UserConverter userConverter() {return Mappers.getMapper(UserConverter.class);}
        @Bean CommentConverter commentConverter() {return Mappers.getMapper(CommentConverter.class);}
        @Bean cn.jualn.miniapp.common.mapper.EnumConverter enums() {return new cn.jualn.miniapp.common.mapper.EnumConverter();}
        @Bean WxClient wx() {var wx=mock(WxClient.class,RETURNS_DEEP_STUBS);when(wx.getMiniSession(anyString()).getOpenid()).thenReturn("p1-user42");return wx;}
        @Bean cn.jualn.miniapp.module.audit.service.ProfileSafetyCheckService profileSafety() {return mock(cn.jualn.miniapp.module.audit.service.ProfileSafetyCheckService.class);}
        @Bean cn.jualn.miniapp.module.media.TestMediaService media() {return mock(cn.jualn.miniapp.module.media.TestMediaService.class);}
        @Bean cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService logs() {return mock(cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService.class);}
        @Bean cn.jualn.miniapp.module.subject.service.SubjectExistenceService validator() {return mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class);}
        @Bean cn.jualn.miniapp.module.notify.service.NotifyService notifications() {return mock(cn.jualn.miniapp.module.notify.service.NotifyService.class);}
        @Bean cn.jualn.miniapp.module.post.service.PostNotificationFactsService postFacts() {return id->null;}
        @Bean CommentNotificationFactsService commentFacts() {return id->null;}
        @Bean cn.jualn.miniapp.module.article.service.ArticleService articles() {return mock(cn.jualn.miniapp.module.article.service.ArticleService.class);}
        @Bean ArticleCommentNotifications articleNotifications() {return mock(ArticleCommentNotifications.class);}
        @Bean CanonicalCommentService canonicalComments() {return mock(CanonicalCommentService.class);}
        @Bean cn.jualn.miniapp.module.activity.converter.ActivityResourceConverter activityConverter(ObjectMapper json) {return new cn.jualn.miniapp.module.activity.converter.ActivityResourceConverter(json);}
        @Bean cn.jualn.miniapp.module.activity.converter.PersonalRegistrationConverter registrationConverter() {return new cn.jualn.miniapp.module.activity.converter.PersonalRegistrationConverter();}
        @Bean ActivityRegistrationService registrations() {return mock(ActivityRegistrationService.class);}
        @Bean ActivityEnrollmentService subscriptions() {var s=mock(ActivityEnrollmentService.class);when(s.subscribeWithState(anyLong())).thenReturn(new cn.jualn.miniapp.module.activity.bo.ActivitySubscriptionBO(true,java.time.LocalDateTime.now()));return s;}
        @Bean cn.jualn.miniapp.module.activity.service.ActivityReviewService activityReviews() { return mock(cn.jualn.miniapp.module.activity.service.ActivityReviewService.class); }
        @Bean ActivityInteractionSubject activities() {return mock(ActivityInteractionSubject.class);}
        @Bean PublicEventInteractionSubject events() {return mock(PublicEventInteractionSubject.class);}
        @Bean cn.jualn.miniapp.module.audit.service.AuditReservationService audit() {
            var audit=mock(cn.jualn.miniapp.module.audit.service.AuditReservationService.class);
            when(audit.reserveAuditLogs(any())).thenReturn(cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO.builder().textAuditLogId(1L).build());return audit;
        }
    }
}
