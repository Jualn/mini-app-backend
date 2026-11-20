package cn.jualn.miniapp.module.points;
import cn.jualn.miniapp.module.notify.service.inbox.PersonalNotificationInbox;
import cn.jualn.miniapp.module.notify.service.preference.NotificationPreferences;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCapabilityQueries;
import cn.jualn.miniapp.module.notify.converter.NotificationPersonalConverter;
import cn.jualn.miniapp.module.notify.bo.NotificationInboxBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPreferencesBO;

import cn.jualn.miniapp.module.notify.service.preference.NotificationPreferenceBridgeService;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationStreamCursorCodec;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationSnapshotProjection;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationCenterServiceImpl;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationCenterService;

import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;

import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.converter.NotifyConverter;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.service.*;
import cn.jualn.miniapp.module.notify.service.impl.*;
import cn.jualn.miniapp.module.points.bo.PointsBO.*;
import cn.jualn.miniapp.module.points.service.*;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL/Job worker/RBAC account/NotifyService/inbox. Only unrelated owners and cache are stubs. */
@EnabledIfSystemProperty(named="points.notice.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/points_notice_test_[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsNotificationDatabaseTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate db;
    TransactionTemplate tx;
    PointsResultNotifications results;
    JobWorker worker;
    NotificationCenterService center;
    NotificationMapper notifications;
    final long user=901;
    static DataSource source(String url) { return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","root",""); }
    @BeforeAll void start() {
        String url=System.getProperty("points.notice.jdbcUrl");
        String name=url.substring(url.lastIndexOf('/')+1);
        new JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql")).execute("CREATE DATABASE "+name);
        Flyway.configure().dataSource(source(url)).load().migrate();
        context=new AnnotationConfigApplicationContext(Config.class);
        db=new JdbcTemplate(context.getBean(DataSource.class));
        tx=context.getBean(TransactionTemplate.class);results=context.getBean(PointsResultNotifications.class);
        worker=context.getBean(JobWorker.class);center=context.getBean(NotificationCenterService.class);
        notifications=context.getBean(NotificationMapper.class);
        db.update("INSERT INTO user_profile(id,openid) VALUES(?,'isolated-notification-recipient')",user);
        db.update("INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at) VALUES(?,'LEGACY',?,UTC_TIMESTAMP(6))",user,UUID.randomUUID().toString());
    }
    @BeforeEach void clear() {
        db.execute("DROP TRIGGER IF EXISTS points_notice_fail");
        db.execute("DROP TRIGGER IF EXISTS points_notice_accept_fail");
        db.update("DELETE FROM notification WHERE user_id=?",user);
        db.update("DELETE FROM notification_inbox_counter WHERE user_id=?",user);
        db.update("DELETE FROM async_job WHERE job_type=?",PointsResultNotifications.JOB);
        db.update("DELETE FROM point_notification_resolution");
        when(persons().deliveryBinding(any())).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("ACTIVE",user,"current-binding"));
        db.update("UPDATE user_profile SET status=1,ban_expire_at=NULL WHERE id=?",user);
        UserContext.setUserId(user);
    }
    @AfterEach void userContext() { UserContext.clear(); }
    @AfterAll void close() { if(context!=null) context.close(); }
    Application app(long id,Status status) {
        return new Application(id,user,"private title","private description",List.of("private-file"),status,LocalDateTime.now(),902L,LocalDateTime.now(),"private review note",status==Status.APPROVED?10:null,status==Status.APPROVED?501L:null,"synthetic-org","synthetic-cycle",null,null,null,"synthetic-person","submitted-binding");
    }
    void accept(Application app,Ledger reversal) { tx.executeWithoutResult(s->results.accept(app,reversal)); }
    cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes persons() {return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.class));}
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }

    @Test void accountingAcceptanceIsDurableDeduplicatedAndRollsBackTogether() {
        var approved=app(101,Status.APPROVED);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{results.accept(approved,null);throw new IllegalStateException("accounting rollback");}));
        assertEquals(0,count("async_job"));
        accept(approved,null);accept(approved,null);
        assertEquals(1,count("async_job"));assertEquals(0,count("notification"));
        assertThrows(IllegalArgumentException.class,()->accept(app(102,Status.PENDING),null));
        assertThrows(IllegalStateException.class,()->context.getBean(PointsResultNotificationHandler.class).handle(new PointsResultNotifications.Result(101,user,"POINT_APPLICATION_APPROVED","application:101:APPROVED")));
    }

    @Test void threeResultsHaveSystemProjectionSameReadStateAndNoPrivateDataOrExternalDelivery() {
        accept(app(101,Status.APPROVED),null);
        accept(app(102,Status.REJECTED),null);
        accept(app(101,Status.APPROVED),new Ledger(601,user,101,Kind.REVERSAL,-10,902,"private reversal reason",501L,LocalDateTime.now()));
        worker.poll();worker.poll();
        assertEquals(3,count("notification"));assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM async_job WHERE status='SUCCEEDED'",Long.class));
        assertEquals(0,count("notification_delivery"));
        var page=center.list(new NotificationCenterBO.Query(null,20,null,"SYSTEM",null));
        assertEquals(3,page.items().size());
        for(var item:page.items()) {
            assertEquals("SYSTEM",item.category());assertTrue(item.type().startsWith("POINT_"));
            assertEquals("POINT_APPLICATION",item.subject().type());assertNull(item.target());
            String payload=db.queryForObject("SELECT content_payload FROM notification WHERE id=?",String.class,item.id());
            assertFalse(payload.contains("private"));
            var legacy=Mappers.getMapper(NotifyConverter.class).toBO(notifications.selectOwned(Long.parseLong(item.id()),user));
            assertEquals(item.type(),legacy.getType().name());
            center.batchRead(List.of(item.id()));
            assertNotNull(center.get(item.id()).readAt());
            assertTrue(Mappers.getMapper(NotifyConverter.class).toBO(notifications.selectOwned(Long.parseLong(item.id()),user)).getIsRead());
        }
        assertEquals(0,center.summary(null).unreadCount());
        var legacyService=new PersonalNotificationInbox(notifications,context.getBean(RedisService.class));
        var legacyPage=new NotificationPersonalConverter().page(legacyService.list(new NotificationInboxBO.Query(null,20,null,null)));
        assertEquals(3,legacyPage.items().size());
        for(var item:legacyPage.items()) {
            assertTrue(item.type().startsWith("POINT_"));assertNull(item.category());assertNull(item.target());assertTrue(item.isRead());
            assertFalse(new ObjectMapper().valueToTree(item).has("category"));
        }
    }

    @Test void failedDurableAcceptanceReturnsContractDependencyAndRollsBackCallerWrite() {
        db.execute("CREATE TRIGGER points_notice_accept_fail BEFORE INSERT ON async_job FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated acceptance failure'");
        var failure=assertThrows(ContractProblemException.class,()->tx.executeWithoutResult(s->{
            db.update("UPDATE user_profile SET nickname='must roll back' WHERE id=?",user);
            results.accept(app(106,Status.APPROVED),null);
        }));
        assertEquals("NOTIFICATION_ACCEPTANCE",failure.getMessage());
        assertNotEquals("must roll back",db.queryForObject("SELECT nickname FROM user_profile WHERE id=?",String.class,user));assertEquals(0,count("async_job"));
        db.execute("DROP TRIGGER points_notice_accept_fail");
    }

    @Test void failedDeliveryRetriesOnlyNotificationAndLostAcknowledgementDoesNotDuplicate() {
        accept(app(103,Status.APPROVED),null);
        db.execute("CREATE TRIGGER points_notice_fail BEFORE INSERT ON notification FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated delivery failure'");
        worker.poll();
        assertEquals(0,count("notification"));assertEquals("PENDING",db.queryForObject("SELECT status FROM async_job",String.class));
        db.execute("DROP TRIGGER points_notice_fail");
        db.update("UPDATE async_job SET next_run_at=NOW()-INTERVAL 1 SECOND");worker.poll();
        assertEquals(1,count("notification"));assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM async_job",String.class));
        db.update("UPDATE async_job SET status='PENDING',next_run_at=NOW()-INTERVAL 1 SECOND,finished_at=NULL");worker.poll();
        assertEquals(1,count("notification"));assertEquals(1,db.queryForObject("SELECT COUNT(DISTINCT inbox_seq) FROM notification",Long.class));
        assertEquals(0,count("point_ledger"));
    }

    @Test void receiverAccountIsRecheckedAfterAcceptanceWithoutRecheckingPoliticalStage() {
        accept(app(104,Status.REJECTED),null);
        db.update("UPDATE user_profile SET status=3 WHERE id=?",user);
        worker.poll();assertEquals(0,count("notification"));
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM async_job",String.class));
        db.update("UPDATE user_profile SET status=1 WHERE id=?",user);
        accept(app(105,Status.REJECTED),null);worker.poll();
        assertEquals(1,count("notification"));
        assertEquals(0,count("person_fact_current"));
    }

    @Test void mutedRecipientReceivesReviewResultUnderOrdinaryUserLoginSemantics() {
        accept(app(107,Status.APPROVED),null);
        db.update("UPDATE user_profile SET status=2 WHERE id=?",user);
        worker.poll();
        assertEquals(1,count("notification"));
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM async_job",String.class));
        assertEquals(1,center.summary(null).unreadCount());
        accept(app(108,Status.REJECTED),null);
        db.update("UPDATE user_profile SET status=99 WHERE id=?",user);
        worker.poll();
        assertEquals(1,count("notification"));
        assertEquals("PENDING",db.queryForObject("SELECT status FROM async_job WHERE dedupe_key='application:108:REJECTED'",String.class));
    }

    @Test void correctedResultOnlyOneInboxAndRebindingSkipsOldRecipientWithDurableEvidence() {
        var app=app(301,Status.APPROVED);
        var reversal=new Ledger(701,user,301,Kind.REVERSAL,-10,902,"reason",501L,LocalDateTime.now());
        var replacement=new Ledger(702,user,301,Kind.CORRECTION_GRANT,1,902,"reason",501L,LocalDateTime.now());
        var correction=new Correction("correction-301",301,"synthetic-person",501,902,"reason","basis",LocalDateTime.now(),reversal,replacement);
        tx.executeWithoutResult(s->{results.acceptCorrection(app,correction);results.acceptCorrection(app,correction);});
        worker.poll();assertEquals(1,count("notification"));assertEquals(1,count("async_job"));
        assertEquals("POINT_GRANT_CORRECTED",center.list(new NotificationCenterBO.Query(null,20,null,"SYSTEM",null)).items().get(0).type());
        accept(app(302,Status.APPROVED),null);
        when(persons().deliveryBinding(any())).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("ACTIVE",999L,"new-binding"));
        worker.poll();assertEquals(1,count("notification"));
        assertEquals("BINDING_CHANGED",db.queryForObject("SELECT outcome FROM point_notification_resolution WHERE occurrence='application:302:APPROVED'",String.class));
        when(persons().deliveryBinding(any())).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("ACTIVE",null,null));
        accept(app(303,Status.APPROVED),null);worker.poll();assertEquals(1,count("notification"));
        assertEquals("NO_BINDING",db.queryForObject("SELECT outcome FROM point_notification_resolution WHERE occurrence='application:303:APPROVED'",String.class));
    }

    @Test void leasesHonorWorkerClockInUtcAndShanghaiSessionsAndExpiredOwnerCannotCommit() {
        var mapper=context.getBean(AsyncJobMapper.class);
        var claims=context.getBean(JobClaimService.class);
        long id=201;
        for(String zone:List.of("+00:00","+08:00")) {
            accept(app(id++,Status.APPROVED),null);
            var job=claims.claimDue("lease-test-"+zone,1).get(0);
            tx.executeWithoutResult(s->{
                db.execute("SET time_zone='"+zone+"'");
                assertEquals(zone,db.queryForObject("SELECT @@session.time_zone",String.class));
                assertEquals(1,mapper.renewLease(job.getId(),job.getLeaseOwner(),120));
                long remaining=db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CONVERT_TZ(UTC_TIMESTAMP(3),'+00:00','+08:00'),lease_until) FROM async_job WHERE id=?",Long.class,job.getId());
                assertTrue(remaining>=118 && remaining<=120,"renewed lease must have 120 business-clock seconds, actual="+remaining);
                assertEquals(1,mapper.lockOwnedAttempt(job.getId(),job.getLeaseOwner()));
                db.update("UPDATE async_job SET lease_until=CONVERT_TZ(UTC_TIMESTAMP(3),'+00:00','+08:00')-INTERVAL 1 SECOND WHERE id=?",job.getId());
                assertEquals(0,mapper.renewLease(job.getId(),job.getLeaseOwner(),120));
                assertEquals(0,mapper.lockOwnedAttempt(job.getId(),job.getLeaseOwner()));
                LocalDateTime now=LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
                assertEquals(0,mapper.markSucceeded(job.getId(),job.getLeaseOwner(),now));
                assertEquals(0,mapper.markRetry(job.getId(),job.getLeaseOwner(),now,"internal","stale"));
                assertEquals(0,mapper.markDead(job.getId(),job.getLeaseOwner(),"internal","stale",now));
            });
        }
        assertEquals(2,claims.recoverExpired(20,new RetrySchedule()));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM async_job WHERE status='PENDING' AND last_error='worker_lost'",Long.class));
    }

    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement
    @Import({cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.class,JobService.class,JobClaimService.class,PointsResultNotifications.class,PointsResultNotificationHandler.class,
            TransactionalAuthorization.class,RbacAuthorizationReader.class,AdminPermissionPolicy.class,UserCurrentLoginAccess.class,
            NotificationInboxService.class,NotificationSnapshotProjection.class,NotificationCenterServiceImpl.class})
    static class Config {
        @Bean DataSource dataSource() { return source(System.getProperty("points.notice.jdbcUrl")); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TransactionTemplate transactions(DataSourceTransactionManager tm) { return new TransactionTemplate(tm); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes persons() {return mock(cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.class);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.person.mapper.PersonMapper> personMapper(SqlSessionFactory f) {return mapper(cn.jualn.miniapp.module.person.mapper.PersonMapper.class,f);}
        @Bean MapperFactoryBean<cn.jualn.miniapp.module.points.mapper.PointsMapper> pointsMapper(SqlSessionFactory f) {return mapper(cn.jualn.miniapp.module.points.mapper.PointsMapper.class,f);}
        @Bean RbacCatalog catalog() { return new RbacCatalog(); }
        @Bean SimpleMeterRegistry meters() { return new SimpleMeterRegistry(); }
        @Bean RedisService redis() { return mock(RedisService.class); }
        @Bean NotificationStreamCursorCodec cursors() { return new NotificationStreamCursorCodec("isolated-points-notice-secret-32-characters"); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var p=new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();var values=new Properties();p.setProperties(values);return p;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);var c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);f.setConfiguration(c);
            f.setMapperLocations(new ClassPathResource("mapper/PersonMapper.xml"),new ClassPathResource("mapper/PointsMapper.xml"),new ClassPathResource("mapper/AsyncJobMapper.xml"),new ClassPathResource("mapper/NotificationMapper.xml"),new ClassPathResource("mapper/RbacMapper.xml"),new ClassPathResource("mapper/RbacOfflineMapper.xml"),new ClassPathResource("mapper/UserProfileMapper.xml"));return f.getObject();
        }
        @Bean MapperFactoryBean<AsyncJobMapper> jobs(SqlSessionFactory f) { return mapper(AsyncJobMapper.class,f); }
        @Bean MapperFactoryBean<NotificationMapper> notifications(SqlSessionFactory f) { return mapper(NotificationMapper.class,f); }
        @Bean MapperFactoryBean<RbacMapper> rbac(SqlSessionFactory f) { return mapper(RbacMapper.class,f); }
        @Bean MapperFactoryBean<UserProfileMapper> users(SqlSessionFactory f) { return mapper(UserProfileMapper.class,f); }
        static <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionFactory f) { var m=new MapperFactoryBean<T>(type);m.setSqlSessionFactory(f);return m; }
        @Bean JobWorker worker(JobClaimService claims,AsyncJobMapper mapper,ObjectMapper json,PointsResultNotificationHandler handler,SimpleMeterRegistry meters) {
            return new JobWorker(claims,mapper,new JobHandlerRegistry(json,List.of(handler)),new JobFailureClassifier(),new RetrySchedule(),Runnable::run,meters);
        }
        @Bean NotifyService notifyService(NotificationMapper mapper,NotificationInboxService inbox,RedisService redis,JobService jobs,TransactionTemplate tx,ObjectMapper json,SimpleMeterRegistry meters) {
            return new NotifyServiceImpl(mapper,
                    meters,
                    mock(cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper.class),
                    mock(cn.jualn.miniapp.module.user.service.UserService.class),
                    java.util.List.of(new cn.jualn.miniapp.module.activity.service.ActivityNotificationSource(mock(cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService.class)),new cn.jualn.miniapp.module.publicevent.service.PublicEventNotificationSource(mock(cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService.class))),
                    redis,
                    Mappers.getMapper(NotifyConverter.class),
                    jobs,
                    tx,
                    new cn.jualn.miniapp.module.notify.service.impl.NotificationCreationService(mapper,
                meters,
                inbox,
                mock(NotificationDeliveryService.class),
                redis,
                mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class),
                json,
                mock(cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper.class),
                new cn.jualn.miniapp.module.user.service.UserNotificationIdentityService(mock(cn.jualn.miniapp.module.user.mapper.UserProfileMapper.class)),
                java.util.List.of(new cn.jualn.miniapp.module.activity.service.ActivityNotificationSource(mock(cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService.class)),new cn.jualn.miniapp.module.publicevent.service.PublicEventNotificationSource(mock(cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService.class)))),
                    List.of(),
                    mock(cn.jualn.miniapp.module.notify.reminder.ReminderReconcileService.class));
        }
    }
}
