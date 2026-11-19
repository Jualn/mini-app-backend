package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.authorization.service.BusinessAuthorizationLease;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.dev33.satoken.SaManager;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.config.MybatisPlusConfig;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.infrastructure.async.outbox.*;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.bo.*;
import cn.jualn.miniapp.module.audit.converter.AdminReviewConverter;
import cn.jualn.miniapp.module.audit.entity.*;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.*;
import cn.jualn.miniapp.module.audit.service.impl.*;
import cn.jualn.miniapp.module.post.service.*;
import cn.jualn.miniapp.module.post.service.impl.PostServiceImpl;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.comment.service.*;
import cn.jualn.miniapp.module.comment.service.impl.*;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/** Real MySQL/Spring/MyBatis/domain writes; identities, permission publication, Redis and notifications are synthetic adapters. */
@EnabledIfSystemProperty(named="safety.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/content_safety_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentSafetyReviewDatabaseTest {
    AnnotationConfigApplicationContext context; JdbcTemplate jdbc; ContentSafetyReviewService rounds;
    AuditReservationService reservations; AuditResultPersistenceService results; AdminReviewService admin;
    ContentAuditLogMapper logs; ContentSafetyReviewMapper rows; TransactionTemplate tx;
    static final AtomicLong ids=new AtomicLong(1000);
    static final AtomicBoolean failEffect=new AtomicBoolean(), revokeAtCommit=new AtomicBoolean();
    static final Map<Long,Decision> decisions=new ConcurrentHashMap<>();
    static DataSource source(String url) {return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true","root","");}
    @BeforeAll void migrate() {
        String url=System.getProperty("safety.test.jdbcUrl");var source=source(url);var sql=new JdbcTemplate(source);
        assertEquals(System.getProperty("safety.test.serverUuid"),sql.queryForObject("SELECT @@server_uuid",String.class));
        if(!Boolean.getBoolean("safety.test.reuseOwnedSchema")) assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class),"Requires fresh owned schema or explicit reuse of this suite's own fixture");
        var flyway=Flyway.configure().dataSource(source).load();flyway.migrate();flyway.validate();
        var upgrade=source(url.replace("content_safety_test","content_safety_upgrade_test"));var old=new JdbcTemplate(upgrade);
        if(!Boolean.getBoolean("safety.test.reuseOwnedSchema")) {
            assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
            Flyway.configure().dataSource(upgrade).target("70").load().migrate();
            old.update("INSERT INTO content_audit_log(id,target_type,target_id,audit_source,wx_result,wx_detail,final_result) VALUES(900,1,500,1,1,?,2)","{\"result\":{\"suggest\":\"risky\"}}");
            old.update("INSERT INTO content_audit_log(target_type,target_id,audit_source,admin_user_id,admin_action,admin_remark,idempotency_key,final_result) VALUES(1,500,2,9,1,'retained','legacy-success-key',1)");
        }
        var latest=Flyway.configure().dataSource(upgrade).load();latest.migrate();latest.validate();assertEquals(0,latest.migrate().migrationsExecuted);
        assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM content_safety_review",Integer.class));
        assertEquals("risky",old.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(wx_detail,'$.result.suggest')) FROM content_audit_log WHERE id=900",String.class));
        assertEquals("legacy-success-key",old.queryForObject("SELECT idempotency_key FROM content_audit_log WHERE audit_source=2",String.class));
        context=new AnnotationConfigApplicationContext(Config.class);jdbc=context.getBean(JdbcTemplate.class);
        rounds=context.getBean(ContentSafetyReviewService.class);reservations=context.getBean(AuditReservationService.class);results=context.getBean(AuditResultPersistenceService.class);admin=context.getBean(AdminReviewService.class);
        logs=context.getBean(ContentAuditLogMapper.class);rows=context.getBean(ContentSafetyReviewMapper.class);
        tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        jdbc.update("INSERT INTO user_profile(id,openid,nickname) VALUES(1,'synthetic-owner','Owner'),(2,'synthetic-commenter','Commenter') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)");
    }
    @AfterAll void close() {if(context!=null) context.close();}
    @BeforeEach void setup() {
        jdbc.update("UPDATE content_audit_log SET review_task_id=NULL");jdbc.update("UPDATE content_safety_check SET supersedes_check_id=NULL");jdbc.update("DELETE FROM content_safety_check");jdbc.update("UPDATE content_safety_review SET previous_task_id=NULL,superseded_by_task_id=NULL");jdbc.update("DELETE FROM content_safety_review");jdbc.update("DELETE FROM content_audit_log");jdbc.update("DELETE FROM async_job");jdbc.update("DELETE FROM outbox_event");jdbc.update("DELETE FROM comment");jdbc.update("DELETE FROM post");jdbc.update("DELETE FROM public_event");
        failEffect.set(false);revokeAtCommit.set(false);decisions.clear();grant(9,"POST");grant(10,"POST");
        var storage=mock(cn.dev33.satoken.context.model.SaStorage.class);var values=new HashMap<String,Object>();
        when(storage.get(anyString())).thenAnswer(i->values.get(i.getArgument(0)));
        when(storage.set(anyString(),any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});
        SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);AdminStpUtil.STP_LOGIC.switchTo(9L);
    }
    @AfterEach void clear() {UserContext.clear();SaManager.getSaTokenContext().clearContext();}
    static void grant(long actor,String space) {decisions.put(actor,new Decision(actor,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(new Grant("source-"+actor,"CONTENT_REVIEWER",space,"GLOBAL",List.of("review:read","review:decide"),null,ContentSafetyAuthorization.duty(space),new UnifiedModels.Target("PLATFORM",null,null)))));}
    long post() {long id=ids.incrementAndGet();jdbc.update("INSERT INTO post(id,user_id,title,content,status,audit_status) VALUES(?,1,'Title','Original content',2,0)",id);return id;}
    AuditReserveResultBO reserve(long id,boolean media) {
        UserContext.setUserId(1L);
        return tx.execute(status->reservations.reserveAuditLogs(AuditReserveBO.builder().auditScene(AuditScene.POST).targetId(id).textContent("Title\nOriginal content").mediaItems(media?List.of(AuditReserveBO.MediaItem.builder().mediaType(MediaType.IMAGE).mediaUrl("https://fixture.invalid/image").build()):List.of()).build()));
    }
    String raw(String suggestion) {return "{\"errcode\":0,\"result\":{\"suggest\":\""+suggestion+"\",\"label\":100},\"detail\":[{\"errcode\":0,\"suggest\":\""+suggestion+"\",\"label\":100}]}";}
    void complete(long log,long target,String suggestion) {
        if(results.completeText(log,rows.forCheck(log).getTargetType()==1?AuditScene.POST:AuditScene.COMMENT,target,"trace-"+log,"pass".equals(suggestion)?AuditStatus.PASS:AuditStatus.REJECT,raw(suggestion),null)) deliver(log);
    }
    void deliver(long log) {
        var event=jdbc.queryForMap("SELECT message_id,payload FROM outbox_event WHERE topic='audit.completed' AND aggregate_id=? ORDER BY id DESC LIMIT 1",Long.toString(log));
        try {
            var json=context.getBean(ObjectMapper.class);var payload=json.readTree(event.get("payload").toString());
            context.getBean(cn.jualn.miniapp.module.audit.async.AuditCompletedEventHandler.class).handle(json.treeToValue(payload,cn.jualn.miniapp.module.audit.async.AuditCompletedEventPayload.class),new cn.jualn.miniapp.infrastructure.async.message.MessageEnvelope(event.get("message_id").toString(),"audit.completed",1,java.time.OffsetDateTime.now(),null,payload));
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw new IllegalStateException(failure);}
    }
    long pending() {long post=post();var r=reserve(post,false);complete(r.getTextAuditLogId(),post,"risky");return r.getTextAuditLogId();}
    AdminReviewDecisionBO command(long task,long actor,String key) {return AdminReviewDecisionBO.builder().taskId(task).operatorId(actor).action("approve").idempotencyKey(key).build();}
    @Test void completeEvidenceOutOfOrderAndDuplicateCallbacks() {
        long post=post();var r=reserve(post,true);long media=r.getMediaItems().get(0).getAuditLogId();
        complete(media,post,"pass");assertEquals("checking",rounds.find(r.getTextAuditLogId()).getState());
        complete(r.getTextAuditLogId(),post,"risky");var round=rounds.find(r.getTextAuditLogId());assertEquals("pending",round.getState());
        assertTrue(rounds.evidence(round).complete());assertEquals(2,rounds.evidence(round).checks().size());
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class);
        complete(media,post,"pass");assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM post WHERE id=?",Integer.class,post));
        var detail=admin.getReviewDetail(round.getTaskId());assertEquals(round.getSubmissionVersion(),detail.getEvidence().submissionVersion());assertEquals("Original content",detail.getContent());
    }
    @Test void unknownDoesNotBecomeReviewAndLateAuthoritativeResultRecovers() {
        long post=post();var r=reserve(post,false);complete(r.getTextAuditLogId(),post,"unexpected");
        assertEquals("check-failed",rounds.find(r.getTextAuditLogId()).getState());assertFalse(rounds.evidence(rounds.find(r.getTextAuditLogId())).complete());
        assertThrows(ReviewConflict.class,()->admin.decide(command(r.getTextAuditLogId(),9,"unknown-original-key")));
        complete(r.getTextAuditLogId(),post,"pass");assertEquals("approved",rounds.find(r.getTextAuditLogId()).getState());
    }
    @Test void confirmedDomainRejectIsNotManuallyOverridable() {
        long post=post();var r=reserve(post,false);complete(r.getTextAuditLogId(),post,"reject");
        assertEquals("pending",rounds.find(r.getTextAuditLogId()).getState());
        // Synthetic owning-policy verdict: raw Provider 'reject' by itself never supplies this authority.
        jdbc.update("UPDATE content_safety_check SET result='reject' WHERE audit_log_id=?",r.getTextAuditLogId());
        tx.executeWithoutResult(t->rounds.reconcile(r.getTextAuditLogId()));
        assertEquals("rejected",rounds.find(r.getTextAuditLogId()).getState());assertEquals(3,jdbc.queryForObject("SELECT status FROM post WHERE id=?",Integer.class,post));
        assertEquals("review-already-decided",assertThrows(ReviewConflict.class,()->admin.decide(command(r.getTextAuditLogId(),9,"reject-original-key"))).getConflictCode());
    }
    @Test void roundDecisionSerializesTwoAdministrators() throws Exception {
        long task=pending();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var calls=new ArrayList<Future<String>>();for(long actor:List.of(9L,10L)) calls.add(pool.submit(()->{start.await();try {admin.decide(command(task,actor,"concurrent-key-"+actor));return "success";}catch(ReviewConflict e){return e.getConflictCode();}}));start.countDown();
            var outcomes=new ArrayList<String>();for(var c:calls) outcomes.add(c.get(15,TimeUnit.SECONDS));Collections.sort(outcomes);assertEquals(List.of("review-already-decided","success"),outcomes);
        } finally {pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE review_task_id=? AND audit_source=2",Integer.class,task));
    }
    @Test void successfulReplaySurvivesLaterDeletionButNotRevocationOrChangedIntent() {
        long task=pending();var c=command(task,9,"stable-request-key");admin.decide(c);
        jdbc.update("UPDATE post SET status=4,deleted_at=NOW() WHERE id=?",rounds.find(task).getTargetId());admin.decide(c);
        var other=command(task,9,"stable-request-key");other.setAction("reject");other.setReasonCode("other");assertEquals("review-idempotency-conflict",assertThrows(ReviewConflict.class,()->admin.decide(other)).getConflictCode());
        decisions.remove(9L);assertThrows(RuntimeException.class,()->admin.decide(c));
    }
    @Test void staleVersionAndDeletionNeverReactivateContent() {
        long task=pending();long target=rounds.find(task).getTargetId();jdbc.update("UPDATE post SET revision=revision+1,audit_revision=audit_revision+1,content='New checked version',status=2,audit_status=1 WHERE id=?",target);
        assertEquals("review-version-stale",assertThrows(ReviewConflict.class,()->admin.decide(command(task,9,"stale-request-key"))).getConflictCode());
        tx.executeWithoutResult(s->rounds.reconcile(task));assertEquals("invalidated",rounds.find(task).getState());assertEquals("New checked version",jdbc.queryForObject("SELECT content FROM post WHERE id=?",String.class,target));
        long deleted=pending();jdbc.update("UPDATE post SET status=4,deleted_at=NOW() WHERE id=?",rounds.find(deleted).getTargetId());assertEquals("review-target-inactive",assertThrows(ReviewConflict.class,()->admin.decide(command(deleted,9,"deleted-request-key"))).getConflictCode());
    }
    @Test void ownerFailureAndCommitTimeRevocationRollBackAllDecisionFacts() {
        long task=pending();failEffect.set(true);assertThrows(RuntimeException.class,()->admin.decide(command(task,9,"rollback-request-key")));failEffect.set(false);
        assertEquals("pending",rounds.find(task).getState());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE review_task_id=? AND audit_source=2",Integer.class,task));
        revokeAtCommit.set(true);assertThrows(RuntimeException.class,()->admin.decide(command(task,9,"revoke-request-key")));assertEquals("pending",rounds.find(task).getState());
        assertEquals(1,jdbc.queryForObject("SELECT status FROM post WHERE id=?",Integer.class,rounds.find(task).getTargetId()));
    }
    @Test void resultAndOutboxRollBackTogether() {
        long post=post();long task=reserve(post,false).getTextAuditLogId();long before=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Long.class);
        assertThrows(IllegalStateException.class,()->tx.execute(status->{results.completeText(task,AuditScene.POST,post,"t",AuditStatus.PASS,raw("pass"),null);throw new IllegalStateException("synthetic rollback");}));
        assertEquals("queued",rows.check(task).getExecutionState());assertEquals(0,logs.selectById(task).getFinalResult());assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Long.class));
    }
    @Test void listCountSummaryAndDetailKeepNamespaceAndLegacyBoundaries() {
        long task=pending();assertTrue(logs.countAdminReviews(null,null,null,"pending",List.of("POST"))>0);assertEquals(0,logs.countAdminReviews(null,null,null,"pending",List.of("ACTIVITY")));
        assertEquals(0,logs.selectScopedReviewSummary(List.of("ACTIVITY")).getPending());assertEquals(0,logs.countAdminReviews(null,null,null,"pending",null));
        grant(9,"ACTIVITY");assertThrows(RuntimeException.class,()->admin.getReviewDetail(task));assertThrows(RuntimeException.class,()->admin.decide(command(task,9,"cross-domain-key")));
        grant(9,"POST");assertEquals(logs.countAdminReviews(null,null,null,"pending",List.of("POST")),logs.selectScopedReviewSummary(List.of("POST")).getPending());
        var d=decisions.get(9L);decisions.put(9L,new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(new Grant("standalone","CONTENT_REVIEWER","POST","GLOBAL",List.of("review:read","review:decide"),null))));
        assertThrows(RuntimeException.class,()->admin.decide(command(task,9,"standalone-request-key")));decisions.put(9L,d);
    }
    @Test void legacyIdentityAndReceiptsAreNotReboundToCurrentContent() {
        long p=post();long id=ids.incrementAndGet();jdbc.update("INSERT INTO content_audit_log(id,target_type,target_id,audit_source,wx_result,final_result) VALUES(?,1,?,1,1,2)",id,p);
        assertEquals("review-legacy-unresolved",assertThrows(ReviewConflict.class,()->admin.decide(command(id,9,"legacy-unresolved-key"))).getConflictCode());
        var detail=admin.getReviewDetail(id);assertNull(detail.getReview());assertEquals("",detail.getContent());
        jdbc.update("INSERT INTO content_audit_log(target_type,target_id,audit_source,admin_user_id,admin_action,idempotency_key,final_result) VALUES(1,?,2,9,1,'legacy-original-key',1)",p);
        admin.decide(command(id,9,"legacy-original-key"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_safety_review WHERE target_id=?",Integer.class,p));
    }
    @Test void reservationAndJobsRollbackAndRetryHasNoSecondRemoteSubmission() {
        long post=post();long before=jdbc.queryForObject("SELECT COUNT(*) FROM async_job",Long.class);
        assertThrows(IllegalStateException.class,()->tx.execute(s->{reserve(post,false);throw new IllegalStateException("rollback");}));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM async_job",Long.class));
        long task=reserve(post,false).getTextAuditLogId();var execution=context.getBean(ContentSafetyCheckExecution.class);assertEquals(task,execution.prepare(task));execution.unknown(task);
        assertThrows(cn.jualn.miniapp.infrastructure.async.job.UnknownOutcomeException.class,()->execution.prepare(task));assertEquals("unknown",rows.check(task).getExecutionState());
    }
    @Test void commentsUseRealOwningCounterAndDurableNotificationAcceptance() {
        long post=post();jdbc.update("UPDATE post SET audit_status=1 WHERE id=?",post);
        long comment=ids.incrementAndGet();jdbc.update("INSERT INTO comment(id,target_type,target_id,user_id,content,status,audit_status) VALUES(?,1,?,2,'Comment body',0,0)",comment,post);
        UserContext.setUserId(2L);
        var r=tx.execute(t->reservations.reserveAuditLogs(AuditReserveBO.builder().auditScene(AuditScene.COMMENT).targetId(comment).textContent("Comment body").build()));
        complete(r.getTextAuditLogId(),comment,"risky");admin.decide(command(r.getTextAuditLogId(),9,"comment-review-key"));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM comment WHERE id=?",Integer.class,comment));assertEquals(1,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,post));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic='test.notification.accepted'",Integer.class));
        admin.decide(command(r.getTextAuditLogId(),9,"comment-review-key"));assertEquals(1,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,post));
    }
    @Test void commentRemovalAndRestoreKeepParentSubjectAndAuditAtomic() {
        long post=post(),root=ids.incrementAndGet(),reply=ids.incrementAndGet();
        jdbc.update("UPDATE post SET audit_status=1,comment_count=2 WHERE id=?",post);
        jdbc.update("INSERT INTO comment(id,target_type,target_id,user_id,content,status,audit_status,reply_count) VALUES(?,1,?,2,'Root',1,1,1)",root,post);
        jdbc.update("INSERT INTO comment(id,target_type,target_id,user_id,parent_id,content,status,audit_status) VALUES(?,1,?,2,?,'Reply',1,1)",reply,post,root);
        decisions.put(9L,new Decision(9,Source.LEGACY,"v","c",LocalDateTime.now(),true,List.of("*"),List.of()));
        var governance=context.getBean(CommentGovernanceService.class);
        var command=cn.jualn.miniapp.module.comment.bo.AdminCommentActionBO.builder().operatorId(9L).commentId(reply).reason("test moderation").build();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                ()->context.getBean(CommentCounters.class).decrease(1,post,root));
        governance.takeDownComment(command);
        assertEquals(0,jdbc.queryForObject("SELECT reply_count FROM comment WHERE id=?",Integer.class,root));
        assertEquals(1,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,post));
        long audit=jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_record",Long.class);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(t->{governance.restoreComment(command);throw new IllegalStateException("rollback restore");}));
        assertEquals(4,jdbc.queryForObject("SELECT status FROM comment WHERE id=?",Integer.class,reply));
        assertEquals(audit,jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_record",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT reply_count FROM comment WHERE id=?",Integer.class,root));
        assertEquals(1,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,post));
        governance.restoreComment(command);
        assertEquals(1,jdbc.queryForObject("SELECT reply_count FROM comment WHERE id=?",Integer.class,root));
        assertEquals(2,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,post));
        UserContext.setUserId(2L);context.getBean(CommentService.class).removeComment(reply);
        assertEquals(0,jdbc.queryForObject("SELECT reply_count FROM comment WHERE id=?",Integer.class,root));
        assertEquals(1,jdbc.queryForObject("SELECT comment_count FROM post WHERE id=?",Integer.class,post));
    }
    @Test void publicEventCommentCannotBorrowPostOrActivityDuty() {
        long event=ids.incrementAndGet();jdbc.update("INSERT INTO public_event(id,user_id,title,publish_status) VALUES(?,1,'Event',1)",event);
        long comment=ids.incrementAndGet();jdbc.update("INSERT INTO comment(id,target_type,target_id,user_id,content,status,audit_status) VALUES(?,3,?,1,'Event comment',0,0)",comment,event);
        UserContext.setUserId(1L);var r=tx.execute(t->reservations.reserveAuditLogs(AuditReserveBO.builder().auditScene(AuditScene.COMMENT).targetId(comment).textContent("Event comment").build()));
        complete(r.getTextAuditLogId(),comment,"risky");assertThrows(RuntimeException.class,()->admin.decide(command(r.getTextAuditLogId(),9,"wrong-post-duty-key")));
        grant(9,"ACTIVITY");assertThrows(RuntimeException.class,()->admin.decide(command(r.getTextAuditLogId(),9,"wrong-activity-key")));
        grant(9,"PUBLIC_EVENT");assertEquals(1,logs.countAdminReviews(null,null,null,"pending",List.of("PUBLIC_EVENT")));assertEquals("PUBLIC_EVENT",admin.getReviewDetail(r.getTextAuditLogId()).getReview().namespace());
        admin.decide(command(r.getTextAuditLogId(),9,"event-comment-key"));assertEquals("approved",rounds.find(r.getTextAuditLogId()).getState());
    }
    @Test void mvcProjectionAndConflictUseCanonicalWireAndNoStore() throws Exception {
        long task=pending();
        var old=SaManager.getStpInterface();
        SaManager.setStpInterface(new cn.dev33.satoken.stp.StpInterface() {
            public List<String> getPermissionList(Object id,String type) {return List.of("review:read","review:decide");}
            public List<String> getRoleList(Object id,String type) {return List.of();}
        });
        try {
            var mapper=new cn.jualn.miniapp.config.JacksonConfig().objectMapper(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder());
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new cn.jualn.miniapp.module.audit.controller.admin.AdminReviewController(admin,new AdminReviewConverter()))
                .setControllerAdvice(new cn.jualn.miniapp.module.audit.controller.admin.ContentSafetyProblemAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new cn.jualn.miniapp.module.audit.controller.admin.ContentSafetyNoStoreFilter()).build();
            var page=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/admin/reviews")).andReturn().getResponse();
            assertEquals(200,page.getStatus());assertEquals("no-store",page.getHeader("Cache-Control"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/content-safety-page.json"),mapper.writeValueAsString(mapper.readTree(page.getContentAsString()).get("data")));
            var detail=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/admin/reviews/"+task)).andReturn().getResponse();
            assertEquals(200,detail.getStatus());assertEquals("no-store",detail.getHeader("Cache-Control"));
            var data=mapper.readTree(detail.getContentAsString()).get("data");assertEquals("pending",data.at("/review/state").asText());assertTrue(data.at("/evidence/complete").asBoolean());
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/content-safety-detail.json"),mapper.writeValueAsString(data));
            admin.decide(command(task,9,"mvc-approved-key"));
            var conflict=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/admin/reviews/"+task+"/decisions").header("Idempotency-Key","mvc-another-request").contentType("application/json").content("{\"action\":\"approve\"}")).andReturn().getResponse();
            assertEquals(409,conflict.getStatus());assertEquals("review-already-decided",mapper.readTree(conflict.getContentAsString()).path("conflictCode").asText());
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/content-safety-conflict.json"),conflict.getContentAsString());
        } finally {SaManager.setStpInterface(old);}
    }
    @Test void failureBeforeInvocationCanRecoverWithAnExplicitReplacementAttempt() {
        long post=post();long task=reserve(post,false).getTextAuditLogId();var execution=context.getBean(ContentSafetyCheckExecution.class);
        assertEquals(task,execution.prepare(task));execution.failedBeforeInvocation(task);tx.executeWithoutResult(t->rounds.reconcile(task));assertEquals("check-failed",rounds.find(task).getState());
        long retry=execution.prepare(task);assertNotEquals(task,retry);assertEquals(task,rows.check(retry).getSupersedesCheckId());
        complete(retry,post,"pass");var evidence=rounds.evidence(rounds.find(task));assertTrue(evidence.complete());assertEquals(2,evidence.checks().size());assertEquals("approved",rounds.find(task).getState());
    }
    @Test void newVersionAndLateOldPassKeepIdentityAndSnapshotsSeparate() {
        long post=post();long old=reserve(post,false).getTextAuditLogId();
        jdbc.update("UPDATE post SET revision=revision+1,audit_revision=audit_revision+1,content='Checked edit',audit_status=1 WHERE id=?",post);
        complete(old,post,"pass");assertEquals("invalidated",rounds.find(old).getState());assertEquals("Checked edit",jdbc.queryForObject("SELECT content FROM post WHERE id=?",String.class,post));
        assertEquals("Original content",rounds.find(old).getSnapshotContent());
    }
    @Test void failedEventRollsBackConsumptionAndCanBeRedelivered() {
        long post=post();long task=reserve(post,false).getTextAuditLogId();
        results.completeText(task,AuditScene.POST,post,"trace",AuditStatus.PASS,raw("pass"),null);failEffect.set(true);
        assertThrows(RuntimeException.class,()->deliver(task));assertEquals("checking",rounds.find(task).getState());
        String message=jdbc.queryForObject("SELECT message_id FROM outbox_event WHERE aggregate_id=?",String.class,Long.toString(task));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM async_event_consumption WHERE message_id=?",Integer.class,message));
        failEffect.set(false);deliver(task);deliver(task);assertEquals("approved",rounds.find(task).getState());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM async_event_consumption WHERE message_id=?",Integer.class,message));
    }
    @Test void inspectBoundQueueQueryPlan() throws Exception {
        pending();var parameters=new HashMap<String,Object>();parameters.put("namespaces",List.of("POST"));parameters.put("tab","pending");parameters.put("sort","priority");parameters.put("limit",21);
        var statement=context.getBean(SqlSessionFactory.class).getConfiguration().getMappedStatement(ContentAuditLogMapper.class.getName()+".selectAdminReviewPage");var bound=statement.getBoundSql(parameters);
        try(var connection=context.getBean(DataSource.class).getConnection();var query=connection.prepareStatement("EXPLAIN FORMAT=JSON "+bound.getSql())) {
            new org.apache.ibatis.scripting.defaults.DefaultParameterHandler(statement,parameters,bound).setParameters(query);
            try(var result=query.executeQuery()) {assertTrue(result.next());java.nio.file.Files.writeString(java.nio.file.Path.of("target/content-safety-query-plan.json"),result.getString(1));}
        }
    }
    @Test void longUnicodeSubmissionRequiresEveryChunk() {
        long post=post();String content="\uD83D\uDE42".repeat(2500);jdbc.update("UPDATE post SET content=? WHERE id=?",content,post);UserContext.setUserId(1L);
        var reserved=tx.execute(t->reservations.reserveAuditLogs(AuditReserveBO.builder().auditScene(AuditScene.POST).targetId(post).textContent("Title\n"+content).build()));
        assertEquals(2,reserved.getTextAuditLogIds().size());
        complete(reserved.getTextAuditLogIds().get(0),post,"pass");assertEquals("checking",rounds.find(reserved.getTextAuditLogId()).getState());
        complete(reserved.getTextAuditLogIds().get(1),post,"pass");assertEquals("approved",rounds.find(reserved.getTextAuditLogId()).getState());
        for(long id:reserved.getTextAuditLogIds()) {String chunk=jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.content')) FROM async_job WHERE subject_id=?",String.class,Long.toString(id));assertTrue(chunk.codePointCount(0,chunk.length())<=2000);}
    }
    @Test void ownerDeletionPermanentlyInvalidatesPendingChildEvenIfSubjectReturns() {
        long post=post();long comment=ids.incrementAndGet();jdbc.update("INSERT INTO comment(id,target_type,target_id,user_id,content,status,audit_status) VALUES(?,1,?,1,'Child comment',0,0)",comment,post);UserContext.setUserId(1L);
        var r=tx.execute(t->reservations.reserveAuditLogs(AuditReserveBO.builder().auditScene(AuditScene.COMMENT).targetId(comment).textContent("Child comment").build()));complete(r.getTextAuditLogId(),comment,"risky");
        context.getBean(PostService.class).removePost(post);assertEquals("invalidated",rounds.find(r.getTextAuditLogId()).getState());
        // Synthetic reappearance tests permanent invalidation; it does not introduce a restore command.
        jdbc.update("UPDATE post SET status=2,deleted_at=NULL WHERE id=?",post);tx.executeWithoutResult(t->rounds.reconcile(r.getTextAuditLogId()));
        assertEquals("subject-unavailable",rounds.projection(rounds.find(r.getTextAuditLogId())).invalidationReason());
        assertEquals("review-target-inactive",assertThrows(ReviewConflict.class,()->admin.decide(command(r.getTextAuditLogId(),9,"invalid-child-key"))).getConflictCode());
    }
    @Test void missingCallbackBecomesUnknownAndSameTraceResultCanRecover() {
        long post=post();long task=reserve(post,false).getTextAuditLogId();var execution=context.getBean(ContentSafetyCheckExecution.class);execution.prepare(task);
        jdbc.update("UPDATE content_safety_review SET created_at=DATE_SUB(NOW(),INTERVAL 16 MINUTE) WHERE task_id=?",task);execution.markMissingCallbacks();deliver(task);
        assertEquals("check-failed",rounds.find(task).getState());assertEquals("unknown",rows.check(task).getExecutionState());
        complete(task,post,"pass");assertEquals("approved",rounds.find(task).getState());
    }
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    @Import({cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,ContentSafetyInvalidation.class,ContentSafetyReviewService.class,ContentSafetyAuthorization.class,ContentSafetyCheckExecution.class,AuditReservationServiceImpl.class,AuditResultPersistenceService.class,AdminReviewServiceImpl.class})
    @cn.jualn.miniapp.module.post.OrdinaryGrowthTestConfiguration.Fixture
    static class Config {
        @Bean PostPublicAccess publicAccess(PostMapper posts){return new PostPublicAccess(posts);}

        @Bean DataSource source() {return ContentSafetyReviewDatabaseTest.source(System.getProperty("safety.test.jdbcUrl"));}
        @Bean JdbcTemplate jdbc(DataSource ds) {return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager manager(DataSource ds) {return new DataSourceTransactionManager(ds);}
        @Bean ObjectMapper objectMapper() {return new ObjectMapper().findAndRegisterModules();}
        @Bean SqlSessionFactory sessions(DataSource ds) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
            factory.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig().setLogicDeleteField("deletedAt").setLogicDeleteValue("NOW()").setLogicNotDeleteValue("NULL")).setMetaObjectHandler(new MybatisPlusConfig().metaObjectHandler()));
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
            var result=factory.getObject();com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(result.getConfiguration()).setMetaObjectHandler(new MybatisPlusConfig().metaObjectHandler());
            result.getConfiguration().addMapper(ContentSafetyReviewMapper.class);return result;
        }
        @Bean ContentAuditLogMapper logs(SqlSessionFactory f) {return new org.mybatis.spring.SqlSessionTemplate(f).getMapper(ContentAuditLogMapper.class);}
        @Bean ContentSafetyReviewMapper rounds(SqlSessionFactory f) {return new org.mybatis.spring.SqlSessionTemplate(f).getMapper(ContentSafetyReviewMapper.class);}
        @Bean PostMapper postsMapper(SqlSessionFactory f) {return new org.mybatis.spring.SqlSessionTemplate(f).getMapper(PostMapper.class);}
        @Bean CommentMapper commentsMapper(SqlSessionFactory f) {return new org.mybatis.spring.SqlSessionTemplate(f).getMapper(CommentMapper.class);}
        @Bean AsyncJobMapper jobs(SqlSessionFactory f) {return new org.mybatis.spring.SqlSessionTemplate(f).getMapper(AsyncJobMapper.class);}
        @Bean OutboxMapper outboxMapper(SqlSessionFactory f) {return new org.mybatis.spring.SqlSessionTemplate(f).getMapper(OutboxMapper.class);}
        @Bean cn.jualn.miniapp.infrastructure.async.message.EventConsumptionMapper consumptionMapper(SqlSessionFactory f) {return new org.mybatis.spring.SqlSessionTemplate(f).getMapper(cn.jualn.miniapp.infrastructure.async.message.EventConsumptionMapper.class);}
        @Bean cn.jualn.miniapp.infrastructure.async.message.EventConsumptionService consumption(cn.jualn.miniapp.infrastructure.async.message.EventConsumptionMapper m) {return new cn.jualn.miniapp.infrastructure.async.message.EventConsumptionService(m);}
        @Bean cn.jualn.miniapp.module.audit.async.AuditCompletedEventHandler handler(cn.jualn.miniapp.infrastructure.async.message.EventConsumptionService c) {return new cn.jualn.miniapp.module.audit.async.AuditCompletedEventHandler(Map.of(),c);}
        @Bean JobService jobsService(AsyncJobMapper m,ObjectMapper j) {return new JobService(m,j);}
        @Bean OutboxService outbox(OutboxMapper m,ObjectMapper j) {return new OutboxService(m,j);}
        @Bean RbacAuthorizationReader reader() {var r=mock(RbacAuthorizationReader.class);when(r.read(anyLong())).thenAnswer(i->decision(i.getArgument(0)));return r;}
        static Decision decision(long actor) {return decisions.getOrDefault(actor,new Decision(actor,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),false,List.of(),List.of()));}
        @Bean BusinessAuthorizationLease authorizationLease(RbacAuthorizationReader reader) {
            var locks=mock(TransactionalAuthorization.class);when(locks.currentNow(anyLong())).thenAnswer(i->decision(i.getArgument(0)));
            return new BusinessAuthorizationLease(locks,reader);
        }
        @Bean CurrentAuthorization authorization(RbacAuthorizationReader reader) {return new CurrentAuthorization(reader);}
        @Bean TestMediaService media() {var m=mock(TestMediaService.class);when(m.listAttachments(any(),anyLong())).thenReturn(List.of(cn.jualn.miniapp.module.media.bo.MediaAttachmentBO.builder().id(99L).type(MediaType.IMAGE).url("https://fixture.invalid/image").originalName("image").build()));return m;}
        @Bean RedisService redis() {return mock(RedisService.class);}
        @Bean cn.jualn.miniapp.module.activity.service.ActivityReviewService activityReviews() { return mock(cn.jualn.miniapp.module.activity.service.ActivityReviewService.class); }
        @Bean ActivityInteractionSubject activity() {return mock(ActivityInteractionSubject.class);}
        @Bean PostService postService(CurrentAuthorization auth,PostMapper mapper,TestMediaService media,RedisService redis) {
            return new PostServiceImpl(auth, mock(PostOrganizationService.class), mock(PostCommentPermissions.class), mapper, media, media, mock(UserService.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserAccountService.class), redis, mock(AuditReservationService.class));
        }
        @Bean cn.jualn.miniapp.module.post.service.PostGovernanceService postGovernance(CurrentAuthorization auth,PostMapper mapper,RedisService redis) {
            var owner=new cn.jualn.miniapp.module.post.service.PostGovernanceService(auth, mapper, redis, mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class), mock(cn.jualn.miniapp.module.post.mapper.PostCommunityMapper.class), mock(cn.jualn.miniapp.module.post.service.PostCommunityNotifications.class), mock(org.springframework.beans.factory.ObjectProvider.class));
            var spy=spy(owner);
            doAnswer(i->{i.callRealMethod();if(failEffect.get()) throw new IllegalStateException("synthetic owner failure");if(revokeAtCommit.get()) decisions.remove(9L);return null;}).when(spy).approvePostReview(anyLong(),anyLong(),any());
            doAnswer(i->{i.callRealMethod();if(failEffect.get()) throw new IllegalStateException("synthetic automatic owner failure");return null;}).when(spy).applyContentSafety(anyLong(),anyLong(),anyString());
            return spy;
        }
        @Bean cn.jualn.miniapp.module.comment.service.CommentCounters commentCounters(CommentMapper mapper,PostMapper posts,RedisService redis,ActivityInteractionSubject activity) {
            return new cn.jualn.miniapp.module.comment.service.CommentCounters(mapper,
                new cn.jualn.miniapp.module.post.service.PostInteractionSubject(posts,redis),activity,mock(PublicEventInteractionSubject.class),
                mock(cn.jualn.miniapp.module.article.service.ArticleService.class));
        }
        @Bean cn.jualn.miniapp.module.comment.service.CommentGovernanceService commentGovernance(CurrentAuthorization auth,CommentMapper mapper,cn.jualn.miniapp.module.comment.service.CommentCounters counters,
                cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal audit,
                org.springframework.beans.factory.ObjectProvider<ContentSafetyInvalidation> reviews,
                org.springframework.beans.factory.ObjectProvider<CommentAuditCallback> callback) {
            return new cn.jualn.miniapp.module.comment.service.CommentGovernanceService(auth, mapper, counters, audit, mock(cn.jualn.miniapp.module.comment.service.CommentTargetAccess.class), mock(cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper.class), mock(cn.jualn.miniapp.module.post.service.PostCommunityNotifications.class), reviews, callback);
        }
        @Bean CommentAuditCallback callback(CommentMapper comments,ContentAuditLogMapper logs,PostMapper posts,ActivityInteractionSubject activity,RedisService redis,OutboxService outbox) {
            var notifications=mock(NotifyService.class);
            doAnswer(i->{outbox.append("test.notification.accepted",1,null,"comment","test",Map.of("accepted",true));return null;}).when(notifications).processNotificationPayload(any());
            return new CommentAuditCallback(comments, new cn.jualn.miniapp.module.audit.service.AuditLogService(logs), notifications, mock(UserService.class), new cn.jualn.miniapp.module.interact.service.InteractionSubjects(java.util.List.of(new cn.jualn.miniapp.module.post.service.PostInteractionSubject(posts,redis),new cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject(mock(ActivityMapper.class),redis),new cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject(mock(PublicEventMapper.class)))), mock(cn.jualn.miniapp.module.comment.service.CommentTargetAccess.class), mock(cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper.class));
        }
        @Bean CommentService commentService(CurrentAuthorization auth,CommentMapper mapper,PostMapper posts,TestMediaService media,AuditReservationService reservations,cn.jualn.miniapp.module.comment.service.CommentCounters counters) {
            return new CommentServiceImpl(auth, mock(PostQaCommentPolicy.class), mapper, publicAccess(posts), mock(cn.jualn.miniapp.module.user.service.UserAccountService.class), mock(CommentTargetAccess.class), reservations, media, mock(CanonicalCommentService.class), counters, mock(cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper.class));
        }
    }
}
