package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Allocation;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Apply;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Deadline;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Decision;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Edit;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.FileKind;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Material;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.PreviewRequest;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Progress;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Purpose;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Result;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Review;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReviewStatus;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Revoke;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Save;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Status;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskService;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper;
import cn.jualn.miniapp.module.learningtask.service.impl.LearningTaskServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.conflict;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real MySQL constraints, Mapper and Spring transaction proxy. Only unintegrated providers are doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/learning_task_test")
class LearningTaskDatabaseTest {
    @org.junit.jupiter.api.BeforeAll static void migrateDisposable() {
        String url=System.getProperty("event.test.jdbcUrl");
        if(url==null || !url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/learning_task_test"))throw new IllegalArgumentException("Disposable LearningTask DB only");
        String options="?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        var admin=new JdbcTemplate(new DriverManagerDataSource(url.substring(0,url.lastIndexOf('/'))+"/mysql"+options,"root",""));
        admin.execute("CREATE DATABASE IF NOT EXISTS learning_task_test");
        var data=new DriverManagerDataSource(url+options,"root","");
        var before=org.flywaydb.core.Flyway.configure().dataSource(data).target("37").load();
        if(before.info().applied().length==0)before.migrate();
        var fixture=new JdbcTemplate(data);
        fixture.update("INSERT INTO user_profile(id,openid) VALUES(9,'legacy-learning-sentinel-user') ON DUPLICATE KEY UPDATE id=id");
        fixture.update("INSERT INTO post(user_id,title,content) SELECT 9,'legacy-learning-fixture','preserve-before-v38' WHERE NOT EXISTS(SELECT 1 FROM post WHERE title='legacy-learning-fixture')");
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(data).load();flyway.migrate();flyway.validate();
    }
    AnnotationConfigApplicationContext context;
    LearningTaskService service;
    JdbcTemplate db;
    TestProviders provider;
    MutableClock clock;
    final LocalDateTime start=LocalDateTime.of(2026,10,14,10,0);
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Config.class);
        service=context.getBean(LearningTaskService.class); db=context.getBean(JdbcTemplate.class);
        provider=context.getBean(TestProviders.class); clock=context.getBean(MutableClock.class); at(start);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        db.update("INSERT INTO user_profile(id,openid) VALUES(42,'learning-user42'),(43,'learning-user43') ON DUPLICATE KEY UPDATE openid=VALUES(openid)");
        // Keep FK checks active, including the circular latest-submission history relation.
        db.update("UPDATE learning_task_assignment SET latest_submission_id=NULL");
        db.update("DELETE FROM media_learning_receipt");db.update("DELETE FROM media_learning_file");db.update("DELETE FROM media_learning_upload");
        for(String table:List.of("learning_task_request","learning_task_audit","learning_task_preview","learning_task_submission","learning_task_assignment","learning_task"))
            { db.update("DELETE FROM "+table); }
        db.update("DELETE FROM outbox_event WHERE topic='learning-task.test'");
    }
    @AfterEach void close() { if(context!=null) { context.close(); } }
    @Test void videoOnlySubmissionBindsAllBytesAtomicallyAndRetainsRejectedHistory() throws Exception {
        var sessions=context.getBean(SqlSessionFactory.class);
        var mediaType=cn.jualn.miniapp.module.media.mapper.LearningMaterialMapper.class;
        if(!sessions.getConfiguration().hasMapper(mediaType)) sessions.getConfiguration().addMapper(mediaType);
        var mediaMapper=new org.mybatis.spring.SqlSessionTemplate(sessions).getMapper(mediaType);
        var storage=org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.LearningMaterialStorage.class);
        org.mockito.Mockito.when(storage.kinds()).thenReturn(List.of("VIDEO"));org.mockito.Mockito.when(storage.configuredKinds()).thenReturn(List.of("VIDEO"));
        org.mockito.Mockito.when(storage.credentials(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            java.time.LocalDateTime expiry=call.getArgument(2);
            return new cn.jualn.miniapp.module.media.bo.PointMaterialBO.Credentials("fixture-private-123","fixture",call.getArgument(1),"redacted","redacted","redacted",expiry.minusSeconds(10).toEpochSecond(java.time.ZoneOffset.UTC),null,call.getArgument(0),expiry.minusSeconds(10));
        });
        var media=new cn.jualn.miniapp.module.media.service.LearningMaterialService(mediaMapper,storage,context.getBean(DataSourceTransactionManager.class),clock);
        provider.fileBinding=(user,assignment,submission,files,requirements)->media.bind(user,assignment,submission,files,requirements.allowedFileKinds().stream().map(Enum::name).toList());
        var rules=new Requirements(false,0,1,1,List.of(FileKind.VIDEO));var current=ready(rules,true);
        var ids=new cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter();
        var converter=new cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter(ids);
        var queries=new cn.jualn.miniapp.module.learningtask.service.LearningTaskSliceQueries(service,provider,context.getBean(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskSliceMapper.class),context.getBean(LearningTaskLedgerMapper.class),converter,clock);
        var files=new cn.jualn.miniapp.module.learningtask.service.LearningTaskFiles(service,queries,provider,media,org.mockito.Mockito.mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskScopePolicy.class),context.getBean(DataSourceTransactionManager.class));
        var errors=new cn.jualn.miniapp.common.exception.GlobalExceptionHandler();
        var wireJson=new cn.jualn.miniapp.config.JacksonConfig().objectMapper(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder());
        wireJson.addMixIn(org.springframework.http.ProblemDetail.class,org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new cn.jualn.miniapp.module.learningtask.controller.LearningTaskController(files,ids),
            new cn.jualn.miniapp.module.learningtask.controller.LearningTaskSubmissionController(service,provider,queries,org.mockito.Mockito.mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskSubmissionQueries.class),ids,converter))
            .setControllerAdvice(new cn.jualn.miniapp.module.learningtask.controller.LearningTaskProblemAdvice(errors),errors).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(wireJson)).build();
        java.util.function.Function<Result,Long> video=result->{
            Runnable owner=()->service.mine(result.task().taskId(),result.assignment().assignmentId());
            var upload=media.upload(42,result.assignment().assignmentId(),List.of(new cn.jualn.miniapp.module.media.bo.PointMaterialBO.Declaration("成果.mp4","VIDEO","video/mp4",100)),key(),owner,owner);
            return Long.parseLong(media.register(42,result.assignment().assignmentId(),upload.uploadId(),0,key(),owner,owner).fileId());
        };
        long first=video.apply(current);
        String assignmentPath="/v1/users/me/learning-task-assignments/"+current.assignment().assignmentId();
        var wireResponses=new java.util.ArrayList<java.util.Map<String,Object>>();
        var credentialsResponse=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(assignmentPath+"/file-upload-credentials").contentType("application/json").header("Idempotency-Key",key()).content("{\"files\":[{\"fileName\":\"proof.mp4\",\"kind\":\"VIDEO\",\"contentType\":\"video/mp4\",\"sizeBytes\":100}]}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.expiredTime").isNumber()).andReturn().getResponse();
        recordBusinessWire(wireResponses,"LearningTaskFileCredentials",credentialsResponse);
        var submittedResponse=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(assignmentPath+"/submissions").contentType("application/json").header("If-Match",assignmentTag(current)).header("Idempotency-Key",key()).content("{\"fileIds\":[\""+first+"\"]}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.fileIds[0]").value(Long.toString(first))).andReturn().getResponse();
        recordBusinessWire(wireResponses,"LearningTaskSubmission",submittedResponse);
        long submitted=db.queryForObject("SELECT latest_submission_id FROM learning_task_assignment WHERE id=?",Long.class,current.assignment().assignmentId());
        var pending=service.ownSubmission(current.task().taskId(),current.assignment().assignmentId(),submitted);
        var fileResponse=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(assignmentPath+"/files/"+first))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.unboundExpiresAt").doesNotExist()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andReturn().getResponse();
        recordBusinessWire(wireResponses,"LearningTaskFile",fileResponse);
        org.mockito.Mockito.when(storage.access(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->new cn.jualn.miniapp.module.media.bo.PointMaterialBO.Access("https://private.invalid/synthetic",call.getArgument(1)));
        var accessResponse=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(assignmentPath+"/files/"+first+"/access")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        recordBusinessWire(wireResponses,"LearningTaskFileAccess",accessResponse);
        provider.user=43;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(assignmentPath+"/files/"+first)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());provider.user=42;
        assertNull(pending.submission().text());assertEquals(Progress.PENDING_REVIEW,pending.assignment().progress());assertNull(pending.assignment().completedAt());
        var rejected=review(pending,Decision.REJECT);long second=video.apply(rejected);
        var resubmitted=service.submit(current.task().taskId(),current.assignment().assignmentId(),new Material(null,List.of(second),null),assignmentTag(rejected),key());
        assertEquals(2,resubmitted.submission().attempt());
        var history=service.history(current.task().taskId(),current.assignment().assignmentId());assertEquals(2,history.size());assertEquals(List.of(first),history.get(0).fileIds());assertEquals(ReviewStatus.REJECTED,history.get(0).reviewStatus());
        assertEquals(pending.submission().submissionId(),db.queryForObject("SELECT submission_id FROM media_learning_file WHERE file_id=?",Long.class,first));
        var another=ready(rules,true);long submissions=count("learning_task_submission");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/learning-task-assignments/"+another.assignment().assignmentId()+"/files/"+first)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        problem(400,()->service.submit(another.task().taskId(),another.assignment().assignmentId(),new Material(null,List.of(first),null),assignmentTag(another),key()));
        assertEquals(submissions,count("learning_task_submission"));assertEquals(Progress.NOT_STARTED,service.mine(another.task().taskId(),another.assignment().assignmentId()).assignment().progress());
        wireResponses.add(java.util.Map.of("schema","LearningTaskFileCapabilities","status",200,"producer","Media service with storage double","body",wireJson.valueToTree(media.capabilities())));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/learning-task-v1-business-http-responses.json").toFile(),wireResponses);
    }
    private void recordBusinessWire(java.util.List<java.util.Map<String,Object>> evidence,String schema,org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        evidence.add(java.util.Map.of("schema",schema,"status",response.getStatus(),"producer","MockMvc / real MySQL; synthetic identity and storage double","body",new ObjectMapper().readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))));
    }
    @Test void expiredPreviewAndReceiptKeepFrozenMembersButLoseApplicability() {
        var draft=draft(rules(true,0,false),false);String requestKey=key();
        var command=new PreviewRequest(Purpose.PUBLISH,draft.task().audience());
        var original=service.preview(draft.task().taskId(),command,taskTag(draft),requestKey);
        assertTrue(original.preview().canApply());at(start.plusMinutes(5));
        var expired=service.preview(draft.task().taskId(),original.preview().previewId());
        assertEquals(original.preview().members(),expired.preview().members());assertTrue(!expired.preview().canApply());
        assertTrue(!LearningTaskEtags.preview(original.preview()).equals(LearningTaskEtags.preview(expired.preview())));
        assertEquals(expired.preview(),service.preview(draft.task().taskId(),command,"old-tag",requestKey).preview());
        assertEquals(original.preview().members(),service.previewMembers(draft.task().taskId(),original.preview().previewId(),1,20).items());
        problem(409,()->service.apply(draft.task().taskId(),new Apply(original.preview().previewId(),null),Purpose.PUBLISH,taskTag(draft),key()));
        provider.failure=Dependency.AUTHORIZATION;
        problem(503,()->service.preview(draft.task().taskId(),original.preview().previewId()));
    }
    @Test void blockedExistingResponsibilityHasOnlyOnePreviewClassification() {
        var current=ready(rules(true,0,false),false);provider.unavailableUsers=Set.of(42L);
        var preview=service.preview(current.task().taskId(),new PreviewRequest(Purpose.ADD,new Audience(List.of(),List.of("42","43"),null,null)),taskTag(current),key()).preview();
        assertEquals(2,preview.members().size());assertEquals(1,preview.blockedCount());assertEquals(0,preview.alreadyAssignedCount());assertEquals(1,preview.newAssignmentCount());
        assertTrue(!preview.canApply());
    }
    @Test void originalSubmittingAccountCannotReviewAndHasNoSideEffects() {
        provider.resolved=List.of(9L);provider.user=9;
        var current=ready(rules(false,1,false),true);var pending=material(current,"本人提交成果");
        long audits=count("learning_task_audit"),notifications=count("outbox_event");
        problem(403,()->review(pending,Decision.APPROVE));
        var unchanged=service.mine(pending.task().taskId(),pending.assignment().assignmentId());
        assertEquals(Progress.PENDING_REVIEW,unchanged.assignment().progress());assertNull(unchanged.assignment().completedAt());
        assertEquals("PENDING_REVIEW",db.queryForObject("SELECT review_status FROM learning_task_submission WHERE id=?",String.class,pending.submission().submissionId()));
        assertEquals(audits,count("learning_task_audit"));assertEquals(notifications,count("outbox_event"));
    }
    @Test void unavailableDeliveryDoesNotBreakDeadlineReviewRevocationOrCancellation() {
        var current=ready(rules(false,1,false),true);var pending=material(current,"已提交成果");provider.unavailableUsers=Set.of(42L);
        var approved=review(pending,Decision.APPROVE);assertEquals(Progress.COMPLETED,approved.assignment().progress());
        var changed=service.deadline(current.task().taskId(),new Deadline(start.plusDays(2),"调整截止"),taskTag(approved),key());
        var assignment=service.adminAssignment(current.task().taskId(),current.assignment().assignmentId());
        service.revoke(current.task().taskId(),current.assignment().assignmentId(),new Revoke("责任撤销",true),assignmentTag(assignment),key());
        assertEquals("ACCOUNT_UNAVAILABLE",provider.lastNotices.get(0).availability());
        var cancelled=service.cancel(current.task().taskId(),"停止任务",taskTag(changed),key());
        assertEquals(Status.CANCELLED,cancelled.task().status());
        provider.unavailableUsers=Set.of();var another=ready(rules(true,0,false),false);provider.unavailableUsers=Set.of(42L);
        service.cancel(another.task().taskId(),"接收账号失效后取消",taskTag(another),key());
        assertEquals("ACCOUNT_UNAVAILABLE",provider.lastNotices.get(0).availability());
        assertEquals(Long.valueOf(42),provider.lastNotices.get(0).userId());
    }
    void at(LocalDateTime t) { clock.value.set(t.atZone(ZoneId.of("Asia/Shanghai")).toInstant()); }
    String key() { return UUID.randomUUID().toString(); }
    Requirements rules(boolean confirmation, int text, boolean optionalFiles) {
        return new Requirements(confirmation,text,0,optionalFiles?1:0,optionalFiles?List.of(FileKind.PDF):List.of());
    }
    Save command(Requirements rules,boolean review) {
        return new Save("专题学习","任务说明",null,rules,review,start.plusDays(1),new Audience(List.of(),List.of("42"),null,null));
    }
    Result draft(Requirements rules,boolean review) { return service.create(command(rules,review),key()); }
    String taskTag(Result r) { return LearningTaskEtags.task(r.task()); }
    String assignmentTag(Result r) { return LearningTaskEtags.assignment(r.task(),r.assignment()); }
    String submissionTag(Result r) { return LearningTaskEtags.review(r); }
    Result publish(Result draft) {
        var p=service.preview(draft.task().taskId(),new PreviewRequest(Purpose.PUBLISH,draft.task().audience()),taskTag(draft),key());
        return service.apply(draft.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(draft),key());
    }
    Result ready(Requirements rules,boolean review) {
        at(start);
        var t=publish(draft(rules,review)); long id=db.queryForObject("SELECT id FROM learning_task_assignment WHERE task_id=?",Long.class,t.task().taskId());
        return service.mine(t.task().taskId(),id);
    }
    Result material(Result r,String text) {
        var current=service.mine(r.task().taskId(),r.assignment().assignmentId());
        return service.submit(r.task().taskId(),r.assignment().assignmentId(),new Material(text,List.of(),r.task().requirements().confirmationRequired()?true:null),assignmentTag(current),key());
    }
    Result review(Result r,Decision decision) {
        return service.review(r.task().taskId(),r.assignment().assignmentId(),r.submission().submissionId(),new Review(decision,decision==Decision.REJECT?"请补充":null),submissionTag(r),key());
    }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    ContractProblemException problem(int status,Runnable action) {
        var e=assertThrows(ContractProblemException.class,action::run); assertEquals(status,e.getStatus().value()); return e;
    }
    @Test void confirmationHasNoSubmissionAndCompletionSurvivesExceptionalRevocationAndCancellation() {
        var r=ready(rules(true,0,true),false);
        var done=service.confirm(r.task().taskId(),r.assignment().assignmentId(),assignmentTag(r),key());
        assertEquals(Progress.COMPLETED,done.assignment().progress()); assertNull(done.assignment().firstSubmittedAt());
        assertEquals(0,count("learning_task_submission")); assertNotNull(done.assignment().confirmedAt());
        problem(409,()->service.revoke(done.task().taskId(),done.assignment().assignmentId(),new Revoke("普通撤销",false),assignmentTag(done),key()));
        var revoked=service.revoke(done.task().taskId(),done.assignment().assignmentId(),new Revoke("例外撤销",true),assignmentTag(done),key());
        assertEquals(done.assignment().completedAt(),revoked.assignment().completedAt()); assertEquals("COMPLETED",revoked.assignment().displayStatus());
        var cancelled=service.cancel(done.task().taskId(),"取消任务",taskTag(done),key());
        var history=service.mine(done.task().taskId(),done.assignment().assignmentId());
        assertEquals(Status.CANCELLED,cancelled.task().status()); assertEquals("COMPLETED",history.assignment().displayStatus());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_task_audit WHERE exceptional=1",Integer.class));
    }
    @Test void combinedConfirmationCannotBypassMaterialsAndCodePointsAreNotUtf16Units() {
        var r=ready(rules(true,2,false),true);
        problem(409,()->service.confirm(r.task().taskId(),r.assignment().assignmentId(),assignmentTag(r),key()));
        problem(400,()->service.submit(r.task().taskId(),r.assignment().assignmentId(),new Material(" 😀 ",List.of(),true),assignmentTag(r),key()));
        problem(400,()->service.submit(r.task().taskId(),r.assignment().assignmentId(),new Material("\u00a0😀\u00a0",List.of(),true),assignmentTag(r),key()));
        problem(400,()->service.submit(r.task().taskId(),r.assignment().assignmentId(),new Material("学习",List.of(),null),assignmentTag(r),key()));
        assertEquals(0,count("learning_task_submission"));
        var pending=material(r," 😀学 ");
        assertEquals(Progress.PENDING_REVIEW,pending.assignment().progress()); assertNull(pending.assignment().completedAt());
        assertEquals(pending.submission().submittedAt(),pending.submission().confirmedAt());
        var done=review(pending,Decision.APPROVE); assertEquals(Progress.COMPLETED,done.assignment().progress());
        problem(409,()->material(done,"新的学习材料"));
    }
    @Test void reviewConfigurationIsValidatedAfterPatchMergeAndPublishedRequirementsAreFrozen() {
        var c=command(rules(true,0,false),true);
        problem(400,()->service.create(c,key()));
        var d=draft(rules(true,0,false),false);
        problem(400,()->service.edit(d.task().taskId(),new Edit(null,null,false,null,null,true,null,null),taskTag(d)));
        var t=publish(d);
        problem(409,()->service.edit(t.task().taskId(),new Edit(null,null,true,null,null,null,null,null),taskTag(t)));
        var changed=service.edit(t.task().taskId(),new Edit("更正标题",null,false,null,null,null,null,null),taskTag(t));
        assertEquals("更正标题",changed.task().title());
        assertEquals(t.task().requirements(),changed.task().requirements());
    }
    @Test void exactFirstDeadlineRejectsButOneMicrosecondBeforeAcceptsAndDeadlineDoesNotChangeStatus() {
        var r=ready(rules(false,1,false),false);
        at(r.task().firstDeadline().minusNanos(1000)); var done=material(r,"按时");
        assertTrue(done.assignment().onTimeFirstSubmission());
        var next=ready(rules(false,1,false),false); at(next.task().firstDeadline());
        problem(409,()->material(next,"刚好截止"));
        assertEquals(Status.PUBLISHED,service.get(next.task().taskId()).task().status());
        assertEquals("OVERDUE",service.mine(next.task().taskId(),next.assignment().assignmentId()).assignment().displayStatus());
    }
    @Test void firstRejectionGrantsFixedWindowAndRepeatedRejectionAndDeadlineChangeNeverExtendIt() {
        var r=ready(rules(true,1,false),true); var submitted=material(r,"第一次");
        at(r.task().firstDeadline().plusHours(1)); var rejected=review(submitted,Decision.REJECT);
        LocalDateTime fixed=start.plusDays(1).plusHours(73);
        assertEquals(fixed,rejected.assignment().supplementDeadline());
        assertNull(rejected.assignment().completedAt()); assertEquals("RESUBMISSION_REQUIRED",rejected.assignment().displayStatus());
        problem(409,()->service.close(r.task().taskId(),taskTag(r),key()));
        var changed=service.deadline(r.task().taskId(),new Deadline(fixed.plusDays(3),"首次截止调整"),taskTag(r),key());
        var second=material(rejected,"第二次");
        assertEquals(2,second.submission().attempt()); assertEquals(submitted.assignment().firstSubmittedAt(),second.assignment().firstSubmittedAt());
        assertEquals(submitted.assignment().confirmedAt(),second.assignment().confirmedAt());
        at(fixed.plusHours(1)); var twice=review(second,Decision.REJECT);
        assertEquals(fixed,twice.assignment().supplementDeadline());
        problem(409,()->material(twice,"第三次"));
        assertEquals(2,service.history(r.task().taskId(),r.assignment().assignmentId()).size());
        assertEquals(ReviewStatus.REJECTED,service.history(r.task().taskId(),r.assignment().assignmentId()).get(0).reviewStatus());
        at(changed.task().firstDeadline()); assertEquals(Status.CLOSED,service.close(r.task().taskId(),taskTag(changed),key()).task().status());
    }
    @Test void rejectionBeforeFirstDeadlineUsesLaterPublishedDeadlineAndBoundaryIsExclusive() {
        var r=ready(rules(false,1,false),true);
        var extended=service.deadline(r.task().taskId(),new Deadline(start.plusDays(7),"延长"),taskTag(r),key());
        var submitted=material(r,"材料"); var rejected=review(submitted,Decision.REJECT);
        assertEquals(start.plusDays(7),rejected.assignment().supplementDeadline());
        at(start.plusDays(7)); problem(409,()->material(rejected,"补交"));
        assertEquals(Status.CLOSED,service.close(r.task().taskId(),taskTag(extended),key()).task().status());
    }
    @Test void candidateSafetyIsOutsideTransactionAndWriteRechecksAuthorizationAndVersion() {
        provider.safetyHook=()-> {
            org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            provider.failure=Dependency.AUTHORIZATION;
        };
        problem(503,()->draft(rules(true,0,false),false));
        assertEquals(0,count("learning_task"));
        provider.failure=null; provider.safetyHook=null;
        var original=draft(rules(true,0,false),false);
        provider.safetyHook=()-> {
            org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            provider.safetyHook=null;
            service.edit(original.task().taskId(),new Edit("other writer",null,false,null,null,null,null,null),taskTag(original));
        };
        problem(412,()->service.edit(original.task().taskId(),new Edit("stale candidate",null,false,null,null,null,null,null),taskTag(original)));
        assertEquals("other writer",service.get(original.task().taskId()).task().title());
    }
    @Test void submissionSafetyRunsOutsideLocksAndRechecksDeadlineAndAccount() {
        var r=ready(rules(false,1,false),false);
        provider.safetyHook=()-> {
            org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            at(r.task().firstDeadline());
        };
        problem(409,()->service.submit(r.task().taskId(),r.assignment().assignmentId(),new Material("候选文字",List.of(),null),assignmentTag(r),key()));
        assertEquals(0,count("learning_task_submission"));
        at(start); provider.safetyHook=()->provider.failure=Dependency.AUTHORIZATION;
        problem(503,()->service.submit(r.task().taskId(),r.assignment().assignmentId(),new Material("候选文字",List.of(),null),assignmentTag(r),key()));
        provider.failure=null; provider.safetyHook=null;
        assertNull(service.mine(r.task().taskId(),r.assignment().assignmentId()).assignment().completedAt());
    }
    @Test void submissionCandidateCannotPublishAfterTaskChangesDuringRemoteSafety() {
        var r=ready(rules(false,1,false),false);
        provider.safetyHook=()-> {
            org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            service.cancel(r.task().taskId(),"安全检查期间取消",taskTag(r),key());
        };
        problem(412,()->service.submit(r.task().taskId(),r.assignment().assignmentId(),new Material("候选文字",List.of(),null),assignmentTag(r),key()));
        assertEquals(0,count("learning_task_submission"));
        assertNull(service.mine(r.task().taskId(),r.assignment().assignmentId()).assignment().completedAt());
    }
    @Test void previewClassificationRemainsFrozenAfterApplyAndExpiry() {
        var draft=draft(rules(true,0,false),false);
        var preview=service.preview(draft.task().taskId(),new PreviewRequest(Purpose.PUBLISH,draft.task().audience()),taskTag(draft),key());
        service.apply(draft.task().taskId(),new Apply(preview.preview().previewId(),null),Purpose.PUBLISH,taskTag(draft),key());
        assertEquals(1,service.preview(draft.task().taskId(),preview.preview().previewId()).preview().newAssignmentCount());
        org.junit.jupiter.api.Assertions.assertFalse(service.previewMembers(draft.task().taskId(),preview.preview().previewId(),1,20).items().get(0).alreadyAssigned());
        // V66 made the frozen subject members authoritative; legacy V44 user-only classification columns were retired.
        db.update("UPDATE learning_task_preview SET new_assignment_count=NULL,already_assigned_count=NULL WHERE id=?",preview.preview().previewId());
        at(start.plusMinutes(10));
        assertEquals(1,service.preview(draft.task().taskId(),preview.preview().previewId()).preview().newAssignmentCount());
        assertEquals(1,service.previewMembers(draft.task().taskId(),preview.preview().previewId(),1,20).totalItems());
    }
    @Test void closeWaitsForPendingReviewAfterDeadlineAndTerminalTasksCannotReopen() {
        var r=ready(rules(false,1,false),true); var pending=material(r,"材料"); at(r.task().firstDeadline());
        problem(409,()->service.close(r.task().taskId(),taskTag(r),key()));
        assertEquals("PENDING_REVIEW",service.mine(r.task().taskId(),r.assignment().assignmentId()).assignment().displayStatus());
        review(pending,Decision.APPROVE);
        var closed=service.close(r.task().taskId(),taskTag(r),key());
        problem(409,()->service.cancel(r.task().taskId(),"不能取消",taskTag(closed),key()));
        problem(409,()->service.deadline(r.task().taskId(),new Deadline(start.plusDays(9),"不能重开"),taskTag(closed),key()));
    }
    @Test void sameKeyReplaysOriginalSnapshotBeforeOldEtagButConflictingBodyAndOtherOwnerFail() {
        var r=ready(rules(false,1,false),true); String key=key(); Material c=new Material("原成果",List.of(),null);
        var first=service.submit(r.task().taskId(),r.assignment().assignmentId(),c,assignmentTag(r),key);
        var reviewed=review(first,Decision.APPROVE);
        assertEquals(first,service.submit(r.task().taskId(),r.assignment().assignmentId(),c,"invalid old etag",key));
        assertEquals(Progress.COMPLETED,service.mine(r.task().taskId(),r.assignment().assignmentId()).assignment().progress());
        problem(409,()->service.submit(r.task().taskId(),r.assignment().assignmentId(),new Material("换正文",List.of(),null),assignmentTag(reviewed),key));
        provider.user=43; problem(404,()->service.submit(r.task().taskId(),r.assignment().assignmentId(),c,assignmentTag(r),key));
        assertEquals(1,count("learning_task_submission"));
    }
    @Test void confirmIsIdempotentAndFreshRepeatedCompletionCannotAddFacts() {
        var r=ready(rules(true,0,false),false); String key=key();
        var done=service.confirm(r.task().taskId(),r.assignment().assignmentId(),assignmentTag(r),key);
        assertEquals(done,service.confirm(r.task().taskId(),r.assignment().assignmentId(),assignmentTag(r),key));
        problem(409,()->service.confirm(r.task().taskId(),r.assignment().assignmentId(),assignmentTag(done),key()));
        assertEquals(0,count("learning_task_submission"));
    }
    @Test void twoConcurrentPublishesHaveOneWinnerAndOneAssignmentNotification() throws Exception {
        var d=draft(rules(true,0,false),false);
        var p=service.preview(d.task().taskId(),new PreviewRequest(Purpose.PUBLISH,d.task().audience()),taskTag(d),key());
        var results=race(()->service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),key()),
                ()->service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),key()));
        assertEquals(List.of(200,412),results); assertEquals(1,count("learning_task_assignment"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic='learning-task.test'",Integer.class));
    }
    @Test void concurrentSubmissionsDifferentKeysHaveOnePendingAndSameKeyReplays() throws Exception {
        var r=ready(rules(false,1,false),true);
        assertEquals(List.of(200,412),race(()->material(r,"A"),()->material(r,"B")));
        assertEquals(1,count("learning_task_submission"));
        var another=ready(rules(false,1,false),true); String key=key(); var body=new Material("相同",List.of(),null);
        assertEquals(List.of(200,200),race(()->service.submit(another.task().taskId(),another.assignment().assignmentId(),body,assignmentTag(another),key),
                ()->service.submit(another.task().taskId(),another.assignment().assignmentId(),body,assignmentTag(another),key)));
        assertEquals(2,count("learning_task_submission"));
    }
    @Test void concurrentReviewersAndRepeatedReviewPreserveExactlyOneDecision() throws Exception {
        var pending=material(ready(rules(false,1,false),true),"材料");
        assertEquals(List.of(200,412),race(()->review(pending,Decision.APPROVE),()->review(pending,Decision.REJECT)));
        var current=service.history(pending.task().taskId(),pending.assignment().assignmentId()).get(0);
        String latest=LearningTaskEtags.review(service.adminSubmission(current.submissionId()));
        problem(409,()->service.review(pending.task().taskId(),pending.assignment().assignmentId(),current.submissionId(),new Review(Decision.APPROVE,null),latest,key()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_task_audit WHERE operation='reviewLearningTaskSubmission'",Integer.class));
    }
    @Test void reviewAndCancelSerializeWithoutRewritingCancelledPendingHistory() throws Exception {
        for(int n=0;n<8;n++) {
            var pending=material(ready(rules(false,1,false),true),"材料");
            List<Integer> results=race(()->review(pending,Decision.APPROVE),
                    ()->service.cancel(pending.task().taskId(),"取消",taskTag(pending),key()));
            assertTrue(results.equals(List.of(200,200))||results.equals(List.of(200,412)));
            var mine=service.mine(pending.task().taskId(),pending.assignment().assignmentId());
            assertEquals(Status.CANCELLED,mine.task().status());
            var s=service.history(pending.task().taskId(),pending.assignment().assignmentId()).get(0);
            assertEquals(s.reviewStatus()==ReviewStatus.APPROVED?Progress.COMPLETED:Progress.PENDING_REVIEW,mine.assignment().progress());
        }
    }
    @Test void ordinaryRevocationAndSubmissionCompeteOnTheSameParentLock() throws Exception {
        for(int n=0;n<8;n++) {
            var r=ready(rules(false,1,false),true);
            List<Integer> result=race(()->material(r,"提交"),()->service.revoke(r.task().taskId(),r.assignment().assignmentId(),new Revoke("撤销",false),assignmentTag(r),key()));
            assertTrue(result.equals(List.of(200,409))||result.equals(List.of(200,412)));
            var a=service.mine(r.task().taskId(),r.assignment().assignmentId()).assignment();
            if(a.allocationStatus()==Allocation.REVOKED) { assertNull(a.firstSubmittedAt()); }
            else { assertEquals(Progress.PENDING_REVIEW,a.progress()); }
        }
    }
    @Test void revokedUserIsSkippedByAddAndNoNewAssignmentOrNotificationIsCreated() {
        var r=ready(rules(true,0,false),false);
        service.revoke(r.task().taskId(),r.assignment().assignmentId(),new Revoke("撤销",false),assignmentTag(r),key());
        var p=service.preview(r.task().taskId(),new PreviewRequest(Purpose.ADD,new Audience(List.of(),List.of("42","43"),null,null)),taskTag(r),key());
        assertEquals(1,p.preview().alreadyAssignedCount()); assertEquals(1,p.preview().newAssignmentCount());
        var added=service.apply(r.task().taskId(),new Apply(p.preview().previewId(),"追加"),Purpose.ADD,taskTag(r),key());
        assertEquals(1,added.addedCount()); assertEquals(1,added.alreadyAssignedCount());
        assertEquals(Allocation.REVOKED,service.mine(r.task().taskId(),r.assignment().assignmentId()).assignment().allocationStatus());
        var zero=service.preview(r.task().taskId(),new PreviewRequest(Purpose.ADD,new Audience(List.of(),List.of("42"),null,null)),taskTag(added),key());
        assertEquals("/problems/learning-task-no-recipients",problem(409,()->service.apply(r.task().taskId(),new Apply(zero.preview().previewId(),"追加"),Purpose.ADD,taskTag(added),key())).getType());
        assertEquals(2,count("learning_task_assignment"));
    }
    @Test void previewExpiresAtFiveMinutesAndMembershipOrTaskVersionChangesInvalidateIt() {
        var d=draft(rules(true,0,false),false);
        var p=service.preview(d.task().taskId(),new PreviewRequest(Purpose.PUBLISH,d.task().audience()),taskTag(d),key());
        at(start.plusMinutes(5));
        problem(409,()->service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),key()));
        at(start);
        provider.resolved=List.of(43L);
        problem(409,()->service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),key()));
        provider.resolved=null;
        var changed=service.edit(d.task().taskId(),new Edit("新标题",null,false,null,null,null,null,null),taskTag(d));
        problem(409,()->service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(changed),key()));
        assertEquals(0,count("learning_task_assignment"));
    }
    @Test void missingProvidersAndNotificationFailureRollBackAllBusinessAndRequestFacts() {
        var d=draft(rules(false,1,true),true);
        provider.failure=Dependency.PERSON_RESOLVER;
        problem(503,()->service.preview(d.task().taskId(),new PreviewRequest(Purpose.PUBLISH,d.task().audience()),taskTag(d),key()));
        assertEquals(0,count("learning_task_preview")); provider.failure=null;
        var p=service.preview(d.task().taskId(),new PreviewRequest(Purpose.PUBLISH,d.task().audience()),taskTag(d),key());
        provider.failure=Dependency.NOTIFICATION_ACCEPTANCE; String request=key();
        problem(503,()->service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),request));
        assertEquals(Status.DRAFT,service.get(d.task().taskId()).task().status()); assertEquals(0,count("learning_task_assignment"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic='learning-task.test'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM learning_task_request WHERE request_key=?",Integer.class,request));
        provider.failure=null;
        service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),request);
        long assignment=db.queryForObject("SELECT id FROM learning_task_assignment WHERE task_id=?",Long.class,d.task().taskId());
        var r=service.mine(d.task().taskId(),assignment);
        problem(503,()->service.submit(d.task().taskId(),assignment,new Material("附件",List.of(999L),null),assignmentTag(r),key()));
        assertEquals(0,count("learning_task_submission")); assertNull(service.mine(d.task().taskId(),assignment).assignment().latestSubmissionId());
        provider.failure=Dependency.CONTENT_SAFETY; problem(503,()->material(r,"安全检查不可用"));
        assertEquals(0,count("learning_task_submission"));
    }
    @Test void rollbackAfterReviewAndCancellationRetainsOriginalFactsAndAllowsSameKeyRecovery() {
        var pending=material(ready(rules(false,1,false),true),"成果"); provider.failure=Dependency.NOTIFICATION_ACCEPTANCE;
        String request=key(); problem(503,()->service.review(pending.task().taskId(),pending.assignment().assignmentId(),pending.submission().submissionId(),
                new Review(Decision.REJECT,"补充"),submissionTag(pending),request));
        assertEquals(Progress.PENDING_REVIEW,service.mine(pending.task().taskId(),pending.assignment().assignmentId()).assignment().progress());
        assertNull(service.mine(pending.task().taskId(),pending.assignment().assignmentId()).assignment().supplementDeadline());
        problem(503,()->service.cancel(pending.task().taskId(),"取消",taskTag(pending),key()));
        assertEquals(Status.PUBLISHED,service.get(pending.task().taskId()).task().status());
        provider.failure=null;
        var rejected=service.review(pending.task().taskId(),pending.assignment().assignmentId(),pending.submission().submissionId(),
                new Review(Decision.REJECT,"补充"),submissionTag(pending),request);
        assertEquals(Progress.REJECTED,rejected.assignment().progress());
    }
    @Test void publicationFailsBeforeAssignmentsWhenOptionalPrivateFilesOrTextSafetyAreUnavailable() {
        var d=draft(rules(true,0,true),false);
        var p=service.preview(d.task().taskId(),new PreviewRequest(Purpose.PUBLISH,d.task().audience()),taskTag(d),key());
        provider.failure=Dependency.PRIVATE_MEDIA;
        assertEquals("PRIVATE_MEDIA",problem(503,()->service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),key())).getMessage());
        assertEquals(0,count("learning_task_assignment")); assertEquals(Status.DRAFT,service.get(d.task().taskId()).task().status());
        provider.failure=null;
        var text=draft(rules(false,1,false),true);
        var tp=service.preview(text.task().taskId(),new PreviewRequest(Purpose.PUBLISH,text.task().audience()),taskTag(text),key());
        provider.failure=Dependency.CONTENT_SAFETY;
        problem(503,()->service.apply(text.task().taskId(),new Apply(tp.preview().previewId(),null),Purpose.PUBLISH,taskTag(text),key()));
        assertEquals(0,count("learning_task_assignment"));
        provider.failure=Dependency.AUTHORIZATION;
        problem(503,()->service.get(d.task().taskId()));
    }
    @Test void firstDeadlineExtensionReopensOnlyFirstSubmissionAndNeverRecomputesAcceptedFacts() {
        var r=ready(rules(false,1,false),false);
        at(r.task().firstDeadline()); problem(409,()->material(r,"已截止"));
        var changed=service.deadline(r.task().taskId(),new Deadline(start.plusDays(2),"重新开放"),taskTag(r),key());
        var completed=material(r,"重开后的首次提交");
        assertTrue(completed.assignment().onTimeFirstSubmission());
        var submittedAt=completed.assignment().firstSubmittedAt();
        service.deadline(r.task().taskId(),new Deadline(start.plusDays(3),"再次延长"),taskTag(changed),key());
        var actual=service.mine(r.task().taskId(),r.assignment().assignmentId());
        assertEquals(submittedAt,actual.assignment().firstSubmittedAt()); assertTrue(actual.assignment().onTimeFirstSubmission());
    }
    @Test void twoConcurrentConfirmationsAndRepeatedSameReviewKeyDoNotDuplicateFacts() throws Exception {
        var r=ready(rules(true,0,false),false);
        assertEquals(List.of(200,412),race(()->service.confirm(r.task().taskId(),r.assignment().assignmentId(),assignmentTag(r),key()),
                ()->service.confirm(r.task().taskId(),r.assignment().assignmentId(),assignmentTag(r),key())));
        assertEquals(0,count("learning_task_submission"));
        var pending=material(ready(rules(false,1,false),true),"成果"); String key=key(); Review decision=new Review(Decision.APPROVE,null);
        var approved=service.review(pending.task().taskId(),pending.assignment().assignmentId(),pending.submission().submissionId(),decision,submissionTag(pending),key);
        assertEquals(approved,service.review(pending.task().taskId(),pending.assignment().assignmentId(),pending.submission().submissionId(),decision,"stale",key));
        problem(409,()->service.review(pending.task().taskId(),pending.assignment().assignmentId(),pending.submission().submissionId(),new Review(Decision.REJECT,"更改审核"),submissionTag(approved),key));
    }
    @Test void cancellationLeavesPendingReviewUntouchedAndRejectsFurtherDecisionsAndSubmissions() {
        var pending=material(ready(rules(true,1,false),true),"待审成果");
        service.cancel(pending.task().taskId(),"取消",taskTag(pending),key());
        problem(412,()->review(pending,Decision.REJECT));
        problem(409,()->review(service.adminSubmission(pending.submission().submissionId()),Decision.REJECT));
        var after=service.mine(pending.task().taskId(),pending.assignment().assignmentId());
        assertEquals(Progress.PENDING_REVIEW,after.assignment().progress()); assertEquals("CANCELLED",after.assignment().displayStatus());
        assertEquals(ReviewStatus.PENDING_REVIEW,service.history(pending.task().taskId(),pending.assignment().assignmentId()).get(0).reviewStatus());
        problem(409,()->material(after,"不再受理")); assertNull(after.assignment().supplementDeadline());
    }
    @Test void zeroPeoplePreviewDoesNotPublishAndSubmissionRelationshipCannotBeSubstituted() {
        var d=draft(rules(true,0,false),false); provider.resolved=List.of();
        var p=service.preview(d.task().taskId(),new PreviewRequest(Purpose.PUBLISH,d.task().audience()),taskTag(d),key());
        assertEquals(0,p.preview().newAssignmentCount());
        problem(409,()->service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),key()));
        assertEquals(Status.DRAFT,service.get(d.task().taskId()).task().status()); provider.resolved=null;
        var first=material(ready(rules(false,1,false),true),"第一任务");
        var second=ready(rules(false,1,false),true);
        problem(404,()->service.review(second.task().taskId(),second.assignment().assignmentId(),first.submission().submissionId(),
                new Review(Decision.APPROVE,null),submissionTag(first),key()));
        problem(404,()->service.mine(second.task().taskId(),first.assignment().assignmentId()));
    }
    @Test void expiredDraftCanBePreviewedButCannotPublishWithPastDeadlineAndTerminalWritesStayRejected() {
        var d=draft(rules(true,0,false),false); at(d.task().firstDeadline());
        var p=service.preview(d.task().taskId(),new PreviewRequest(Purpose.PUBLISH,d.task().audience()),taskTag(d),key());
        problem(400,()->service.apply(d.task().taskId(),new Apply(p.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),key()));
        assertEquals(0,count("learning_task_assignment"));
        var r=ready(rules(true,0,false),false);
        var cancelled=service.cancel(r.task().taskId(),"取消",taskTag(r),key());
        problem(409,()->service.edit(r.task().taskId(),new Edit("不能编辑",null,false,null,null,null,null,null),taskTag(cancelled)));
        problem(409,()->service.preview(r.task().taskId(),new PreviewRequest(Purpose.ADD,new Audience(List.of(),List.of("43"),null,null)),taskTag(cancelled),key()));
    }
    @Test void realDatabaseUniquenessAndForeignKeysRejectBypassWrites() throws Exception {
        var r=ready(rules(false,1,false),true);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update(
                "INSERT INTO learning_task_assignment(task_id,user_id,assigned_at,assigned_by) VALUES(?,?,?,9)",r.task().taskId(),42,start));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update(
                "INSERT INTO learning_task_assignment(task_id,user_id,assigned_at,assigned_by) VALUES(?,?,?,9)",r.task().taskId(),999999,start));
        var pending=material(r,"材料");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update(
                "INSERT INTO learning_task_submission(task_id,assignment_id,attempt,text,file_ids,submitted_at,review_status,submitted_by_user_id,responsibility_key) VALUES(?,?,2,'duplicate',JSON_ARRAY(),?,'PENDING_REVIEW',42,'ACCOUNT:42')",
                r.task().taskId(),r.assignment().assignmentId(),start));
        var d=draft(rules(true,0,false),false); // two independent connections race on unique task/user.
        assertEquals(List.of(200,409),race(()->directAssignment(d.task().taskId()),()->directAssignment(d.task().taskId())));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_task_assignment WHERE task_id=?",Integer.class,d.task().taskId()));
        assertNotNull(pending.submission());
    }
    Result directAssignment(long taskId) {
        try {
            db.update("INSERT INTO learning_task_assignment(task_id,user_id,assigned_at,assigned_by) VALUES(?,43,?,9)",taskId,start); return null;
        } catch(org.springframework.dao.DuplicateKeyException e) { throw conflict("state-conflict"); }
    }
    @Test void preconditionsAndNoOpDeadlineAndLegacyFixtureArePreserved() {
        var r=ready(rules(true,0,false),false);
        problem(428,()->service.confirm(r.task().taskId(),r.assignment().assignmentId(),null,key()));
        problem(400,()->service.confirm(r.task().taskId(),r.assignment().assignmentId(),"W/"+assignmentTag(r),key()));
        problem(412,()->service.confirm(r.task().taskId(),r.assignment().assignmentId(),"\"old\"",key()));
        int outbox=db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic='learning-task.test'",Integer.class);
        var same=service.deadline(r.task().taskId(),new Deadline(r.task().firstDeadline(),"未变化"),taskTag(r),key());
        assertEquals(r.task().revision(),same.task().revision());
        assertEquals(outbox,db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic='learning-task.test'",Integer.class));
        assertEquals("preserve-before-v38",db.queryForObject("SELECT content FROM post WHERE title='legacy-learning-fixture'",String.class));
    }
    @Test void lockWaitAcrossDeadlineSamplesTimeOnlyAfterParentLock() throws Exception {
        var r=ready(rules(false,1,false),true);
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> blocker=pool.submit(()->tx.executeWithoutResult(s->{
                db.queryForObject("SELECT id FROM learning_task WHERE id=? FOR UPDATE",Long.class,r.task().taskId());
                locked.countDown(); try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            Future<Integer> submit=pool.submit(()->status(()->material(r,"跨截止等待")));
            at(r.task().firstDeadline()); release.countDown(); blocker.get(10,TimeUnit.SECONDS);
            assertEquals(409,submit.get(10,TimeUnit.SECONDS)); assertEquals(0,count("learning_task_submission"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void previewBindsFullArticleAvailabilityAndLegacyNullBindingIsNeverApplied() {
        var d=draft(rules(true,0,false),false);
        var old=service.preview(d.task().taskId(),new PreviewRequest(Purpose.PUBLISH,d.task().audience()),taskTag(d),key());
        db.update("UPDATE learning_task_preview SET task_etag=NULL WHERE id=?",old.preview().previewId());
        problem(409,()->service.apply(d.task().taskId(),new Apply(old.preview().previewId(),null),Purpose.PUBLISH,taskTag(d),key()));
        db.update("INSERT INTO article_publisher(id,display_name,updated_by,updated_at) VALUES(1,'lt-domain-publisher',9,?) ON DUPLICATE KEY UPDATE display_name=VALUES(display_name)",start);
        db.update("INSERT INTO article(title,summary,body,plain_text,publisher_id,publish_status,created_by,updated_by,created_at,updated_at) VALUES('lt-domain-article','',JSON_OBJECT(),'',1,'DRAFT',9,9,?,?)",start,start);
        long article=db.queryForObject("SELECT MAX(id) FROM article WHERE title='lt-domain-article'",Long.class);
        db.update("UPDATE learning_task SET article_id=? WHERE id=?",article,d.task().taskId());
        var unavailable=service.get(d.task().taskId());
        var preview=service.preview(d.task().taskId(),new PreviewRequest(Purpose.PUBLISH,d.task().audience()),taskTag(unavailable),key());
        provider.publicArticles=Set.of(article);
        var available=service.get(d.task().taskId());
        assertTrue(!taskTag(unavailable).equals(taskTag(available)));
        problem(409,()->service.apply(d.task().taskId(),new Apply(preview.preview().previewId(),null),Purpose.PUBLISH,taskTag(available),key()));
        assertEquals(0,count("learning_task_assignment"));
    }
    List<Integer> race(Supplier<Result> a,Supplier<Result> b) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Integer> one=pool.submit(()->{ start.await(); return status(a); });
            Future<Integer> two=pool.submit(()->{ start.await(); return status(b); });
            start.countDown(); var results=new ArrayList<>(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))); Collections.sort(results); return results;
        } finally { pool.shutdownNow(); }
    }
    int status(Supplier<Result> work) { try { work.get(); return 200; } catch(ContractProblemException e) { return e.getStatus().value(); } }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value.get(); }
    }
    /** TEST ONLY. Does not claim personnel, authorization, private storage or notification integration. */
    static class TestProviders implements LearningTaskProviders {
        interface FileBinding {void bind(long user,long assignment,long submission,List<Long> files,Requirements requirements);}
        volatile FileBinding fileBinding;
        final JdbcTemplate db;
        volatile Dependency failure;
        volatile List<Long> resolved;
        volatile long user=42;
        volatile Set<Long> publicArticles=Set.of();
        volatile Set<Long> unavailableUsers=Set.of();
        volatile List<Notice> lastNotices=List.of();
        volatile Runnable safetyHook;
        TestProviders(JdbcTemplate db) { this.db=db; }
        // This fixture proves the old state machine; current scope/provider checks use separate full application tests.
        public cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope reviewableTasks(){return readableTasks();}
        public void creationTarget(long actor,cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target target){}
        public String audienceVersion(long actor,long task) {return "explicit-synthetic-account-fixture";}
        public String own(long actor,cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject subject) {if(!"ACCOUNT".equals(subject.type()) || !Long.toString(actor).equals(subject.userId())) throw cn.jualn.miniapp.module.learningtask.LearningTaskProblems.missing();return null;}
        public cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient recipient(cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject subject) {long user=Long.parseLong(subject.userId());return new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient(subject,user,null,unavailableUsers.contains(user)?"ACCOUNT_UNAVAILABLE":"AVAILABLE");}
        public List<cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient> resolve(long actor,long task,Audience audience){return resolve(actor,audience);}
        public void finish(long actor,boolean user){}
        public long authorize(Action action,Long task,Long owner) {
            fail(Dependency.AUTHORIZATION);
            return action==Action.MY_READ||action==Action.SUBMIT||action==Action.CONFIRM?user:9;
        }
        public List<cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient> resolve(long actor,Audience audience) { fail(Dependency.PERSON_RESOLVER); return (resolved==null?audience.accountUserIds().stream().map(Long::valueOf).toList():resolved).stream().map(user->recipient(cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject.account(user))).toList(); }
        public cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope readableTasks() { return new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope(db.queryForList("SELECT id FROM learning_task WHERE created_by=9",Long.class)); }
        public java.util.Set<Long> publiclyVisibleArticles(java.util.Set<Long> ids) { return publicArticles; }
        public void article(long actor,long article) { throw conflict("resource-unavailable"); }
        public void textSafety(long actor,String text) { if(safetyHook!=null) { safetyHook.run(); } fail(Dependency.CONTENT_SAFETY); }
        public void publicationCapabilities(Requirements requirements) {
            if(requirements.maximumFiles()>0) { fail(Dependency.PRIVATE_MEDIA); }
            if(requirements.minimumTextLength()>0) { fail(Dependency.CONTENT_SAFETY); }
        }
        public void bindPrivateFiles(long user,long assignment,long submission,List<Long> files,Requirements r) {if(fileBinding!=null) {fileBinding.bind(user,assignment,submission,files,r);return;} throw unavailable(Dependency.PRIVATE_MEDIA); }
        public void accept(List<Notice> notices) {
            lastNotices=List.copyOf(notices);
            for(Notice n:notices) { db.update(
                "INSERT INTO outbox_event(message_id,topic,schema_version,aggregate_type,aggregate_id,payload,status,next_attempt_at) VALUES(?,'learning-task.test',1,'LEARNING_TASK',?,JSON_OBJECT('occurrence',?,'type',?),'PENDING',?)",
                UUID.randomUUID().toString().replace("-",""),Long.toString(n.taskId()),n.occurrence(),n.type(),LocalDateTime.of(2026,10,14,10,0)); }
            fail(Dependency.NOTIFICATION_ACCEPTANCE); // deliberate failure AFTER local durable writes.
        }
        public void reconcile(long task,LocalDateTime now) { fail(Dependency.NOTIFICATION_ACCEPTANCE); }
        void fail(Dependency d) { if(failure==d) { throw unavailable(d); } }
    }
    @TestConfiguration @EnableTransactionManagement
    @MapperScan("cn.jualn.miniapp.module.learningtask.mapper")
    @Import(LearningTaskServiceImpl.class)
    static class Config {
        @Bean DataSource dataSource() {
            String url=System.getProperty("event.test.jdbcUrl");
            if(url==null||!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/learning_task_test")) { throw new IllegalArgumentException("Disposable LearningTask DB only"); }
            return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean(name="learningTaskClock") MutableClock clock() { return new MutableClock(); }
        @Bean TestProviders providers(JdbcTemplate db) { return new TestProviders(db); }
        @Bean cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal adminAudit() {return org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class);}
        @Bean cn.jualn.miniapp.module.learningtask.service.LearningTaskSubmitterSummaryReader summaries() {
            var summaries=org.mockito.Mockito.mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskSubmitterSummaryReader.class);
            org.mockito.Mockito.when(summaries.read(org.mockito.ArgumentMatchers.any())).thenReturn(new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.SubmitterSummary("UNAVAILABLE",null));return summaries;
        }
        @Bean SqlSessionFactory sessions(DataSource ds) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); factory.setConfiguration(config);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/Learning*.xml"));
            return factory.getObject();
        }
    }
}
