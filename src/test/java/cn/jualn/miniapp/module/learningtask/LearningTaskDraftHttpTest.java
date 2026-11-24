package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.mapper.UnifiedMapper;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.article.mapper.ArticleMapper;
import cn.jualn.miniapp.module.article.service.ArticleReferenceReadService;
import cn.jualn.miniapp.module.article.service.impl.ArticleReferenceReadServiceImpl;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements;
import cn.jualn.miniapp.module.learningtask.controller.LearningTaskController;
import cn.jualn.miniapp.module.learningtask.controller.LearningTaskDraftController;
import cn.jualn.miniapp.module.learningtask.controller.LearningTaskProblemAdvice;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskEntryGate;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders;
import cn.jualn.miniapp.module.learningtask.service.UnavailableLearningTaskProviders;
import cn.jualn.miniapp.module.learningtask.service.impl.LearningTaskServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.missing;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback Tomcat + real HTTP/JWT + MySQL. Production session revocation/QR activation is NOT
 * simulated as ready: only test-source grants and deterministic safety permit draft success.
 * All other provider operations delegate to the real rejecting production provider.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/learning_task_test")
class LearningTaskDraftHttpTest {
    AnnotationConfigServletWebServerApplicationContext context;
    JdbcTemplate db;
    ControlledDraftProviders provider;
    ObjectMapper json;
    String base;
    String admin;
    String reader;
    String user;
    SaTokenConfig previousConfig;
    SaTokenContext previousContext;
    StpLogic previousUserLogic;
    SaTokenDao previousDao;
    final HttpClient client=HttpClient.newHttpClient();
    final List<Map<String,Object>> fixtures=new ArrayList<>();
    final List<Map<String,Object>> verticalExchanges=new ArrayList<>();

    @BeforeAll void startServer() {
        previousConfig=SaManager.getConfig(); previousContext=SaManager.getSaTokenContext(); previousUserLogic=StpUtil.getStpLogic();
        previousDao=SaManager.getSaTokenDao();
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization").setTokenPrefix("Bearer")
                .setIsReadCookie(false).setJwtSecretKey("learning-task-loopback-test-only-signing-secret-2026"));
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        StpUtil.setStpLogic(new StpLogicJwtForSimple());
        context=new AnnotationConfigServletWebServerApplicationContext(Config.class);
        base="http://127.0.0.1:"+context.getWebServer().getPort();
        db=context.getBean(JdbcTemplate.class); provider=context.getBean(ControlledDraftProviders.class);
        json=context.getBean(ObjectMapper.class);
        admin=session(AdminStpUtil.STP_LOGIC,9L);
        reader=session(AdminStpUtil.STP_LOGIC,10L);
        user=session(StpUtil.getStpLogic(),42L);
    }
    @BeforeEach void reset() {
        ((MutableHttpClock)context.getBean("learningTaskClock")).value=Instant.parse("2026-10-14T03:22:33.123456Z");
        provider.productionDenial=false; provider.safetyUnavailable=false; provider.revoked=false; provider.formal=null; provider.integrated=null;
        for(String table:List.of("person_business_scope","rbac_delegation_authority","rbac_duty_preview","rbac_duty_cursor","rbac_unified_command"))db.update("DELETE FROM "+table);
        db.update("DELETE FROM rbac_authorization_change");
        db.update("DELETE FROM rbac_user_role_assignment WHERE user_id IN (9,10)");
        db.update("DELETE FROM async_job WHERE job_type='learning-task.reminder'");
        db.update("UPDATE learning_task_assignment SET latest_submission_id=NULL");
        for(String table:List.of("learning_task_request","learning_task_audit","learning_task_preview","learning_task_submission","learning_task_assignment","learning_task")) {
            db.update("DELETE FROM "+table);
        }
        db.update("INSERT INTO user_profile(id,openid,role,status) VALUES(9,'lt-http-admin',3,1),(10,'lt-http-reader',2,1),(42,'learning-user42',1,1) ON DUPLICATE KEY UPDATE role=VALUES(role),status=1");
    }
    @AfterAll void stopServer() throws Exception {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/learning-task-b2-http-responses.json"),json.writeValueAsString(fixtures));
            Files.writeString(Path.of("target/learning-task-vertical-exchanges.json"),json.writeValueAsString(verticalExchanges));
        } finally {
            if(context!=null) { context.close(); }
            SaManager.setConfig(previousConfig); SaManager.setSaTokenContext(previousContext); StpUtil.setStpLogic(previousUserLogic);
            SaManager.setSaTokenDao(previousDao);
        }
    }
    String key() { return UUID.randomUUID().toString(); }
    String session(StpLogic logic,long id) {
        String token=logic.createTokenValue(id,"test",3600,Map.of());
        return logic.createLoginSession(id,new SaLoginParameter().setToken(token).setTimeout(3600)
                .setIsConcurrent(true).setIsShare(false).setIsWriteHeader(false).setIsLastingCookie(false));
    }
    String body() {
        return """
                {"title":"HTTP专题学习","description":"草稿说明",
                 "requirements":{"confirmationRequired":true,"minimumTextLength":0,"minimumFiles":0,"maximumFiles":0,"allowedFileKinds":[]},
                 "reviewRequired":false,"firstDeadline":"2026-10-30T10:00:00.123456Z",
                 "managementTarget":{"type":"PLATFORM"},"audience":{"userIds":["opaque-user-A"],"filters":[{"organizationIds":["org-A"],"identityIds":["identity-A"]}],"authorizedScopeId":"scope-A"}}
                """;
    }
    HttpRequest request(String method,String path,String body,String token,Map<String,String> headers) {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type","application/json").header("Accept","application/json");
        if(token!=null) { builder.header("Authorization","Bearer "+token); }
        headers.forEach(builder::header);
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> call(String method,String path,String body,String token,Map<String,String> headers) throws Exception {
        var response=client.send(request(method,path,body,token,headers),HttpResponse.BodyHandlers.ofString());
        if(provider.integrated!=null) {
            var entry=new java.util.LinkedHashMap<String,Object>(); entry.put("method",method); entry.put("path",path);
            entry.put("requestBody",body); entry.put("requestHeaders",headers);
            entry.put("tokenSha256",token==null?null:java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            entry.put("status",response.statusCode()); entry.put("responseBody",tree(response));
            entry.put("etag",response.headers().firstValue("ETag").orElse(null)); entry.put("location",response.headers().firstValue("Location").orElse(null));
            verticalExchanges.add(entry);
        }
        return response;
    }
    HttpResponse<String> create(String body,String key) throws Exception {
        var response=call("POST","/v1/admin/learning-tasks",body,admin,Map.of("Idempotency-Key",key));
        return response;
    }
    JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    String etag(HttpResponse<String> response) { return response.headers().firstValue("ETag").orElseThrow(); }
    String location(HttpResponse<String> response) { return response.headers().firstValue("Location").orElseThrow(); }
    void status(int expected,HttpResponse<String> response) { assertEquals(expected,response.statusCode(),response.body()); }
    void record(String schema,HttpResponse<String> response) throws Exception {
        fixtures.add(Map.of("schema",schema,"status",response.statusCode(),"body",tree(response)));
    }
    @Test void formalOwningPolicyOverRealHttpWithIsolatedGrantsAndSafety() throws Exception {
        var authorization=org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class);
        var catalog=org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.RbacCatalog.class);
        org.mockito.Mockito.when(catalog.supports(org.mockito.ArgumentMatchers.eq("LEARNING_TASK"),org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var decision=new cn.jualn.miniapp.module.authorization.model.RbacModels.Decision(9,
                cn.jualn.miniapp.module.authorization.model.RbacModels.Source.ASSIGNMENT,"fixture","fixture",LocalDateTime.now(),true,List.of(),
                List.of(new cn.jualn.miniapp.module.authorization.model.RbacModels.Grant("fixture","fixture","LEARNING_TASK","GLOBAL",
                        List.of("learning-task:read","learning-task:edit"),null)));
        org.mockito.Mockito.when(authorization.current(9)).thenReturn(decision);
        provider.formal=new cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy(authorization,catalog,
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserAccountService.class),
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskScopePolicy.class));
        var scopeDouble=org.mockito.Mockito.mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskScopePolicy.class);
        org.mockito.Mockito.when(scopeDouble.decision(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(i->authorization.current(i.getArgument(0)));
        org.springframework.test.util.ReflectionTestUtils.setField(provider.formal,"scopes",scopeDouble);
        String key=key();
        var created=create(body(),key); status(201,created);
        String path=location(created);
        // GLOBAL is the proven LT namespace scope, not a creator-only restriction.
        db.update("UPDATE learning_task SET created_by=10 WHERE id=?",tree(created).path("taskId").asLong());
        status(200,call("GET",path,null,admin,Map.of()));
        var page=call("GET","/v1/admin/learning-tasks",null,admin,Map.of()); status(200,page);
        assertEquals(1,tree(page).path("items").size());
        status(200,call("PATCH",path,"{\"title\":\"formal policy\"}",admin,Map.of("If-Match",etag(created))));
        var replay=create(body(),key); status(201,replay); assertEquals(created.body(),replay.body());
        status(401,call("GET",path,null,user,Map.of()));
        org.mockito.Mockito.when(authorization.current(9)).thenReturn(new cn.jualn.miniapp.module.authorization.model.RbacModels.Decision(9,
                decision.source(),"revoked","fixture",LocalDateTime.now(),true,List.of(),List.of()));
        var denied=create(body(),key); status(403,denied);
        assertEquals("no-store",denied.headers().firstValue("Cache-Control").orElseThrow());
        org.mockito.Mockito.when(authorization.current(9)).thenThrow(new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/authorization-source-unavailable","unavailable"));
        var failed=call("GET",path,null,admin,Map.of()); status(503,failed);
        assertEquals("AUTHORIZATION",tree(failed).path("dependency").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_task",Integer.class));
    }
    @Test void productionPortsExecuteTwoUserConfirmationSliceWithRealReaderDatabaseAndInbox() throws Exception {
        assertEquals("2026-10-17T08:00:00+08:00",context.getBean(cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter.class)
                .time(LocalDateTime.of(2026,10,17,8,0)));
        verticalExchanges.clear();
        db.update("DELETE FROM rbac_user_role_assignment WHERE user_id=10");
        db.update("INSERT INTO user_profile(id,openid,role,status) VALUES(43,'lt-second-user',1,1),(44,'lt-outsider',1,1) ON DUPLICATE KEY UPDATE status=1,deleted_at=NULL");
        for(long id:List.of(9L,10L,42L,43L,44L)) {
            db.update("INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at) VALUES(?,'ASSIGNMENT',UUID(),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE source='ASSIGNMENT',version=UUID()",id);
        }
        db.update("UPDATE rbac_permission_definition p JOIN rbac_role_permission m ON p.code=m.permission_code SET p.lifecycle='ENABLED',p.readiness='READY',p.grantable=1,p.unavailable_reason=NULL WHERE m.role_code='LEARNING_MANAGER'");
        db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='LEARNING_MANAGER'");
        db.update("DELETE FROM rbac_user_role_assignment WHERE user_id=9 AND role_code='LEARNING_MANAGER'");
        String grant=boundLearningDuty(9,"LEARNING_MANAGE");
        provider.integrated=context.getBean(UnavailableLearningTaskProviders.class);
        var wx=context.getBean(cn.jualn.miniapp.third.wx.client.WxClient.class);
        org.mockito.Mockito.when(wx.msgSecCheck(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var response=new cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse(); response.setErrcode(0);
            var result=new cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse.ResultInfo(); result.setSuggest("pass"); response.setResult(result); response.setVerifiedPassStructure(true); return response;
        });
        String command="""
                {"title":"真实代码纵向学习","description":"请阅读本说明后主动确认完成。","requirements":{"confirmationRequired":true,"minimumTextLength":0,"minimumFiles":0,"maximumFiles":0,"allowedFileKinds":[]},"reviewRequired":false,"firstDeadline":"2026-10-30T18:00:00.123456+08:00","managementTarget":{"type":"PLATFORM"},"audience":{"userIds":["42","43"]}}
                """;
        command=command.replace("2026-10-30T18:00:00.123456+08:00",Instant.now().plusSeconds(86400-60).truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC).toString());
        var created=create(command,key()); status(201,created); record("LearningTaskAdminDetail",created);
        String taskPath=location(created); String task=tree(created).path("taskId").asText();
        var edited=call("PATCH",taskPath,"{\"description\":\"已确认的学习说明\"}",admin,Map.of("If-Match",etag(created))); status(200,edited);
        var adminDetail=call("GET",taskPath,null,admin,Map.of()); status(200,adminDetail); record("LearningTaskAdminDetail",adminDetail);
        var adminPage=call("GET","/v1/admin/learning-tasks",null,admin,Map.of()); status(200,adminPage); record("LearningTaskAdminPage",adminPage);
        String previewBody="{\"purpose\":\"PUBLISH\",\"audience\":{\"userIds\":[\"42\",\"43\"]}}";
        var preview=call("POST",taskPath+"/audience-previews",previewBody,admin,Map.of("If-Match",etag(edited),"Idempotency-Key",key()));
        status(201,preview); record("LearningTaskAudiencePreview",preview); assertEquals(2,tree(preview).path("resolvedCount").asInt());
        String previewPath=taskPath+"/audience-previews/"+tree(preview).path("previewId").asText();
        var summary=call("GET",previewPath,null,admin,Map.of()); status(200,summary); assertEquals(preview.body(),summary.body());
        var members=call("GET",previewPath+"/members",null,admin,Map.of()); status(200,members); record("LearningTaskPreviewMemberPage",members);
        assertFalse(tree(members).path("items").get(0).path("alreadyAssigned").asBoolean());
        String publishBody="{\"previewId\":\""+tree(preview).path("previewId").asText()+"\"}";
        String publishKey=key();
        var published=call("POST",taskPath+":publish",publishBody,admin,Map.of("If-Match",etag(edited),"Idempotency-Key",publishKey));
        status(200,published); record("LearningTaskAdminDetail",published);
        var publishReplay=call("POST",taskPath+":publish",publishBody,admin,Map.of("If-Match",etag(edited),"Idempotency-Key",publishKey));
        status(200,publishReplay); assertEquals(published.body(),publishReplay.body());
        assertEquals(preview.body(),call("GET",previewPath,null,admin,Map.of()).body());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM learning_task_assignment WHERE task_id=?",Integer.class,task));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM notification WHERE type=24 AND JSON_UNQUOTE(JSON_EXTRACT(content_payload,'$.subject.resourceId'))=? AND inbox_seq IS NOT NULL",Integer.class,task));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type='learning-task.reminder' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.taskId'))=?",Integer.class,task));
        var candidatePayloads=db.queryForList("SELECT payload FROM async_job WHERE job_type='learning-task.reminder' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.taskId'))=? ORDER BY id",String.class,task);
        var firstCandidate=json.readValue(candidatePayloads.get(0),cn.jualn.miniapp.module.learningtask.service.LearningTaskNotifications.Reminder.class);
        ((MutableHttpClock)context.getBean("learningTaskClock")).value=Instant.now();
        var reminderOwner=context.getBean(cn.jualn.miniapp.module.learningtask.service.LearningTaskNotifications.class);
        var queued=new ArrayList<Runnable>();
        var registry=new cn.jualn.miniapp.infrastructure.async.job.JobHandlerRegistry(json,List.of(context.getBean(cn.jualn.miniapp.module.learningtask.service.LearningTaskReminderHandler.class)));
        var worker=new cn.jualn.miniapp.infrastructure.async.job.JobWorker(context.getBean(cn.jualn.miniapp.infrastructure.async.job.JobClaimService.class),
                context.getBean(cn.jualn.miniapp.infrastructure.async.job.AsyncJobMapper.class),registry,new cn.jualn.miniapp.infrastructure.async.job.JobFailureClassifier(),
                new cn.jualn.miniapp.infrastructure.async.job.RetrySchedule(),queued::add,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        worker.poll(); assertEquals(2,queued.size()); queued.get(0).run();
        reminderOwner.deliver(firstCandidate);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification WHERE type=27 AND user_id=42 AND JSON_UNQUOTE(JSON_EXTRACT(content_payload,'$.subject.resourceId'))=?",Integer.class,task));
        String second=session(StpUtil.getStpLogic(),43L); String outsider=session(StpUtil.getStpLogic(),44L);
        var inbox=call("GET","/v1/users/me/notifications?representation=structured&boxCategory=SYSTEM",null,user,Map.of());
        status(200,inbox); record("NotificationListResponse",inbox);
        assertTrue(tree(inbox).path("items").size()>=2);
        assertTrue(tree(inbox).path("items").get(0).path("createdAt").asText()
                .matches(".*T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?[+]08:00"));
        assertEquals(task,tree(inbox).path("items").get(0).path("target").path("taskId").asText());
        String notification=tree(inbox).path("items").get(0).path("id").asText();
        var notificationDetail=call("GET","/v1/users/me/notifications/"+notification,null,user,Map.of()); status(200,notificationDetail); record("NotificationItem",notificationDetail);
        status(404,call("GET","/v1/users/me/notifications/"+notification,null,second,Map.of()));
        var readNotification=call("POST","/v1/users/me/notifications:batch-read","{\"notificationIds\":[\""+notification+"\"]}",user,Map.of());
        status(200,readNotification); record("NotificationReadResult",readNotification); assertEquals(1,tree(readNotification).path("changedCount").asInt());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification WHERE id=? AND user_id=42 AND read_at IS NOT NULL",Integer.class,notification));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification WHERE id=? AND read_at>=created_at",Integer.class,notification));
        String myPath="/v1/users/me/learning-tasks/"+task;
        var page=call("GET","/v1/users/me/learning-tasks",null,user,Map.of()); status(200,page); record("LearningTaskMyPage",page);
        status(404,call("GET",myPath,null,outsider,Map.of()));
        status(401,call("GET",taskPath,null,user,Map.of()));
        var mine=call("GET",myPath,null,user,Map.of()); status(200,mine); record("LearningTaskMyDetail",mine);
        status(400,call("POST",myPath+":confirm","{\"confirmed\":true,\"completedAt\":\"2026-10-15T10:00:00Z\"}",user,Map.of("If-Match",etag(mine),"Idempotency-Key",key())));
        String confirmKey=key();
        var completed=call("POST",myPath+":confirm","{\"confirmed\":true}",user,Map.of("If-Match",etag(mine),"Idempotency-Key",confirmKey));
        status(200,completed); record("LearningTaskMyDetail",completed);
        var replay=call("POST",myPath+":confirm","{\"confirmed\":true}",user,Map.of("If-Match",etag(mine),"Idempotency-Key",confirmKey));
        status(200,replay); assertEquals(completed.body(),replay.body()); assertEquals(etag(completed),etag(replay));
        var other=call("GET",myPath,null,second,Map.of()); status(200,other);
        String secondKey=key();
        var one=client.sendAsync(request("POST",myPath+":confirm","{\"confirmed\":true}",second,Map.of("If-Match",etag(other),"Idempotency-Key",secondKey)),HttpResponse.BodyHandlers.ofString());
        var two=client.sendAsync(request("POST",myPath+":confirm","{\"confirmed\":true}",second,Map.of("If-Match",etag(other),"Idempotency-Key",secondKey)),HttpResponse.BodyHandlers.ofString());
        var firstResult=one.get(15,TimeUnit.SECONDS); var secondResult=two.get(15,TimeUnit.SECONDS);
        status(200,firstResult); status(200,secondResult); assertEquals(firstResult.body(),secondResult.body());
        verticalExchanges.add(Map.of("concurrent",true,"method","POST","path",myPath+":confirm","actorUserId","43","statuses",List.of(firstResult.statusCode(),secondResult.statusCode()),"responseBody",tree(firstResult)));
        queued.get(1).run();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification WHERE type=27 AND user_id=43 AND JSON_UNQUOTE(JSON_EXTRACT(content_payload,'$.subject.resourceId'))=?",Integer.class,task));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type='learning-task.reminder' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.taskId'))=? AND status='SUCCEEDED' AND attempt=1",Integer.class,task));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM learning_task_assignment WHERE task_id=? AND progress='COMPLETED' AND completed_at=confirmed_at",Integer.class,task));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM learning_task_submission WHERE task_id=?",Integer.class,task));
        var assignments=call("GET",taskPath+"/assignments",null,admin,Map.of()); status(200,assignments); record("LearningTaskAssignmentPage",assignments);
        var statistics=call("GET",taskPath+"/statistics",null,admin,Map.of()); status(200,statistics); record("LearningTaskStatistics",statistics);
        assertEquals(2,tree(statistics).path("completed").asInt()); assertEquals(1.0,tree(statistics).path("completionRate").asDouble());
        String ownId=tree(completed).path("assignment").path("assignmentId").asText();
        var adminAssignment=call("GET",taskPath+"/assignments/"+ownId,null,admin,Map.of()); status(200,adminAssignment);
        status(409,call("POST",taskPath+"/assignments/"+ownId+":revoke","{\"reason\":\"普通撤销不能删除完成\"}",admin,Map.of("If-Match",etag(adminAssignment),"Idempotency-Key",key())));
        status(403,call("POST",taskPath+"/assignments/"+ownId+":revoke","{\"reason\":\"例外另权\",\"allowParticipated\":true}",admin,Map.of("If-Match",etag(adminAssignment),"Idempotency-Key",key())));
        status(403,call("GET",taskPath,null,reader,Map.of()));
        var unavailableSafety=new cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse(); unavailableSafety.setErrcode(50001);
        org.mockito.Mockito.when(wx.msgSecCheck(org.mockito.ArgumentMatchers.any())).thenReturn(unavailableSafety);
        var safetyFailure=create(command,key()); status(503,safetyFailure); assertEquals("CONTENT_SAFETY",tree(safetyFailure).path("dependency").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_task",Integer.class));
        var facts=new java.util.LinkedHashMap<String,Object>();
        facts.put("evidenceScope","Loopback HTTP/MySQL; synthetic RBAC catalog/grant; real production LT/User-recipient/Reader/Inbox/Job services; Wx and UserAccountService façade/cache doubles; Sa sessions are not QR activation");
        facts.put("jdbc",System.getProperty("event.test.jdbcUrl")); facts.put("taskId",task);
        facts.put("authorizationBeforeRevocation",context.getBean(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class).current(9));
        facts.put("accounts",db.queryForList("SELECT id,status,deleted_at FROM user_profile WHERE id IN(9,42,43,44) ORDER BY id"));
        facts.put("task",db.queryForList("SELECT id,status,created_by,DATE_FORMAT(published_at,'%Y-%m-%d %H:%i:%s.%f') published_at FROM learning_task WHERE id=?",task));
        facts.put("assignments",db.queryForList("SELECT id,task_id,user_id,allocation_status,progress,DATE_FORMAT(confirmed_at,'%Y-%m-%d %H:%i:%s.%f') confirmed_at,DATE_FORMAT(completed_at,'%Y-%m-%d %H:%i:%s.%f') completed_at FROM learning_task_assignment WHERE task_id=? ORDER BY user_id",task));
        facts.put("notifications",db.queryForList("SELECT id,user_id,type,source_key,inbox_seq,DATE_FORMAT(read_at,'%Y-%m-%d %H:%i:%s.%f') read_at,content_payload FROM notification WHERE JSON_UNQUOTE(JSON_EXTRACT(content_payload,'$.subject.resourceId'))=? ORDER BY id",task));
        facts.put("jobs",db.queryForList("SELECT id,job_type,dedupe_key,status,payload FROM async_job WHERE job_type='learning-task.reminder' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.taskId'))=? ORDER BY id",task));
        Files.writeString(Path.of("target/learning-task-vertical-facts.json"),json.writeValueAsString(facts));
        var passing=new cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse(); passing.setErrcode(0);
        var passingResult=new cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse.ResultInfo(); passingResult.setSuggest("pass"); passing.setResult(passingResult);
        passing.setVerifiedPassStructure(true);
        org.mockito.Mockito.when(wx.msgSecCheck(org.mockito.ArgumentMatchers.any())).thenReturn(passing);
        verifyNotificationRollback(command);
        verifyManagerRemainder(command);
        db.update("UPDATE rbac_user_role_assignment SET revoked_at=UTC_TIMESTAMP(6),current_slot=NULL,version=UUID(),revoke_change_id=UUID() WHERE id=?",grant);
        status(403,call("GET",taskPath,null,admin,Map.of()));
        // Same production provider remains in place: current primary-DB revocation blocks command replay.
        status(403,create(command,key()));
        db.update("DELETE FROM rbac_authorization_authority WHERE user_id=9");
        var authorizationFailure=call("GET",taskPath,null,admin,Map.of()); status(503,authorizationFailure);
        assertEquals("AUTHORIZATION",tree(authorizationFailure).path("dependency").asText());
    }
    private void verifyNotificationRollback(String command) throws Exception {
        var draft=create(command,key()); status(201,draft); String path=location(draft); String task=tree(draft).path("taskId").asText();
        var preview=call("POST",path+"/audience-previews","{\"purpose\":\"PUBLISH\",\"audience\":{\"userIds\":[\"42\",\"43\"]}}",admin,Map.of("If-Match",etag(draft),"Idempotency-Key",key())); status(201,preview);
        String body="{\"previewId\":\""+tree(preview).path("previewId").asText()+"\"}"; String key=key();
        db.execute("CREATE TRIGGER lt_test_notification_failure BEFORE INSERT ON notification FOR EACH ROW BEGIN IF NEW.type=24 AND NEW.user_id=43 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated acceptance failure'; END IF; END");
        try {
            var failed=call("POST",path+":publish",body,admin,Map.of("If-Match",etag(draft),"Idempotency-Key",key)); status(503,failed);
            assertEquals("NOTIFICATION_ACCEPTANCE",tree(failed).path("dependency").asText());
        } finally { db.execute("DROP TRIGGER lt_test_notification_failure"); }
        assertEquals("DRAFT",db.queryForObject("SELECT status FROM learning_task WHERE id=?",String.class,task));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM learning_task_assignment WHERE task_id=?",Integer.class,task));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification WHERE JSON_UNQUOTE(JSON_EXTRACT(content_payload,'$.subject.resourceId'))=?",Integer.class,task));
        var recovered=call("POST",path+":publish",body,admin,Map.of("If-Match",etag(draft),"Idempotency-Key",key)); status(200,recovered);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM learning_task_assignment WHERE task_id=?",Integer.class,task));
    }
    private void verifyManagerRemainder(String command) throws Exception {
        var draft=create(command.replace("[\"42\",\"43\"]","[\"42\"]"),key()); status(201,draft);
        String path=location(draft);
        var preview=call("POST",path+"/audience-previews","{\"purpose\":\"PUBLISH\",\"audience\":{\"userIds\":[\"42\"]}}",admin,Map.of("If-Match",etag(draft),"Idempotency-Key",key())); status(201,preview);
        var published=call("POST",path+":publish","{\"previewId\":\""+tree(preview).path("previewId").asText()+"\"}",admin,Map.of("If-Match",etag(draft),"Idempotency-Key",key())); status(200,published);
        var addPreview=call("POST",path+"/audience-previews","{\"purpose\":\"ADD\",\"audience\":{\"userIds\":[\"42\",\"43\"]}}",admin,Map.of("If-Match",etag(published),"Idempotency-Key",key())); status(201,addPreview);
        var memberPage=call("GET",path+"/audience-previews/"+tree(addPreview).path("previewId").asText()+"/members",null,admin,Map.of()); status(200,memberPage);
        assertTrue(tree(memberPage).path("items").get(0).path("alreadyAssigned").asBoolean()); assertFalse(tree(memberPage).path("items").get(1).path("alreadyAssigned").asBoolean());
        var added=call("POST",path+"/assignments","{\"previewId\":\""+tree(addPreview).path("previewId").asText()+"\",\"reason\":\"补充人员\"}",admin,Map.of("If-Match",etag(published),"Idempotency-Key",key())); status(200,added); record("LearningTaskAdminDetail",added);
        var all=call("GET",path+"/assignments?userId=43",null,admin,Map.of()); status(200,all);
        String assignment=tree(all).path("items").get(0).path("assignmentId").asText();
        var detail=call("GET",path+"/assignments/"+assignment,null,admin,Map.of()); status(200,detail); record("LearningTaskAssignment",detail);
        var revoked=call("POST",path+"/assignments/"+assignment+":revoke","{\"reason\":\"未参加撤销\"}",admin,Map.of("If-Match",etag(detail),"Idempotency-Key",key())); status(200,revoked); record("LearningTaskAssignment",revoked);
        String futureDeadline=Instant.now().plusSeconds(7*86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC).toString();
        var changed=call("POST",path+":adjust-deadline","{\"firstDeadline\":\""+futureDeadline+"\",\"reason\":\"延期\"}",admin,Map.of("If-Match",etag(added),"Idempotency-Key",key())); status(200,changed); record("LearningTaskAdminDetail",changed);
        var cancelled=call("POST",path+":cancel","{\"reason\":\"取消测试任务\"}",admin,Map.of("If-Match",etag(changed),"Idempotency-Key",key())); status(200,cancelled); record("LearningTaskAdminDetail",cancelled);
        var closing=create(command,key()); status(201,closing); String closingPath=location(closing);
        var closingPreview=call("POST",closingPath+"/audience-previews","{\"purpose\":\"PUBLISH\",\"audience\":{\"userIds\":[\"42\",\"43\"]}}",admin,Map.of("If-Match",etag(closing),"Idempotency-Key",key())); status(201,closingPreview);
        var closingPublished=call("POST",closingPath+":publish","{\"previewId\":\""+tree(closingPreview).path("previewId").asText()+"\"}",admin,Map.of("If-Match",etag(closing),"Idempotency-Key",key())); status(200,closingPublished);
        ((MutableHttpClock)context.getBean("learningTaskClock")).value=java.time.OffsetDateTime.parse(tree(closingPublished).path("firstDeadline").asText()).toInstant();
        var closed=call("POST",closingPath+":close",null,admin,Map.of("If-Match",etag(closingPublished),"Idempotency-Key",key())); status(200,closed); record("LearningTaskAdminDetail",closed);
    }
    @Test void realHttpDraftCreateReadPatchAndReplayPreserveOriginalWireAndMicroseconds() throws Exception {
        String key=key(); var created=create(body(),key); status(201,created); record("LearningTaskAdminDetail",created);
        var first=tree(created); assertEquals("DRAFT",first.path("status").asText());
        assertEquals("2026-10-30T18:00:00.123456+08:00",first.path("firstDeadline").asText());
        assertEquals("2026-10-14T11:22:33.123456+08:00",first.path("createdAt").asText());
        assertTrue(first.path("deadlineRevision").isIntegralNumber()); assertTrue(first.path("createdBy").isTextual());
        assertFalse(first.has("publishedAt")); assertFalse(first.has("article")); assertFalse(first.has("revision"));
        assertEquals("org-A",first.path("audience").path("filters").get(0).path("organizationIds").get(0).asText());
        var read=call("GET",location(created),null,admin,Map.of()); status(200,read); assertEquals(created.body(),read.body());
        var updated=call("PATCH",location(created),"{\"title\":\"已编辑\",\"description\":\"\"}",admin,Map.of("If-Match",etag(created)));
        status(200,updated); record("LearningTaskAdminDetail",updated); assertNotEquals(etag(created),etag(updated));
        assertEquals(first.path("audience"),tree(updated).path("audience")); assertEquals("",tree(updated).path("description").asText());
        var replay=create(body(),key); status(201,replay);
        assertEquals(created.body(),replay.body()); assertEquals(location(created),location(replay)); assertEquals(etag(created),etag(replay));
        status(409,create(body().replace("草稿说明","不同正文"),key));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_task",Integer.class));
        assertEquals("no-store",created.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(db.queryForObject("SELECT result FROM learning_task_request WHERE request_key=?",String.class,key).contains(".123456"));
    }
    @Test void strictHttpValidationRejectsCoercionUnknownFieldsNullsAndInvalidMerge() throws Exception {
        for(String invalid:List.of(body().replace("\"reviewRequired\":false","\"reviewRequired\":\"false\""),
                body().replace("\"minimumFiles\":0","\"minimumFiles\":\"0\""),
                body().replace("\"title\":\"HTTP专题学习\"","\"title\":null"),
                body().replace("\"title\":\"HTTP专题学习\"","\"createdBy\":\"9\",\"title\":\"HTTP专题学习\""),
                body().replace("\"maximumFiles\":0","\"maximumFiles\":1"),
                body().replace("\"reviewRequired\":false","\"reviewRequired\":true"),
                body().replace("2026-10-30T10:00:00.123456Z","2026-10-30 10:00:00"),
                body().replace("\"org-A\"","7"))) {
            var response=create(invalid,key()); status(400,response); assertEquals("/problems/validation-error",tree(response).path("type").asText());
            record("ProblemDetails",response);
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM learning_task",Integer.class));
        var draft=create(body(),key()); status(201,draft);
        for(String patch:List.of("{}","{\"description\":null}","{\"audience\":null}","{\"reviewRequired\":true}",
                "{\"requirements\":{\"confirmationRequired\":true}}","{\"firstDeadline\":\"2026-10-15T12:00+08:00\"}")) {
            status(400,call("PATCH",location(draft),patch,admin,Map.of("If-Match",etag(draft))));
        }
        var clear=call("PATCH",location(draft),"{\"articleId\":null}",admin,Map.of("If-Match",etag(draft)));
        status(200,clear); assertFalse(tree(clear).has("article"));
    }
    @Test void missingMalformedStaleEtagsAndConcurrentEditorsAreVisibleThroughHttp() throws Exception {
        var draft=create(body(),key()); status(201,draft); String path=location(draft);
        status(428,call("PATCH",path,"{\"title\":\"A\"}",admin,Map.of()));
        status(400,call("PATCH",path,"{\"title\":\"A\"}",admin,Map.of("If-Match","W/"+etag(draft))));
        var one=client.sendAsync(request("PATCH",path,"{\"title\":\"A\"}",admin,Map.of("If-Match",etag(draft))),HttpResponse.BodyHandlers.ofString());
        var two=client.sendAsync(request("PATCH",path,"{\"title\":\"B\"}",admin,Map.of("If-Match",etag(draft))),HttpResponse.BodyHandlers.ofString());
        var statuses=List.of(one.get(15,TimeUnit.SECONDS).statusCode(),two.get(15,TimeUnit.SECONDS).statusCode()).stream().sorted().toList();
        assertEquals(List.of(200,412),statuses);
    }
    @Test void concurrentCreateAndDiscardedResponseRecoverTheSameResourceWithSameKey() throws Exception {
        String key=key();
        var one=client.sendAsync(request("POST","/v1/admin/learning-tasks",body(),admin,Map.of("Idempotency-Key",key)),HttpResponse.BodyHandlers.ofString());
        var two=client.sendAsync(request("POST","/v1/admin/learning-tasks",body(),admin,Map.of("Idempotency-Key",key)),HttpResponse.BodyHandlers.ofString());
        var first=one.get(15,TimeUnit.SECONDS); var second=two.get(15,TimeUnit.SECONDS);
        status(201,first); status(201,second); assertEquals(first.body(),second.body());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_task",Integer.class));
        // Deliberately discard a committed response, then recover with the original identity.
        String lost=key(); create(body(),lost);
        var recovered=create(body(),lost); status(201,recovered);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM learning_task",Integer.class));
        provider.revoked=true;
        status(403,create(body(),lost)); // replay cannot return a previously authorized body after revocation.
    }
    @Test void listsUseTrustedScopeLiteralSearchAndStableOrderingWithoutInventingEmptyOnFailure() throws Exception {
        var first=create(body().replace("HTTP专题学习","唯一%_标题"),key()); status(201,first);
        var second=create(body(),key()); status(201,second);
        var third=create(body(),key()); status(201,third);
        db.update("UPDATE learning_task SET created_by=10 WHERE id=?",Long.parseLong(tree(first).path("taskId").asText()));
        var page=call("GET","/v1/admin/learning-tasks?page=1&pageSize=1",null,admin,Map.of());
        status(200,page); record("LearningTaskAdminPage",page);
        assertTrue(tree(page).path("totalItems").isIntegralNumber()); assertEquals(2,tree(page).path("totalItems").asInt());
        assertEquals(tree(third).path("taskId"),tree(page).path("items").get(0).path("taskId"));
        var next=call("GET","/v1/admin/learning-tasks?page=2&pageSize=1",null,admin,Map.of());
        status(200,next); assertEquals(tree(second).path("taskId"),tree(next).path("items").get(0).path("taskId"));
        status(404,call("GET",location(first),null,admin,Map.of()));
        status(404,call("GET",location(second),null,reader,Map.of()));
        status(403,call("PATCH",location(second),"{\"title\":\"越权\"}",reader,Map.of("If-Match",etag(second))));
        var search=call("GET","/v1/admin/learning-tasks?q=%25_",null,reader,Map.of());
        status(200,search); assertEquals(1,tree(search).path("totalItems").asInt());
        var absent=call("GET","/v1/admin/learning-tasks?status=PUBLISHED",null,admin,Map.of());
        status(200,absent); assertEquals(0,tree(absent).path("totalItems").asInt());
        for(String query:List.of("?page=0","?pageSize=101","?status=EXPIRED","?pageSize=abc")) {
            status(400,call("GET","/v1/admin/learning-tasks"+query,null,admin,Map.of()));
        }
        status(404,call("GET","/v1/admin/learning-tasks/not-a-persisted-id",null,admin,Map.of()));
    }
    @Test void articleReadUsesOwnerAvailabilityButReferenceUseAndSafetyCannotBeBypassed() throws Exception {
        provider.safetyUnavailable=true;
        var failure=create(body(),key()); status(503,failure); assertEquals("CONTENT_SAFETY",tree(failure).path("dependency").asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM learning_task",Integer.class)); provider.safetyUnavailable=false;
        status(409,create(body().replace("\"requirements\":","\"articleId\":\"unknown-reference\",\"requirements\":"),key()));
        var draft=create(body(),key()); status(201,draft);
        db.update("INSERT INTO article_publisher(id,display_name,updated_by,updated_at) VALUES(1,'lt-test-publisher',9,?) ON DUPLICATE KEY UPDATE display_name=VALUES(display_name)",LocalDateTime.of(2026,10,14,11,0));
        db.update("INSERT INTO article(title,summary,body,plain_text,publisher_id,publish_status,created_by,updated_by,created_at,updated_at) VALUES('lt-http-article','',JSON_OBJECT(),'private-text',1,'DRAFT',9,9,?,?)",
                LocalDateTime.of(2026,10,14,11,0),LocalDateTime.of(2026,10,14,11,0));
        long article=db.queryForObject("SELECT MAX(id) FROM article WHERE title='lt-http-article'",Long.class);
        db.update("UPDATE learning_task SET article_id=? WHERE id=?",article,Long.parseLong(tree(draft).path("taskId").asText()));
        var hidden=call("GET",location(draft),null,admin,Map.of()); status(200,hidden);
        assertEquals("UNAVAILABLE",tree(hidden).path("article").path("availability").asText()); assertFalse(hidden.body().contains("private-text"));
        db.update("UPDATE article SET publish_status='PUBLISHED',published_at=created_at,last_published_at=created_at WHERE id=?",article);
        var visible=call("GET",location(draft),null,admin,Map.of()); status(200,visible);
        assertEquals("AVAILABLE",tree(visible).path("article").path("availability").asText()); record("LearningTaskAdminDetail",visible);
        assertNotEquals(etag(hidden),etag(visible));
        status(412,call("PATCH",location(draft),"{\"title\":\"过期表示\"}",admin,Map.of("If-Match",etag(hidden))));
        status(409,call("PATCH",location(draft),"{\"articleId\":\""+article+"\"}",admin,Map.of("If-Match",etag(visible))));
    }
    @Test void allThirtyProductionOperationsRemainAuthenticatedAndFailClosedOverActualHttp() throws Exception {
        provider.productionDenial=true;
        var paths=json.readTree(Files.readString(Path.of("../contracts/api/paths/learning-task.yaml"))); int count=0;
        for(var entries=paths.fields();entries.hasNext();) {
            var entry=entries.next(); String path=entry.getKey().replaceAll("\\{[^}]+}","42");
            for(var operations=entry.getValue().fields();operations.hasNext();) {
                var operation=operations.next();
                if(!operation.getValue().has("operationId")) { continue; }
                String method=operation.getKey().toUpperCase(Locale.ROOT);
                String token=path.startsWith("/v1/admin/")?admin:user;
                var response=call(method,path,"GET".equals(method)?null:"{}",token,Map.of());
                status(503,response); assertEquals("AUTHORIZATION",tree(response).path("dependency").asText());
                record("LearningTaskDependencyProblem",response);
                status(401,call(method,path,"GET".equals(method)?null:"{}",null,Map.of()));
                count++;
            }
        }
        assertEquals(30,count); assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM learning_task",Integer.class));
    }

    String boundLearningDuty(long recipient,String duty) {
        long root=11;db.update("INSERT INTO user_profile(id,openid,role,status) VALUES(11,'lt-explicit-source-root',1,1) ON DUPLICATE KEY UPDATE status=1,deleted_at=NULL");
        db.update("INSERT INTO rbac_authorization_authority VALUES(11,'ASSIGNMENT',UUID(),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE source='ASSIGNMENT'");
        db.update("UPDATE rbac_permission_definition p JOIN rbac_role_permission m ON p.code=m.permission_code SET p.lifecycle='ENABLED',p.readiness='READY',p.grantable=1,p.unavailable_reason=NULL WHERE m.role_code='ROOT'");
        db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='ROOT'");
        var roots=db.queryForList("SELECT id FROM rbac_user_role_assignment WHERE user_id=11 AND role_code='ROOT' AND revoked_at IS NULL",String.class);
        String rootId=roots.isEmpty()?UUID.randomUUID().toString():roots.get(0);
        if(roots.isEmpty())db.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(?,11,'ROOT','AUTHORIZATION','GLOBAL',UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,UUID(),UUID())",rootId);
        var target=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("PLATFORM",null,null);
        var selection=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection(duty,target,cn.jualn.miniapp.module.authorization.support.RbacTime.wire(LocalDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0)),null);
        var intent=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("GRANT",Long.toString(recipient),List.of(selection),rootId,null,null,"explicit isolated Learning source");
        var unified=context.getBean(cn.jualn.miniapp.module.authorization.service.UnifiedAuthorizationService.class);var preview=unified.preview(root,intent,key());
        return unified.commit(root,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(preview.previewId(),intent),key()).assignmentIds().get(0);
    }
    void textPorts() {
        db.update("DELETE d FROM notification_delivery d JOIN notification n ON n.id=d.notification_id WHERE n.type IN (29,30)");
        db.update("DELETE FROM notification WHERE type IN (29,30)");
        db.update("INSERT INTO user_profile(id,openid,role,status) VALUES(43,'lt-text-second',1,1),(44,'lt-text-outsider',1,1) ON DUPLICATE KEY UPDATE status=1,deleted_at=NULL");
        for(long id:List.of(9L,10L,42L,43L,44L)) db.update("INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at) VALUES(?,'ASSIGNMENT',UUID(),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE source='ASSIGNMENT',version=UUID()",id);
        for(String role:List.of("LEARNING_MANAGER","LEARNING_REVIEWER")) {
            db.update("UPDATE rbac_permission_definition p JOIN rbac_role_permission m ON p.code=m.permission_code SET p.lifecycle='ENABLED',p.readiness='READY',p.grantable=1,p.unavailable_reason=NULL WHERE m.role_code=?",role);
            db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=?",role);
        }
        db.update("DELETE FROM rbac_user_role_assignment WHERE user_id IN (9,10)");
        boundLearningDuty(9,"LEARNING_MANAGE");boundLearningDuty(10,"LEARNING_REVIEW");
        provider.integrated=context.getBean(UnavailableLearningTaskProviders.class);
        var response=new cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse();response.setErrcode(0);response.setVerifiedPassStructure(true);
        var result=new cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse.ResultInfo();result.setSuggest("pass");response.setResult(result);
        org.mockito.Mockito.when(context.getBean(cn.jualn.miniapp.third.wx.client.WxClient.class).msgSecCheck(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return response;
        });
    }
    String publishedText(boolean review) throws Exception {
        String body="{\"managementTarget\":{\"type\":\"PLATFORM\"},\"title\":\"文字学习\",\"description\":\"请提交文字成果\",\"requirements\":{\"confirmationRequired\":true,\"minimumTextLength\":5,\"minimumFiles\":0,\"maximumFiles\":0,\"allowedFileKinds\":[]},\"reviewRequired\":"+review+",\"firstDeadline\":\"2026-10-15T08:00:00+08:00\",\"audience\":{\"userIds\":[\"42\",\"43\"]}}";
        var draft=create(body,key());status(201,draft);String path=location(draft);
        var preview=call("POST",path+"/audience-previews","{\"purpose\":\"PUBLISH\",\"audience\":{\"userIds\":[\"42\",\"43\"]}}",admin,Map.of("If-Match",etag(draft),"Idempotency-Key",key()));status(201,preview);
        status(200,call("POST",path+":publish","{\"previewId\":\""+tree(preview).path("previewId").asText()+"\"}",admin,Map.of("If-Match",etag(draft),"Idempotency-Key",key())));
        return path;
    }
    @Test void textNoReviewHttpPreservesOwnershipAtomicCompletionAndReplay() throws Exception {
        verticalExchanges.clear();
        textPorts();String path=publishedText(false),mine=path.replace("/admin/","/users/me/");
        String outsider=session(StpUtil.getStpLogic(),44L),second=session(StpUtil.getStpLogic(),43L);
        var detail=call("GET",mine,null,user,Map.of());status(200,detail);
        status(401,call("GET",mine,null,admin,Map.of()));
        status(401,call("GET","/v1/admin/learning-task-submissions",null,user,Map.of()));
        status(404,call("POST",mine+"/submissions","{\"text\":\"有效学习文字\",\"confirmed\":true}",outsider,Map.of("If-Match",etag(detail),"Idempotency-Key",key())));
        for(String invalid:List.of("{}","{\"text\":\"\",\"confirmed\":true}","{\"text\":\"短\",\"confirmed\":true}","{\"text\":\"有效学习文字\"}","{\"text\":\"有效学习文字\",\"confirmed\":true,\"fileIds\":[\"opaque-file\"]}","{\"text\":\"有效学习文字\",\"confirmed\":true,\"completedAt\":\"2026-10-14T00:00:00Z\"}"))
            status(400,call("POST",mine+"/submissions",invalid,user,Map.of("If-Match",etag(detail),"Idempotency-Key",key())));
        String body="{\"text\":\"有效学习文字成果\",\"confirmed\":true}",key=key();
        var submitted=call("POST",mine+"/submissions",body,user,Map.of("If-Match",etag(detail),"Idempotency-Key",key));status(201,submitted);record("LearningTaskSubmission",submitted);
        assertEquals("NOT_REQUIRED",tree(submitted).path("reviewStatus").asText()); assertTrue(tree(submitted).path("attempt").isNumber());assertFalse(tree(submitted).has("fileIds"));
        assertEquals(submitted.body(),call("POST",mine+"/submissions",body,user,Map.of("If-Match","\"old\"","Idempotency-Key",key)).body());
        status(409,call("POST",mine+"/submissions",body.replace("成果","其他"),user,Map.of("If-Match",etag(detail),"Idempotency-Key",key)));
        status(200,call("GET",location(submitted),null,user,Map.of()));
        status(404,call("GET",location(submitted),null,second,Map.of()));
        var history=call("GET",mine+"/submissions",null,user,Map.of());status(200,history);record("LearningTaskSubmissionPage",history);assertEquals(1,tree(history).path("totalItems").asInt());
        var completed=call("GET",mine,null,user,Map.of());assertEquals("COMPLETED",tree(completed).path("assignment").path("progress").asText());
        status(409,call("POST",mine+"/submissions",body,user,Map.of("If-Match",etag(completed),"Idempotency-Key",key())));
        var stats=call("GET",path+"/statistics",null,admin,Map.of());assertEquals(1,tree(stats).path("completed").asInt());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_task_submission",Integer.class));
        status(403,call("GET","/v1/admin/learning-task-submissions",null,admin,Map.of()));
        var queue=call("GET","/v1/admin/learning-task-submissions?actionable=false",null,reader,Map.of());status(200,queue);record("LearningTaskReviewPage",queue);
        saveTextFacts("no-review");
    }
    @Test void textReviewHttpRejectResubmitFixedWindowApproveAndNotificationRollback() throws Exception {
        verticalExchanges.clear();
        textPorts();String path=publishedText(true),mine=path.replace("/admin/","/users/me/");
        String first=null,deadline=null,latest=null;
        for(int attempt=1;attempt<=3;attempt++) {
            var detail=call("GET",mine,null,user,Map.of());
            var submitted=call("POST",mine+"/submissions","{\"text\":\"学习成果第"+attempt+"次\",\"confirmed\":true}",user,Map.of("If-Match",etag(detail),"Idempotency-Key",key()));status(201,submitted);
            assertEquals(attempt,tree(submitted).path("attempt").asInt());assertEquals("PENDING_REVIEW",tree(submitted).path("reviewStatus").asText());
            var current=call("GET",mine,null,user,Map.of());assertEquals("PENDING_REVIEW",tree(current).path("assignment").path("progress").asText());assertFalse(tree(current).path("assignment").has("completedAt"));
            status(409,call("POST",mine+"/submissions","{\"text\":\"重复新提交文字\",\"confirmed\":true}",user,Map.of("If-Match",etag(current),"Idempotency-Key",key())));
            String submission=tree(submitted).path("submissionId").asText(); if(first==null)first=submission;
            String adminPath="/v1/admin/learning-task-submissions/"+submission;
            var review=call("GET",adminPath,null,reader,Map.of());status(200,review);record("LearningTaskReviewDetail",review);
            var queue=call("GET","/v1/admin/learning-task-submissions?taskId="+tree(detail).path("task").path("taskId").asText(),null,reader,Map.of());status(200,queue);assertEquals(1,tree(queue).path("totalItems").asInt());
            status(403,call("POST",adminPath+":review","{\"decision\":\"APPROVE\"}",admin,Map.of("If-Match",etag(review),"Idempotency-Key",key())));
            String decision=attempt<3?"{\"decision\":\"REJECT\",\"reason\":\"请补充学习收获\"}":"{\"decision\":\"APPROVE\"}",key=key();
            if(attempt==3) {
                db.execute("CREATE TRIGGER lt_text_review_failure BEFORE INSERT ON notification FOR EACH ROW BEGIN IF NEW.type=29 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated review rollback'; END IF; END");
                try {status(503,call("POST",adminPath+":review",decision,reader,Map.of("If-Match",etag(review),"Idempotency-Key",key)));}
                finally {db.execute("DROP TRIGGER lt_text_review_failure");}
                assertEquals("PENDING_REVIEW",tree(call("GET",adminPath,null,reader,Map.of())).path("submission").path("reviewStatus").asText());
            }
            var decided=call("POST",adminPath+":review",decision,reader,Map.of("If-Match",etag(review),"Idempotency-Key",key));status(200,decided);record("LearningTaskSubmission",decided);
            assertEquals(decided.body(),call("POST",adminPath+":review",decision,reader,Map.of("If-Match","\"old\"","Idempotency-Key",key)).body());
            var after=call("GET",mine,null,user,Map.of());latest=tree(after).path("assignment").path("progress").asText();
            if(attempt<3) {String window=tree(after).path("assignment").path("supplementDeadline").asText();if(deadline==null)deadline=window;assertEquals(deadline,window);}
        }
        assertEquals("COMPLETED",latest);
        var history=call("GET",mine+"/submissions?pageSize=2",null,user,Map.of());status(200,history);assertEquals(3,tree(history).path("totalItems").asInt());assertEquals(2,tree(history).path("items").size());
        assertEquals("REJECTED",tree(call("GET",mine+"/submissions/"+first,null,user,Map.of())).path("reviewStatus").asText());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM learning_task_submission",Integer.class));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM notification WHERE type IN (29,30)",Integer.class));
        assertEquals(1,tree(call("GET",path+"/statistics",null,admin,Map.of())).path("completed").asInt());
        assertEquals(0,tree(call("GET","/v1/admin/learning-task-submissions",null,reader,Map.of())).path("totalItems").asInt());
        saveTextFacts("review-resubmit");
    }

    void saveTextFacts(String scenario) throws Exception {
        var facts=new java.util.LinkedHashMap<String,Object>();
        facts.put("scope","Disposable MySQL and actual loopback HTTP; synthetic sessions/RBAC and Wx fixture, not phone acceptance");
        facts.put("exchanges",List.copyOf(verticalExchanges));
        facts.put("assignments",db.queryForList("SELECT id,task_id,user_id,progress,latest_submission_id,supplement_deadline,completed_at,revision FROM learning_task_assignment ORDER BY id"));
        facts.put("submissions",db.queryForList("SELECT id,task_id,assignment_id,attempt,review_status,submitted_at,reviewed_at,reviewed_by,revision FROM learning_task_submission ORDER BY id"));
        facts.put("notifications",db.queryForList("SELECT id,user_id,type,source_key,inbox_seq FROM notification WHERE type IN (29,30) ORDER BY id"));
        Files.writeString(Path.of("target/learning-task-text-"+scenario+"-facts.json"),json.writeValueAsString(facts));
    }

    /** TEST ONLY: trusted fixtures define these grants. No property, profile or bean can install them in main. */
    static class ControlledDraftProviders implements LearningTaskProviders {
        final JdbcTemplate db;
        final UnavailableLearningTaskProviders unavailable;
        volatile boolean productionDenial;
        volatile boolean safetyUnavailable;
        volatile boolean revoked;
        volatile cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy formal;
        volatile LearningTaskProviders integrated;
        ControlledDraftProviders(JdbcTemplate db,ArticleReferenceReadService articles) {
            this.db=db;
            var policy=org.mockito.Mockito.mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy.class);
            org.mockito.Mockito.when(policy.authorize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                    .thenThrow(unavailable(Dependency.AUTHORIZATION));
            var notices=org.mockito.Mockito.mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskNotifications.class);
            org.mockito.Mockito.doThrow(unavailable(Dependency.NOTIFICATION_ACCEPTANCE)).when(notices).accept(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doThrow(unavailable(Dependency.NOTIFICATION_ACCEPTANCE)).when(notices).reconcile(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
            unavailable=new UnavailableLearningTaskProviders(articles,policy,(actor,text)->{throw unavailable(Dependency.CONTENT_SAFETY);},
                    org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService.class),notices);
        }
        public long authorize(Action action,Long task,Long owner) {
            if(integrated!=null) { return integrated.authorize(action,task,owner); }
            if(action==Action.MY_READ || action==Action.CONFIRM || action==Action.SUBMIT) {
                StpUtil.checkLogin();
                if(productionDenial) { throw unavailable(Dependency.AUTHORIZATION); }
                return StpUtil.getLoginIdAsLong();
            }
            AdminStpUtil.STP_LOGIC.checkLogin();
            if(productionDenial) { return unavailable.authorize(action,task,owner); }
            if(formal!=null) { return formal.authorize(action,owner); }
            long actor=AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
            int valid=db.queryForObject("SELECT COUNT(*) FROM user_profile WHERE id=? AND status=1 AND deleted_at IS NULL",Integer.class,actor);
            if(valid!=1||revoked||!Set.of(Action.CREATE,Action.EDIT,Action.READ).contains(action)||
                    (actor!=9&&actor!=10)||(actor==10&&action!=Action.READ)) {
                throw new ContractProblemException(HttpStatus.FORBIDDEN,"/problems/forbidden","Controlled test grant absent");
            }
            if(task!=null) {
                if(action==Action.CREATE&&owner!=null) {
                    if(owner!=actor) { throw missing(); }
                } else if(db.queryForObject("SELECT COUNT(*) FROM learning_task WHERE id=? AND created_by=?",Integer.class,task,actor)!=1) {
                    throw missing();
                }
            }
            return actor;
        }
        public ReadScope readableTasks() {
            if(integrated!=null) { return integrated.readableTasks(); }
            long actor=authorize(Action.READ,null,null);
            if(formal!=null) { return ReadScope.globalNamespace(); }
            return new ReadScope(db.queryForList("SELECT id FROM learning_task WHERE created_by=?",Long.class,actor));
        }
        public Set<Long> publiclyVisibleArticles(Set<Long> ids) { return unavailable.publiclyVisibleArticles(ids); }
        public String audienceVersion(long actor,long task) {return integrated==null?unavailable.audienceVersion(actor,task):integrated.audienceVersion(actor,task);}
        public String own(long actor,cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject subject) {return integrated==null?unavailable.own(actor,subject):integrated.own(actor,subject);}
        public cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient recipient(cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject subject) {return integrated==null?unavailable.recipient(subject):integrated.recipient(subject);}
        public List<cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient> resolve(long actor,Audience audience) { return integrated==null?unavailable.resolve(actor,audience):integrated.resolve(actor,audience); }
        public ReadScope reviewableTasks(){return integrated==null?readableTasks():integrated.reviewableTasks();}
        public void creationTarget(long actor,cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target target){if(integrated!=null)integrated.creationTarget(actor,target);}
        public List<cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient> resolve(long actor,long task,Audience audience){return integrated==null?resolve(actor,audience):integrated.resolve(actor,task,audience);}
        public void finish(long actor,boolean user){if(integrated!=null)integrated.finish(actor,user);}
        public void article(long actor,long id) { unavailable.article(actor,id); }
        public void textSafety(long actor,String text) { if(integrated!=null) { integrated.textSafety(actor,text); return; } if(safetyUnavailable) { throw unavailable(Dependency.CONTENT_SAFETY); } }
        public void publicationCapabilities(Requirements rules) { if(integrated!=null) { integrated.publicationCapabilities(rules); return; } unavailable.publicationCapabilities(rules); }
        public void bindPrivateFiles(long user,long assignment,long submission,List<Long> ids,Requirements rules) { unavailable.bindPrivateFiles(user,assignment,submission,ids,rules); }
        public void accept(List<Notice> notices) { if(integrated!=null) { integrated.accept(notices); return; } unavailable.accept(notices); }
        public void reconcile(long task,LocalDateTime now) { if(integrated!=null) { integrated.reconcile(task,now); return; } unavailable.reconcile(task,now); }
    }
    @TestConfiguration @EnableWebMvc @EnableTransactionManagement
    @MapperScan("cn.jualn.miniapp.module.learningtask.mapper")
    @Import({cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,LearningTaskServiceImpl.class,LearningTaskDraftController.class,LearningTaskController.class,
            cn.jualn.miniapp.module.learningtask.controller.LearningTaskSliceController.class,
            cn.jualn.miniapp.module.learningtask.controller.LearningTaskSubmissionController.class,
            cn.jualn.miniapp.module.learningtask.service.LearningTaskSubmissionQueries.class,
            cn.jualn.miniapp.module.learningtask.service.LearningTaskSliceQueries.class,
            cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter.class,
            LearningTaskEntryGate.class,LearningTaskProblemAdvice.class,LearningTaskDraftConverter.class,
            GlobalExceptionHandler.class,ArticleReferenceReadServiceImpl.class,JacksonConfig.class,LearningTaskVerticalTestBeans.class})
    static class Config {
        @Bean TomcatServletWebServerFactory webServer() throws Exception {
            var factory=new TomcatServletWebServerFactory(0); factory.setAddress(InetAddress.getByName("127.0.0.1")); return factory;
        }
        @Bean DispatcherServlet dispatcherServlet() { return new DispatcherServlet(); }
        @Bean ServletRegistrationBean<DispatcherServlet> registration(DispatcherServlet servlet) {
            var registration=new ServletRegistrationBean<>(servlet,"/"); registration.setLoadOnStartup(1); return registration;
        }
        @Bean Jackson2ObjectMapperBuilder jsonBuilder() { return new Jackson2ObjectMapperBuilder(); }
        @Bean WebMvcConfigurer mvc(ObjectMapper mapper) {
            mapper.addMixIn(org.springframework.http.ProblemDetail.class,ProblemDetailJacksonMixin.class);
            return new WebMvcConfigurer() {
                @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                    for(var converter:converters) {
                        if(converter instanceof MappingJackson2HttpMessageConverter jackson) { jackson.setObjectMapper(mapper); }
                    }
                }
                @Override public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                            if(request.getRequestURI().startsWith("/v1/admin/")) { AdminStpUtil.STP_LOGIC.checkLogin(); }
                            else { StpUtil.checkLogin(); cn.jualn.miniapp.common.constant.UserContext.setUserId(StpUtil.getLoginIdAsLong()); }
                            return true;
                        }
                        @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) {
                            cn.jualn.miniapp.common.constant.UserContext.clear();
                        }
                    });
                }
            };
        }
        @Bean DataSource source() {
            String url=System.getProperty("event.test.jdbcUrl");
            if(url==null||!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/learning_task_test")) { throw new IllegalArgumentException("Disposable LearningTask DB only"); }
            return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&forceConnectionTimeZoneToSession=true","root","");
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean(name="learningTaskClock") Clock clock() { return new MutableHttpClock(); }
        @Bean @org.springframework.context.annotation.Primary ControlledDraftProviders providers(JdbcTemplate db,ArticleReferenceReadService articles) { return new ControlledDraftProviders(db,articles); }
        @Bean SqlSessionFactory sessions(DataSource ds) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();
            global.setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler());
            factory.setGlobalConfig(global);
            var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); factory.setConfiguration(config);
            var resolver=new PathMatchingResourcePatternResolver();
            var locations=new ArrayList<org.springframework.core.io.Resource>(List.of(resolver.getResources("classpath*:mapper/Learning*.xml")));
            locations.add(new org.springframework.core.io.ClassPathResource("mapper/ArticleMapper.xml"));
            for(String name:List.of("RbacMapper","UserProfileMapper","NotificationMapper","AsyncJobMapper","PersonMapper","PersonBusinessMapper","UnifiedMapper")) {
                locations.add(new org.springframework.core.io.ClassPathResource("mapper/"+name+".xml"));
            }
            factory.setMapperLocations(locations.toArray(org.springframework.core.io.Resource[]::new)); return factory.getObject();
        }
        @Bean MapperFactoryBean<ArticleMapper> articles(SqlSessionFactory sessions) {
            var mapper=new MapperFactoryBean<>(ArticleMapper.class); mapper.setSqlSessionFactory(sessions); return mapper;
        }
    }
    static class MutableHttpClock extends Clock {
        volatile Instant value=Instant.parse("2026-10-14T03:22:33.123456Z");
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return value; }
    }
}
