package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.bo.PointMaterialBO.*;
import cn.jualn.miniapp.module.media.mapper.LearningMaterialMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MySQL/Flyway/MyBatis/transactions and competing cleanup. COS and LearningTask admission are explicit doubles. */
@EnabledIfSystemProperty(named="learning.material.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/learning_material_test_[a-z0-9]+")
class LearningMaterialDatabaseTest {
    static String url,serverUuid;
    static DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    LearningMaterialService service;
    LearningMaterialStorage storage;
    LearningMaterialMapper mapper;
    JdbcTemplate db;
    TransactionTemplate tx;
    MutableClock clock;
    static String options="?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    @BeforeAll static void migrate() {
        url=System.getProperty("learning.material.test.jdbcUrl");String server=url.substring(0,url.lastIndexOf('/')+1),schema=url.substring(url.lastIndexOf('/')+1);
        var admin=new JdbcTemplate(new DriverManagerDataSource(server+"mysql"+options,"root",""));
        serverUuid=admin.queryForObject("SELECT @@server_uuid",String.class);assertEquals(System.getProperty("learning.material.test.serverUuid"),serverUuid,"Explicit task-owned MySQL instance required");
        for(String name:List.of(schema,schema+"_upgrade")) {
            assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,name),"Fresh task-owned schema required");
            admin.execute("CREATE DATABASE "+name);var ds=new DriverManagerDataSource(server+name+options,"root","");
            if(name.endsWith("_upgrade")) {
                Flyway.configure().dataSource(ds).target("84").load().migrate();var sql=new JdbcTemplate(ds);
                sql.update("INSERT INTO user_profile(id,openid) VALUES(99001,'learning-v1-upgrade-sentinel')");
                sql.update("INSERT INTO learning_task(id,title,description,requirements,review_required,first_deadline,status,revision,deadline_revision,created_by,created_at,updated_at) VALUES(99001,'retained draft','keep history','{}',0,'2026-12-01','DRAFT',1,1,99001,NOW(6),NOW(6))");
            }
            var flyway=Flyway.configure().dataSource(ds).load();flyway.migrate();flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
            if(name.endsWith("_upgrade")) assertEquals("keep history",new JdbcTemplate(ds).queryForObject("SELECT description FROM learning_task WHERE id=99001",String.class));
        }
        source=new DriverManagerDataSource(url+options,"root","");
    }
    @BeforeEach void open() {
        context=new AnnotationConfigApplicationContext(Config.class);service=context.getBean(LearningMaterialService.class);storage=context.getBean(LearningMaterialStorage.class);
        mapper=context.getBean(LearningMaterialMapper.class);db=new JdbcTemplate(source);tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));clock=context.getBean(MutableClock.class);
        db.update("DELETE FROM media_learning_receipt");db.update("DELETE FROM media_learning_file");db.update("DELETE FROM media_learning_upload");
        when(storage.kinds()).thenReturn(List.of("IMAGE","VIDEO","PDF","WORD"));when(storage.configuredKinds()).thenReturn(List.of("IMAGE","VIDEO","PDF","WORD"));
        when(storage.credentials(anyString(),anyList(),any())).thenAnswer(call->{String id=call.getArgument(0);List<String> keys=call.getArgument(1);LocalDateTime expiry=call.getArgument(2);return new Credentials("private-fixture-123","fixture",keys,"redacted","redacted","redacted",expiry.minusSeconds(10).toEpochSecond(ZoneOffset.UTC),null,id,expiry.minusSeconds(10));});
        when(storage.access(anyString(),any())).thenAnswer(call->new Access("https://private.invalid/object",call.getArgument(1)));
    }
    @AfterEach void close(){context.close();}
    String key(){return UUID.randomUUID().toString();}
    Credentials upload(long user,long assignment,String kind){return service.upload(user,assignment,List.of(new Declaration("VIDEO".equals(kind)?"proof.mp4":"proof.pdf",kind,"VIDEO".equals(kind)?"video/mp4":"application/pdf",100)),key(),()->{},()->{});}
    LearningMaterialService.File registered(long user,long assignment,String kind){var upload=upload(user,assignment,kind);return service.register(user,assignment,upload.uploadId(),0,key(),()->{},()->{});}

    @Test void fileOnlyVideoIsPrivateImmutableAndSurvivesResubmissionAndTerminalTime() {
        var video=registered(42,501,"VIDEO");assertEquals("VIDEO",video.kind());assertNotNull(video.unboundExpiresAt());
        tx.executeWithoutResult(s->service.bind(42,501,701,List.of(Long.parseLong(video.fileId())),List.of("VIDEO")));
        clock.value=clock.value.plus(Duration.ofDays(100));
        var metadata=tx.execute(s->service.metadata(service.row(Long.parseLong(video.fileId())),false));
        assertNull(metadata.unboundExpiresAt());assertEquals(video.fileId(),metadata.fileId());assertEquals(0,service.cleanupExpiredBatch());
        assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->service.bind(42,502,702,List.of(Long.parseLong(video.fileId())),List.of("VIDEO"))));
        assertEquals(701,db.queryForObject("SELECT submission_id FROM media_learning_file WHERE file_id=?",Long.class,video.fileId()));
        verify(storage,never()).delete(anyString());
        var access=service.access("trusted-retained-key");assertTrue(access.expiresAt().isAfter(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)));
        assertTrue(!access.expiresAt().isAfter(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).plusMinutes(5)));
    }
    @Test void unauthorizedReplayAndCrossAssignmentAreRejectedBeforeReturningOriginalFile() {
        var upload=upload(42,501,"PDF");String requestKey=key();var first=service.register(42,501,upload.uploadId(),0,requestKey,()->{},()->{});
        assertEquals(first,service.register(42,501,upload.uploadId(),0,requestKey,()->{},()->{}));
        assertThrows(RuntimeException.class,()->service.register(43,501,upload.uploadId(),0,requestKey,()->{},()->{}));
        assertThrows(RuntimeException.class,()->service.register(42,502,upload.uploadId(),0,requestKey,()->{},()->{}));
        assertThrows(SecurityException.class,()->service.register(42,501,upload.uploadId(),0,requestKey,()->{throw new SecurityException("revoked");},()->{}));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM media_learning_file",Integer.class));
    }
    @Test void candidateExpiryIsIndependentOfUploadAndTakeoverIsAllOrNothing() {
        var first=registered(42,501,"PDF");clock.value=clock.value.plus(Duration.ofMinutes(20));
        tx.execute(s->{assertNotNull(service.metadata(service.row(Long.parseLong(first.fileId())),true));return null;});
        var second=registered(43,501,"PDF");
        assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->service.bind(42,501,701,List.of(Long.parseLong(first.fileId()),Long.parseLong(second.fileId())),List.of("PDF"))));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_learning_file WHERE state='BOUND'",Integer.class));
        clock.value=clock.value.plus(Duration.ofDays(2));assertEquals(2,service.cleanupExpiredBatch());
        assertThrows(RuntimeException.class,()->tx.execute(s->service.metadata(service.row(Long.parseLong(first.fileId())),true)));
    }
    @Test void cleanupWinningWhileInspectionIsRunningPreventsSuccessfulRegistration() throws Exception {
        var upload=upload(42,501,"PDF");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(c->{assertTrue(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return null;}).when(storage).inspect(anyString(),anyString(),anyLong(),any());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(()->service.register(42,501,upload.uploadId(),0,key(),()->{},()->{}));assertTrue(entered.await(10,TimeUnit.SECONDS));
            clock.value=clock.value.plus(Duration.ofDays(2));assertEquals(1,service.cleanupExpiredBatch());release.countDown();
            assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));
            assertEquals("CLEANING",db.queryForObject("SELECT state FROM media_learning_file",String.class));
            assertEquals(1,service.cleanupExpiredBatch());assertEquals("DELETED",db.queryForObject("SELECT state FROM media_learning_file",String.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_learning_receipt",Integer.class));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void capabilitiesDistinguishKnownZeroFromUnknownAndRejectUnreadyDeclarations() {
        when(storage.kinds()).thenReturn(List.of());assertEquals(0,service.capabilities().maximumFiles());assertTrue(service.capabilities().availableKinds().isEmpty());
        assertThrows(RuntimeException.class,()->upload(42,501,"VIDEO"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_learning_upload",Integer.class));
        when(storage.kinds()).thenThrow(new IllegalStateException("synthetic storage unknown"));
        var failure=assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->service.capabilities());assertEquals(503,failure.getStatus().value());
    }
    @Test void concurrentCredentialRecoveryUsesOneInProcessIssueAndOriginalAllocation() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);String requestKey=key();
        doAnswer(call->{
            entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));LocalDateTime expiry=call.getArgument(2);
            return new Credentials("private-fixture-123","fixture",call.getArgument(1),"redacted","redacted","redacted",expiry.minusSeconds(10).toEpochSecond(ZoneOffset.UTC),null,call.getArgument(0),expiry.minusSeconds(10));
        }).when(storage).credentials(anyString(),anyList(),any());
        var declaration=List.of(new Declaration("proof.pdf","PDF","application/pdf",100));var pool=Executors.newSingleThreadExecutor();
        try {
            var first=pool.submit(()->service.upload(42,501,declaration,requestKey,()->{},()->{}));assertTrue(entered.await(10,TimeUnit.SECONDS));
            var pending=assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->service.upload(42,501,declaration,requestKey,()->{},()->{}));assertEquals(409,pending.getStatus().value());
            release.countDown();var original=first.get(10,TimeUnit.SECONDS);assertEquals(original,service.upload(42,501,declaration,requestKey,()->{},()->{}));
            verify(storage,times(1)).credentials(anyString(),anyList(),any());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM media_learning_upload",Integer.class));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void exactCaseOfIdempotencyKeysSurvivesDefaultDatabaseCollation() {
        var pdf=service.upload(42,501,List.of(new Declaration("proof.pdf","PDF","application/pdf",100)),"CaseKey",()->{},()->{});
        var video=service.upload(42,501,List.of(new Declaration("proof.mp4","VIDEO","video/mp4",100)),"casekey",()->{},()->{});
        assertNotEquals(pdf.uploadId(),video.uploadId());
        var a=service.register(42,501,pdf.uploadId(),0,"RegisterCase",()->{},()->{});
        var b=service.register(42,501,video.uploadId(),0,"registercase",()->{},()->{});assertNotEquals(a.fileId(),b.fileId());
        var conflict=assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->service.register(42,501,video.uploadId(),0,"RegisterCase",()->{},()->{}));assertEquals(409,conflict.getStatus().value());
    }
    @Test void samePersonOnANewAccountCannotReviewAndArchivedRecipientKeepsDeterminableBinding() {
        var persons=personScopes();String person=person("同名人员");String binding=UUID.randomUUID().toString();
        db.update("INSERT INTO person_account_binding(id,user_id,person_id,status,revision,started_at,verification_method,source_reference,started_by) VALUES(?,9,?,'ACTIVE',1,UTC_TIMESTAMP(6),'ADMIN_MANUAL','synthetic trusted rebind',9)",binding,person);
        var users=mock(cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService.class);when(users.available(List.of(9L))).thenReturn(List.of(9L));
        var responsibilities=new cn.jualn.miniapp.module.learningtask.service.LearningTaskResponsibilities(persons,users);
        var provider=new cn.jualn.miniapp.module.learningtask.service.UnavailableLearningTaskProviders(mock(cn.jualn.miniapp.module.article.service.ArticleReferenceReadService.class),mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy.class),mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskTextSafety.class),users,mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskNotifications.class));
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"responsibilities",responsibilities);
        tx.executeWithoutResult(s->{persons.lock(false);var error=assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->provider.requireOtherReviewer(9,42,person));assertEquals(403,error.getStatus().value());provider.requireOtherReviewer(9,42,"another-person");});
        db.update("UPDATE person_profile SET status='ARCHIVED' WHERE id=?",person);
        tx.executeWithoutResult(s->{persons.lock(false);var recipient=responsibilities.recipient(cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject.person(person));assertEquals("PERSON_UNAVAILABLE",recipient.availability());assertEquals(9L,recipient.deliveryUserId());assertEquals(binding,recipient.bindingId());});
    }
    @Test void unavailableNotificationIsDurableWithoutDeliveryAndAcceptanceFailureRollsBack() {
        var notify=mock(cn.jualn.miniapp.module.notify.service.NotifyService.class);
        var notices=new cn.jualn.miniapp.module.learningtask.service.LearningTaskNotifications(mock(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper.class),mock(cn.jualn.miniapp.module.learningtask.mapper.LearningAssignmentMapper.class),mock(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper.class),notify,mock(cn.jualn.miniapp.infrastructure.async.job.JobService.class),clock,mock(cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService.class));
        var ledger=mapper(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskNoticeMapper.class);org.springframework.test.util.ReflectionTestUtils.setField(notices,"noticeLedger",ledger);
        String occurrence=key();var subject=cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject.person("stable-person");
        var skipped=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice(occurrence,"CANCELLED",501,601,42L,"task",null,null,null,subject,"fixed-binding","ACCOUNT_UNAVAILABLE");
        tx.executeWithoutResult(s->notices.accept(List.of(skipped)));verifyNoInteractions(notify);
        String unbound=key();var noBinding=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice(unbound,"CANCELLED",501,602,null,"task",null,null,null,subject,null,"NO_BINDING");
        tx.executeWithoutResult(s->notices.accept(List.of(noBinding)));verifyNoInteractions(notify);assertNull(db.queryForObject("SELECT delivery_user_id FROM learning_task_notice WHERE occurrence=?",Long.class,unbound));
        assertEquals("fixed-binding",db.queryForObject("SELECT binding_id FROM learning_task_notice WHERE occurrence=?",String.class,occurrence));
        assertEquals(42L,db.queryForObject("SELECT delivery_user_id FROM learning_task_notice WHERE occurrence=?",Long.class,occurrence));
        db.update("UPDATE learning_task_notice SET created_at=DATE_SUB(NOW(6),INTERVAL 91 DAY) WHERE occurrence=?",occurrence);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_task_notice WHERE occurrence=?",Integer.class,occurrence));
        String failed=key();var accepted=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice(failed,"CANCELLED",501,601,42L,"task",null,null,null,subject,"fixed-binding","AVAILABLE");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic inbox unavailable")).when(notify).processNotificationPayload(any());
        assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->notices.accept(List.of(accepted))));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM learning_task_notice WHERE occurrence=?",Integer.class,failed));
    }
    @Test void actualRecipientQueryUsesOrganizationUnionThenStageIntersectionAndStableBoundedPages() {
        String a=organization("A"),b=organization("B");String p1=person("同名"),p2=person("同名"),p3=person("其他阶段");
        membership(p1,a);membership(p2,b);membership(p3,a);stage(p1,"stage-one");stage(p2,"stage-one");stage(p3,"stage-two");
        db.update("INSERT INTO person_major_class_registration(person_id,major_class,last_action,source_kind,source_reference,confirmed_by,confirmed_at,revision) VALUES(?,'专业A班','SET','ADMIN_REGISTRATION','synthetic',9,UTC_TIMESTAMP(6),1)",p1);
        var query=mapper(cn.jualn.miniapp.module.person.mapper.LearningRecipientMapper.class);var ceilings=List.of(new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,a),new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,b));
        var at=LocalDateTime.of(2026,11,2,12,0);var result=query.candidates(false,ceilings,List.of(a,b),List.of("stage-one"),null,null,51,at);
        assertEquals(Set.of(p1,p2),result.stream().map(cn.jualn.miniapp.module.person.mapper.LearningRecipientMapper.Candidate::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals("专业A班",result.stream().filter(r->r.id().equals(p1)).findFirst().orElseThrow().disambiguation());
        var first=query.candidates(false,ceilings,List.of(a,b),List.of("stage-one"),null,null,1,at);var next=query.candidates(false,ceilings,List.of(a,b),List.of("stage-one"),null,first.get(0).id(),1,at);
        assertEquals(1,first.size());assertEquals(1,next.size());assertNotEquals(first.get(0).id(),next.get(0).id());
        assertTrue(query.candidates(false,List.of(),List.of(),List.of(),null,null,51,at).isEmpty());
        assertEquals(List.of(a),query.organizations(false,List.of(a),null,null,51).stream().map(cn.jualn.miniapp.module.person.mapper.LearningRecipientMapper.Candidate::id).toList());
        db.update("INSERT INTO user_profile(id,openid,nickname,status) VALUES(42,'learning-option-account','普通账号',1) ON DUPLICATE KEY UPDATE nickname='普通账号'");
        db.update("INSERT INTO person_account_binding(id,user_id,person_id,status,revision,started_at,verification_method,source_reference,started_by) VALUES(?,42,?,'ACTIVE',1,UTC_TIMESTAMP(6),'ROSTER_EXACT_MATCH','synthetic roster match',9)",UUID.randomUUID().toString(),p1);
        var selectedPerson = query.selectedNames(false, List.of(p1));
        assertEquals(List.of(p1), selectedPerson.stream().map(row -> row.id()).toList());
        assertEquals("专业A班", selectedPerson.get(0).disambiguation());
        var selectedAccount = query.selectedNames(true, List.of("42", "999999"));
        assertEquals(List.of("42"), selectedAccount.stream().map(row -> row.id()).toList());
        assertEquals("普通账号", selectedAccount.get(0).name());
        assertNull(selectedAccount.get(0).personId());
        var platform=List.of(new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("PLATFORM",null,null));
        assertTrue(query.candidates(true,platform,List.of(a),List.of("stage-one"),null,null,51,at).stream().anyMatch(r->r.id().equals("42")));
        var plain=query.candidates(true,platform,List.of(),List.of(),"普通账号",null,51,at).stream().filter(r->r.id().equals("42")).findFirst().orElseThrow();assertEquals(p1, plain.personId());assertEquals("专业A班", plain.disambiguation());
    }
    private <T> T mapper(Class<T> type) {var factory=context.getBean(SqlSessionFactory.class);if(!factory.getConfiguration().hasMapper(type))factory.getConfiguration().addMapper(type);return new org.mybatis.spring.SqlSessionTemplate(factory).getMapper(type);}
    private cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes personScopes() {
        return new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes(mapper(cn.jualn.miniapp.module.person.mapper.PersonBusinessMapper.class),mapper(cn.jualn.miniapp.module.person.mapper.PersonMapper.class),mapper(cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper.class),new cn.jualn.miniapp.module.person.service.facts.PersonCatalog());
    }
    private String event(String type,String id) {
        db.update("INSERT INTO user_profile(id,openid,status) VALUES(9,'learning-v1-owner',1) ON DUPLICATE KEY UPDATE id=id");String event=UUID.randomUUID().toString();
        db.update("INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id) VALUES(?,?,?,1,'fixture','{}',9,UTC_TIMESTAMP(6),'test','synthetic','LOCAL_MANUAL',?)",event,type,id,UUID.randomUUID().toString());return event;
    }
    private String person(String name) {String id=UUID.randomUUID().toString(),event=event("PERSON",id);db.update("INSERT INTO person_profile(id,real_name,status,revision,created_at,updated_at,last_change_id) VALUES(?,?,'ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",id,name,event);return id;}
    private String organization(String name) {String id=UUID.randomUUID().toString(),event=event("ORGANIZATION",id);db.update("INSERT INTO person_organization(id,name,description,status,revision,created_at,updated_at,last_change_id) VALUES(?,?,'','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",id,name,event);return id;}
    private void membership(String person,String organization) {String event=db.queryForObject("SELECT last_change_id FROM person_profile WHERE id=?",String.class,person);db.update("INSERT INTO person_membership(id,person_id,organization_id,valid_from,created_event_id) VALUES(?,?,?,'2026-01-01',?)",UUID.randomUUID().toString(),person,organization,event);}
    private void stage(String person,String stage) {String event=db.queryForObject("SELECT last_change_id FROM person_profile WHERE id=?",String.class,person);db.update("INSERT INTO person_fact_current(person_id,revision,identity_verification,identity_presence,current_record_id,stage_id,identity_status,identity_valid_from,identity_verified_by,identity_verified_at,last_change_id) VALUES(?,1,'VERIFIED','RECORDED',?,?,'ACTIVE','2026-01-01',9,UTC_TIMESTAMP(6),?)",person,event,stage,event);}
    static class MutableClock extends Clock {
        volatile Instant value=Instant.parse("2026-11-02T04:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
    }
    @org.springframework.boot.test.context.TestConfiguration @MapperScan(basePackageClasses=LearningMaterialMapper.class) @Import(LearningMaterialService.class)
    static class Config {
        @Bean DataSource source(){return source;}
        @Bean DataSourceTransactionManager manager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean(name="learningTaskClock") MutableClock clock(){return new MutableClock();}
        @Bean LearningMaterialStorage storage(){return mock(LearningMaterialStorage.class);}
        @Bean SqlSessionFactory sessions(DataSource source)throws Exception {var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);factory.setConfiguration(configuration);
            factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/ManagedPersonMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/PersonMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/PersonBusinessMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/LearningRecipientMapper.xml"));return factory.getObject();}
    }
}
