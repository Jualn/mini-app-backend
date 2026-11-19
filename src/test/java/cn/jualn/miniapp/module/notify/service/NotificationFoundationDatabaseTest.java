package cn.jualn.miniapp.module.notify.service;
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

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.config.*;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.controller.*;
import cn.jualn.miniapp.module.notify.converter.*;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.mapper.*;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.impl.*;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.converter.json.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Dedicated disposable MySQL + Redis + loopback HTTP; identities/providers are explicit test substitutes. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/notification_contract_foundation")
class NotificationFoundationDatabaseTest {
    AnnotationConfigServletWebServerApplicationContext context;
    JdbcTemplate db; NotificationMapper mapper; NotificationCreator creation;
    NotificationCenterService center; TransactionTemplate tx; NotificationLifecycleService cleanup;
    String base; final long user=910000001L;
    @BeforeEach void start() {
        context=new AnnotationConfigServletWebServerApplicationContext(Config.class);
        base="http://127.0.0.1:"+context.getWebServer().getPort();
        db=new JdbcTemplate(context.getBean(DataSource.class));mapper=context.getBean(NotificationMapper.class);
        creation=context.getBean(NotificationCreator.class);center=context.getBean(NotificationCenterService.class);
        tx=context.getBean(TransactionTemplate.class);cleanup=context.getBean(NotificationLifecycleService.class);
        db.update("DELETE FROM notification_delivery_resolution");db.update("DELETE FROM async_job");db.update("DELETE FROM notification_source_receipt");
        db.update("DELETE FROM notification");db.update("DELETE FROM notification_inbox_counter");
        db.update("DELETE FROM notification_preference WHERE user_id=?",user);
        db.update("DELETE FROM notification_preference_owner WHERE user_id=?",user);
        UserContext.setUserId(user);
    }
    @AfterEach void stop() { UserContext.clear();if(context!=null)context.close(); }
    NotifyPayload payload(String source,String body) {
        return NotifyPayload.builder().receiverId(user).type(NotifyType.SYSTEM).title("frozen")
                .content(body).sourceKey(source).inAppOnly(true).build();
    }
    long create(String source) {return creation.create(payload(source,"body")).notificationId();}
    HttpResponse<String> http(String method,String path,long actor,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).header("X-Synthetic-User",Long.toString(actor))
                .header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
    }
    @Test void personalPreferencesKeepOwnerGateResetValidationAndTransactionAtomicity() throws Exception {
        db.update("DELETE FROM notification_preference WHERE user_id=?",user);
        db.update("DELETE FROM notification_preference_owner WHERE user_id=?",user);
        assertEquals(503,http("GET","/v1/users/me/notification-preferences",user,null).statusCode());
        String enabled="{\"changes\":[{\"category\":\"ACTIVITY\",\"channel\":\"WECHAT_OFFICIAL_ACCOUNT\",\"enabled\":true}]}";
        assertEquals(503,http("POST","/v1/users/me/notification-preferences:batch-update",user,enabled).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_preference WHERE user_id=?",Integer.class,user));
        tx.executeWithoutResult(status->context.getBean(NotificationPreferenceBridgeService.class).initializeCanonicalOwner(user));
        assertEquals(400,http("POST","/v1/users/me/notification-preferences:batch-update",user,enabled.replace(",\"enabled\":true", "")).statusCode());
        assertEquals(400,http("POST","/v1/users/me/notification-preferences:batch-update",user,enabled.replace("\"enabled\":true", "\"enabled\":true,\"unexpected\":1")).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_preference WHERE user_id=?",Integer.class,user));
        String duplicate=enabled.replace("}]}", "},{\"category\":\"ACTIVITY\",\"channel\":\"WECHAT_OFFICIAL_ACCOUNT\",\"enabled\":false}]}");
        assertEquals(400,http("POST","/v1/users/me/notification-preferences:batch-update",user,duplicate).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_preference WHERE user_id=?",Integer.class,user));
        assertEquals(400,http("POST","/v1/users/me/notification-preferences:batch-update",user,enabled.replace("\"enabled\":true","\"enabledPresent\":true")).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_preference WHERE user_id=?",Integer.class,user));
        var response=http("POST","/v1/users/me/notification-preferences:batch-update",user,enabled);
        assertEquals(200,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(6,new ObjectMapper().readTree(response.body()).path("items").size());
        assertEquals(1,db.queryForObject("SELECT enabled FROM notification_preference WHERE user_id=?",Integer.class,user));
        var redis=context.getBean(RedisService.class);
        String cache=cn.jualn.miniapp.common.constant.RedisKeyConstant.userSetting(user);
        redis.set(cache,"cached");
        var preferences=context.getBean(NotificationPreferences.class);
        var patch=new NotificationPreferencesBO.Patch(List.of(new NotificationPreferencesBO.Change(
                cn.jualn.miniapp.module.notify.model.NotificationCategory.ACTIVITY,
                cn.jualn.miniapp.module.notify.model.NotificationChannel.WECHAT_OFFICIAL_ACCOUNT,false)));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{preferences.updatePreferences(patch);throw new IllegalStateException("synthetic rollback");}));
        assertEquals(1,db.queryForObject("SELECT enabled FROM notification_preference WHERE user_id=?",Integer.class,user));
        assertEquals("cached",redis.getString(cache));
        tx.executeWithoutResult(status->preferences.updatePreferences(patch));
        assertNull(redis.getString(cache));
        assertEquals(0,db.queryForObject("SELECT enabled FROM notification_preference WHERE user_id=?",Integer.class,user));
        assertEquals(200,http("POST","/v1/users/me/notification-preferences:batch-update",user,enabled.replace("true","null")).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_preference WHERE user_id=?",Integer.class,user));
    }

    @Test void durableAcceptanceChecksIdentityAndRollsBackReceiptAndInboxTogether() {
        var first=creation.create(payload("foundation:one","body"));
        assertEquals(NotificationCreationService.Outcome.CREATED,first.outcome());
        var replay=creation.create(payload("foundation:one","body"));
        assertEquals(NotificationCreationService.Outcome.DUPLICATE,replay.outcome());assertEquals(first.notificationId(),replay.notificationId());
        assertThrows(IllegalStateException.class,()->creation.create(payload("foundation:one","changed")));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{create("foundation:rollback");throw new IllegalStateException("rollback");}));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification_source_receipt",Integer.class));
        assertEquals(1,mapper.selectInboxHead(user));
    }
    @Test void realHttpDeletionAndLegacyReadsShareVisibilityAndNeverWriteReadAt() throws Exception {
        long id=create("foundation:delete");
        assertEquals(204,http("DELETE","/v1/users/me/notifications/"+id,user+1,null).statusCode());
        assertEquals(200,http("GET","/v1/users/me/notifications/"+id,user,null).statusCode());
        assertEquals(204,http("DELETE","/v1/users/me/notifications/"+id,user,null).statusCode());
        assertEquals(204,http("DELETE","/v1/users/me/notifications/"+id,user,null).statusCode());
        assertEquals(204,http("DELETE","/v1/users/me/notifications/opaque-absent",user,null).statusCode());
        assertEquals(404,http("GET","/v1/users/me/notifications/"+id,user,null).statusCode());
        assertEquals(404,http("POST","/v1/users/me/notifications:batch-read",user,"{\"notificationIds\":[\""+id+"\"]}").statusCode());
        assertTrue(http("GET","/v1/notify/me",user,null).body().contains("\"list\":[]"));
        assertNull(db.queryForObject("SELECT read_at FROM notification WHERE id=?",LocalDateTime.class,id));
        assertEquals(0,center.summary(null).unreadCount());assertEquals(1,mapper.selectInboxHead(user));
        assertEquals(NotificationCreationService.Outcome.DUPLICATE,creation.create(payload("foundation:delete","body")).outcome());
        assertEquals(0,center.list(new NotificationCenterBO.Query(null,20,null,null,null)).items().size());
    }
    @Test void exactNinetyDayBoundaryAndExternalOnlyAccessDoNotWaitForCleanup() {
        long expired=create("foundation:expired"),valid=create("foundation:valid");
        tx.executeWithoutResult(status->{
            var now=NotificationObservation.now();
            age(now.minusDays(90),expired);
            age(now.minusDays(90).plusSeconds(1),valid);
            assertNull(mapper.selectOwned(expired,user));assertNotNull(mapper.selectOwned(valid,user));
            assertEquals(1,mapper.countCanonicalUnread(user));
        });
        db.update("INSERT INTO notification(user_id,type,title,content,inbox_generation,created_at) VALUES(?,7,'external','body','CANONICAL',?)",user,LocalDateTime.now().minusDays(200));
        long external=db.queryForObject("SELECT MAX(id) FROM notification",Long.class);
        assertNotNull(mapper.selectOwned(external,user));assertEquals(1,center.batchRead(List.of(Long.toString(external))).changedCount());
        var redis=context.getBean(RedisService.class);
        redis.setCacheProjection(cn.jualn.miniapp.common.constant.RedisKeyConstant.userUnreadCount(user),99L,Duration.ofMinutes(1));
        age(LocalDateTime.now().minusDays(91),valid);
        assertEquals(0,context.getBean(NotifyService.class).countCurrentUnreadNotifications());
        assertEquals(0,center.summary(null).unreadCount());
    }
    @Test void cleanupCanResumeAndSourceReplayCannotRecreateDisposedContent() {
        for(int i=0;i<205;i++)create("foundation:batch:"+i);
        ageAll(NotificationObservation.now().minusDays(91));
        assertEquals(100,cleanup.cleanupBatch(NotificationObservation.now()));
        assertEquals(105,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        cleanup.cleanupBatch(NotificationObservation.now());cleanup.cleanupBatch(NotificationObservation.now());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        assertEquals(NotificationCreationService.Outcome.DUPLICATE,creation.create(payload("foundation:batch:0","body")).outcome());
        assertEquals(205,mapper.selectInboxHead(user));assertEquals(0,center.summary(null).newCount());
    }
    @Test void unknownAndDeadRepairProtectEvidenceAndNeverRetryProvider() {
        long id=create("foundation:unknown");
        age(LocalDateTime.now().minusDays(181),id);
        db.update("INSERT INTO notification_delivery(notification_id,channel,status,template_snapshot) VALUES(?,'WECHAT_OFFICIAL_ACCOUNT','PROCESSING','{}')",id);
        long delivery=db.queryForObject("SELECT id FROM notification_delivery WHERE notification_id=? AND channel='WECHAT_OFFICIAL_ACCOUNT'",Long.class,id);
        db.update("INSERT INTO async_job(job_type,schema_version,subject_type,subject_id,payload,status,next_run_at,max_attempts,dead_at,finished_at) VALUES('notification.wechat-deliver',2,'notification-delivery',?,'{}','DEAD',NOW(),4,NOW(),DATE_SUB(NOW(),INTERVAL 100 DAY))",Long.toString(delivery));
        var deliveries=context.getBean(NotificationDeliveryMapper.class);
        assertEquals(0,context.getBean(AsyncJobMapper.class).deleteCompletedBefore("DEAD",NotificationObservation.now().minusDays(90),200));
        assertEquals(1,deliveries.repairDeadJobResults(50));
        assertEquals(0,context.getBean(AsyncJobMapper.class).deleteCompletedBefore("DEAD",NotificationObservation.now().minusDays(90),200));
        assertEquals("UNKNOWN",deliveries.selectDelivery(delivery).getStatus());
        cleanup.cleanupBatch(NotificationObservation.now());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        assertNull(db.queryForObject("SELECT content_payload FROM notification WHERE id=?",String.class,id));
        assertNull(deliveries.selectDelivery(delivery).getTemplateSnapshot());
    }
    @Test void realLeaseRecoveryAndPendingCancellationKeepTheirDurableState() {
        var jobs=context.getBean(JobService.class);var claims=context.getBean(JobClaimService.class);
        long id=tx.execute(status->jobs.create(new JobDefinition("notification.plan.fanout",1,"synthetic-operation","foundation:lease","notify-plan","999",Map.of("planId",999),NotificationObservation.now(),3)));
        assertEquals(1,claims.claimDue("first-attempt",1).size());
        db.update("UPDATE async_job SET lease_until=? WHERE id=?",LocalDateTime.now().minusMinutes(5),id);
        assertEquals(1,claims.recoverExpired(20,new RetrySchedule()));
        assertEquals("PENDING",jobs.inspect(id).getStatus());
        assertEquals(Boolean.TRUE,tx.execute(status->jobs.cancelPending("notification.plan.fanout","foundation:lease")));
        assertEquals("CANCELLED",jobs.inspect(id).getStatus());
    }

    @Test void concurrentReadAndDeleteSerializeWithoutInventingReadFacts() throws Exception {
        long id=create("foundation:race");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var read=executor.submit(()->{
                UserContext.setUserId(user);
                try {tx.executeWithoutResult(status->{mapper.ensureInboxCounter(user);mapper.lockInboxHead(user);entered.countDown();
                    try {if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                    center.batchRead(List.of(Long.toString(id)));
                });} finally {UserContext.clear();}
            });
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var deletion=executor.submit(()->{UserContext.setUserId(user);try{context.getBean(PersonalNotificationInbox.class).delete(Long.toString(id));}finally{UserContext.clear();}});
            assertThrows(java.util.concurrent.TimeoutException.class,()->deletion.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();read.get(5,java.util.concurrent.TimeUnit.SECONDS);deletion.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertNull(mapper.selectOwned(id,user));assertNotNull(db.queryForObject("SELECT read_at FROM notification WHERE id=?",LocalDateTime.class,id));
            assertEquals(0,center.summary(null).unreadCount());
        } finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void expiryStopsPendingButDeletionDoesNotCancelExternalIntent() {
        long deleted=create("foundation:pending-deleted"),expired=create("foundation:pending-expired");
        db.update("INSERT INTO notification_delivery(notification_id,channel,status) VALUES(?,'WECHAT_OFFICIAL_ACCOUNT','PENDING'),(?,'WECHAT_OFFICIAL_ACCOUNT','PENDING')",deleted,expired);
        context.getBean(PersonalNotificationInbox.class).delete(Long.toString(deleted));
        age(NotificationObservation.now().minusDays(90),expired);
        var deliveries=context.getBean(NotificationDeliveryMapper.class);
        long deletedDelivery=db.queryForObject("SELECT id FROM notification_delivery WHERE notification_id=? AND channel='WECHAT_OFFICIAL_ACCOUNT'",Long.class,deleted);
        long expiredDelivery=db.queryForObject("SELECT id FROM notification_delivery WHERE notification_id=? AND channel='WECHAT_OFFICIAL_ACCOUNT'",Long.class,expired);
        assertEquals(1,deliveries.startProviderAttempt(deletedDelivery,NotificationObservation.now()));
        assertEquals(0,deliveries.startProviderAttempt(expiredDelivery,NotificationObservation.now()));
        var maintenance=context.getBean(NotificationLifecycleMapper.class);
        assertEquals(1,maintenance.expireDelivery(expiredDelivery,NotificationObservation.now()));
        assertEquals("SKIPPED",deliveries.selectDelivery(expiredDelivery).getStatus());
        assertEquals("PROCESSING",deliveries.selectDelivery(deletedDelivery).getStatus());
        // An expired canonical IN_APP intent which has not entered the stream is still protected by the same rule.
        db.update("INSERT INTO notification(user_id,type,title,content,inbox_generation,created_at) VALUES(?,7,'planned','body','CANONICAL',?)",user,NotificationObservation.now().minusDays(90));
        long planned=db.queryForObject("SELECT MAX(id) FROM notification",Long.class);
        db.update("INSERT INTO notification_delivery(notification_id,channel,status) VALUES(?,'IN_APP','PENDING')",planned);
        assertNull(mapper.selectOwned(planned,user));
        tx.executeWithoutResult(status->context.getBean(NotificationInboxService.class).enter(db.queryForObject("SELECT id,user_id FROM notification WHERE id=?",(r,n)->Notification.builder().id(r.getLong(1)).userId(r.getLong(2)).build(),planned)));
        assertNull(db.queryForObject("SELECT inbox_seq FROM notification WHERE id=?",Long.class,planned));
    }
    @Test void fixedBoundarySurvivesDeletionAndBatchHiddenItemRollsBackAllWrites() {
        long first=create("foundation:boundary:first"),second=create("foundation:boundary:second");
        var page=center.list(new NotificationCenterBO.Query(null,1,null,null,null));
        context.getBean(PersonalNotificationInbox.class).delete(Long.toString(second));
        assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->center.batchRead(List.of(Long.toString(first),Long.toString(second))));
        assertNull(db.queryForObject("SELECT read_at FROM notification WHERE id=?",LocalDateTime.class,first));
        assertEquals(Long.toString(first),center.list(new NotificationCenterBO.Query(page.nextCursor(),1,null,null,null)).items().get(0).id());
        assertEquals(1,center.readThrough(page.headCursor()).changedCount());
        assertEquals(0,center.readThrough(page.headCursor()).changedCount());
        assertEquals(0,center.summary(page.headCursor()).newCount());
    }

    @Test void controlledUnknownDispositionRecordsOperatorAndNeverInventsDelivery() {
        long id=create("foundation:resolution");
        age(NotificationObservation.now().minusDays(100),id);
        db.update("INSERT INTO notification_delivery(notification_id,channel,status) VALUES(?,'WECHAT_OFFICIAL_ACCOUNT','UNKNOWN')",id);
        long delivery=db.queryForObject("SELECT id FROM notification_delivery WHERE notification_id=? AND channel='WECHAT_OFFICIAL_ACCOUNT'",Long.class,id);
        var service=context.getBean(NotificationDeliveryResolutionService.class);
        service.resolve(delivery,1,NotificationDeliveryResolutionService.Conclusion.ACCEPT_UNKNOWN,"incident-foundation-1");
        service.resolve(delivery,1,NotificationDeliveryResolutionService.Conclusion.ACCEPT_UNKNOWN,"incident-foundation-1");
        assertEquals("UNKNOWN",context.getBean(NotificationDeliveryMapper.class).selectDelivery(delivery).getStatus());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification_delivery_resolution WHERE operator_id=1",Integer.class));
        assertThrows(IllegalStateException.class,()->service.resolve(delivery,1,NotificationDeliveryResolutionService.Conclusion.CONFIRMED_DELIVERED,"different-evidence"));
        cleanup.cleanupBatch(NotificationObservation.now());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        db.update("UPDATE notification_delivery_resolution SET resolved_at=?",NotificationObservation.now().minusDays(31));
        cleanup.cleanupBatch(NotificationObservation.now());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        assertEquals("ACCEPT_UNKNOWN",db.queryForObject("SELECT conclusion FROM notification_delivery_resolution",String.class));
    }

    @Test void simultaneousCreationUsesOneIdentityAndOneInboxPosition() throws Exception {
        var gate=new java.util.concurrent.CyclicBarrier(2);var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<NotificationCreator.Result> work=()->{gate.await(5,java.util.concurrent.TimeUnit.SECONDS);return creation.create(payload("foundation:concurrent","body"));};
            var first=executor.submit(work);var second=executor.submit(work);
            var a=first.get(5,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(a.notificationId(),b.notificationId());
            assertEquals(Set.of(NotificationCreator.Outcome.CREATED,NotificationCreator.Outcome.DUPLICATE),Set.of(a.outcome(),b.outcome()));
            assertEquals(1,mapper.selectInboxHead(user));
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void scheduledMaintenanceActuallyConsumesExpiredBacklog() throws Exception {
        create("foundation:scheduled");ageAll(NotificationObservation.now().minusDays(91));
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(5);scheduler.initialize();
        var processor=new org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor();
        processor.setScheduler(scheduler);processor.setEmbeddedValueResolver(value->value.replace("${notification.cleanup.interval:60000}","25"));
        try {
            processor.postProcessAfterInitialization(cleanup,"notificationLifecycle");processor.afterSingletonsInstantiated();
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class)>0 && System.nanoTime()<deadline) Thread.sleep(20);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification_source_receipt",Integer.class));
        } finally {processor.destroy();scheduler.shutdown();}
    }

    @Test void channelRestrictionCannotEnableUnavailableExternalDelivery() {
        var command=NotifyPayload.builder().receiverId(user).type(NotifyType.SYSTEM).title("restricted")
            .content("body").sourceKey("foundation:channels").allowedChannels(Set.of(cn.jualn.miniapp.module.notify.model.NotificationChannel.WECHAT_OFFICIAL_ACCOUNT)).build();
        assertEquals(NotificationCreator.Outcome.CREATED,creation.create(command).outcome());
        assertEquals(0,mapper.selectInboxHead(user));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_delivery",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM async_job",Integer.class));
    }

    private void age(LocalDateTime createdAt,long id) {
        tx.executeWithoutResult(status->{
            db.update("UPDATE notification SET created_at=? WHERE id=?",createdAt,id);
            db.update("UPDATE notification_source_receipt SET original_created_at=? WHERE notification_id=?",createdAt,id);
        });
    }
    private void ageAll(LocalDateTime createdAt) {
        tx.executeWithoutResult(status->{
            db.update("UPDATE notification SET created_at=?",createdAt);
            db.update("UPDATE notification_source_receipt SET original_created_at=?",createdAt);
        });
    }

    @Test void unclassifiedHistoryIsProtectedBeforeBatchLimitAndDirectWritesCannotBypassIt() {
        var old=NotificationObservation.now().minusDays(200);
        for(int i=0;i<120;i++) db.update("INSERT INTO notification(user_id,type,title,content,inbox_generation,is_read,read_at,inbox_seq,created_at) VALUES(?,1,'unknown history','original evidence','LEGACY',1,?,?,?)",user,old.plusDays(1),i+1,old);
        db.update("INSERT INTO notification(user_id,type,title,content,source_key,inbox_generation,is_read,read_at,inbox_seq,created_at) VALUES(?,6,'blank source','original evidence',?,'LEGACY',1,?,121,?)",user,"\t",old.plusDays(1),old);
        db.update("INSERT INTO notification_inbox_counter(user_id,head_seq) VALUES(?,121)",user);
        long unknown=db.queryForObject("SELECT MIN(id) FROM notification",Long.class);
        long disposed=create("history:classified");age(old,disposed);
        create("history:live");
        db.update("INSERT INTO notification_delivery(notification_id,channel,status,template_snapshot) VALUES(?,'WECHAT_OFFICIAL_ACCOUNT','UNKNOWN','{}')",unknown);
        long delivery=db.queryForObject("SELECT id FROM notification_delivery WHERE notification_id=?",Long.class,unknown);
        var maintenance=context.getBean(NotificationLifecycleMapper.class);
        assertEquals(0,maintenance.dispose(unknown,NotificationObservation.now()));
        assertEquals(0,maintenance.redact(unknown,NotificationObservation.now()));
        assertEquals(0,maintenance.redactTemplates(unknown));
        assertEquals(1,cleanup.cleanupBatch(NotificationObservation.now()));
        assertEquals(122,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        assertEquals(121,maintenance.unclassifiedBacklog(NotificationObservation.now()));
        assertEquals("original evidence",db.queryForObject("SELECT content FROM notification WHERE id=?",String.class,unknown));
        assertEquals(old.plusDays(1),db.queryForObject("SELECT read_at FROM notification WHERE id=?",LocalDateTime.class,unknown));
        assertEquals("{}",context.getBean(NotificationDeliveryMapper.class).selectDelivery(delivery).getTemplateSnapshot());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM notification_source_receipt",Integer.class));
        assertEquals(123,mapper.selectInboxHead(user));assertEquals(1,center.summary(null).unreadCount());
        context.getBean(NotificationDeliveryResolutionService.class).resolve(delivery,1,NotificationDeliveryResolutionService.Conclusion.ACCEPT_UNKNOWN,"history-classification-pending");
        db.update("UPDATE notification_delivery_resolution SET resolved_at=?",NotificationObservation.now().minusDays(31));
        cleanup.cleanup();
        assertNotNull(db.queryForObject("SELECT id FROM notification WHERE id=?",Long.class,unknown));
        assertEquals("UNKNOWN",context.getBean(NotificationDeliveryMapper.class).selectDelivery(delivery).getStatus());
        assertEquals(121.0,context.getBean(io.micrometer.core.instrument.MeterRegistry.class).get("jualn.notification.cleanup.unclassified").gauge().value());
    }

    @Test void disposalRequiresMatchingSourceReceiptAndCannotForgetAnExistingBarrier() {
        long id=create("history:receipt");var old=NotificationObservation.now().minusDays(100);age(old,id);
        var maintenance=context.getBean(NotificationLifecycleMapper.class);
        db.update("UPDATE notification_source_receipt SET notification_id=? WHERE notification_id=?",id+900,id);
        assertEquals(0,maintenance.dispose(id,NotificationObservation.now()));
        assertEquals(0,cleanup.cleanupBatch(NotificationObservation.now()));
        db.update("UPDATE notification_source_receipt SET notification_id=?,original_created_at=?",id,old.plusDays(1));
        assertEquals(0,maintenance.dispose(id,NotificationObservation.now()));
        db.update("UPDATE notification_source_receipt SET original_created_at=?",old);
        assertEquals(1,cleanup.cleanupBatch(NotificationObservation.now()));
        assertNotNull(db.queryForObject("SELECT disposed_at FROM notification_source_receipt",LocalDateTime.class));
        assertEquals(NotificationCreator.Outcome.DUPLICATE,creation.create(payload("history:receipt","body")).outcome());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        assertEquals(1,mapper.selectInboxHead(user));
    }

    @Test void failedDispositionRollsBackContentCascadeAndSourceEvidenceTogether() {
        long id=create("history:rollback");age(NotificationObservation.now().minusDays(100),id);
        db.update("INSERT INTO notification_delivery(notification_id,channel,status) VALUES(?,'WECHAT_OFFICIAL_ACCOUNT','FAILED')",id);
        db.execute("CREATE TRIGGER history_disposition_fail BEFORE UPDATE ON notification_source_receipt FOR EACH ROW BEGIN IF NEW.disposed_at IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic disposition failure'; END IF; END");
        try {assertThrows(org.springframework.dao.DataAccessException.class,()->cleanup.cleanupBatch(NotificationObservation.now()));}
        finally {db.execute("DROP TRIGGER history_disposition_fail");}
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM notification_delivery",Integer.class));
        assertEquals("FAILED",db.queryForObject("SELECT status FROM notification_delivery WHERE channel='WECHAT_OFFICIAL_ACCOUNT'",String.class));
        assertNull(db.queryForObject("SELECT disposed_at FROM notification_source_receipt",LocalDateTime.class));
        assertEquals(1,cleanup.cleanupBatch(NotificationObservation.now()));
        assertEquals(NotificationCreator.Outcome.DUPLICATE,creation.create(payload("history:rollback","body")).outcome());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_delivery",Integer.class));
        assertEquals(0,center.summary(null).unreadCount());assertEquals(1,mapper.selectInboxHead(user));
    }

    @Test void anApprovedRedactedDeliveryCanCloseWithoutReopeningItsSource() {
        var command=NotifyPayload.builder().receiverId(user).senderId(7L).type(NotifyType.SYSTEM).title("frozen").content("body").sourceKey("history:redaction").inAppOnly(true).build();
        long id=creation.create(command).notificationId();age(NotificationObservation.now().minusDays(200),id);
        db.update("INSERT INTO notification_delivery(notification_id,channel,status,template_snapshot) VALUES(?,'WECHAT_OFFICIAL_ACCOUNT','UNKNOWN','{}')",id);
        long delivery=db.queryForObject("SELECT id FROM notification_delivery WHERE notification_id=? AND channel='WECHAT_OFFICIAL_ACCOUNT'",Long.class,id);
        cleanup.cleanupBatch(NotificationObservation.now());
        assertNull(db.queryForObject("SELECT sender_id FROM notification WHERE id=?",Long.class,id));
        assertNull(db.queryForObject("SELECT content_payload FROM notification WHERE id=?",String.class,id));
        context.getBean(NotificationDeliveryResolutionService.class).resolve(delivery,1,NotificationDeliveryResolutionService.Conclusion.ACCEPT_UNKNOWN,"history-known-source");
        db.update("UPDATE notification_delivery_resolution SET resolved_at=?",NotificationObservation.now().minusDays(31));
        assertEquals(1,cleanup.cleanupBatch(NotificationObservation.now()));
        assertEquals(NotificationCreator.Outcome.DUPLICATE,creation.create(command).outcome());
        assertEquals("ACCEPT_UNKNOWN",db.queryForObject("SELECT conclusion FROM notification_delivery_resolution",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
    }

    @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableTransactionManagement
    @MapperScan(basePackageClasses=NotificationMapper.class)
    static class Config implements WebMvcConfigurer {
        @Bean TomcatServletWebServerFactory server() throws Exception {var f=new TomcatServletWebServerFactory(0);f.setAddress(InetAddress.getByName("127.0.0.1"));return f;}
        @Bean DispatcherServlet dispatcherServlet(){return new DispatcherServlet();}
        @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,Object handler){UserContext.setUserId(Long.parseLong(request.getHeader("X-Synthetic-User")));return true;}
            public void afterCompletion(jakarta.servlet.http.HttpServletRequest a,jakarta.servlet.http.HttpServletResponse b,Object c,Exception d){UserContext.clear();}
        });}
        @Override public void configureMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(json()));}
        @Bean ObjectMapper json(){return new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder());}
        @Bean DataSource dataSource(){return new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true","root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean TransactionTemplate tx(DataSourceTransactionManager tm){return new TransactionTemplate(tm);}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds)throws Exception{var f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);f.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig().setMetaObjectHandler(new MybatisPlusConfig().metaObjectHandler()));f.setMapperLocations(new ClassPathResource("mapper/NotificationMapper.xml"),new ClassPathResource("mapper/AsyncJobMapper.xml"),new ClassPathResource("mapper/NotificationPreferenceMapper.xml"),new ClassPathResource("mapper/NotificationPreferenceOwnerMapper.xml"));return f.getObject();}
        @Bean MapperFactoryBean<AsyncJobMapper> jobMapper(SqlSessionFactory sf){var f=new MapperFactoryBean<>(AsyncJobMapper.class);f.setSqlSessionFactory(sf);return f;}
        @Bean io.micrometer.core.instrument.MeterRegistry meters(){return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();}
        @Bean LettuceConnectionFactory connection(){return new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(java.util.Objects.requireNonNull(System.getProperty("notification.test.redisPort"),"Owned Redis port required")));}
        @Bean RedisService redis(LettuceConnectionFactory f,ObjectMapper json){return new RedisService(new RedisConfig().redisTemplate(f),json);}
        @Bean JobService jobs(AsyncJobMapper mapper,ObjectMapper json){return new JobService(mapper,json);}
        @Bean JobClaimService claims(AsyncJobMapper mapper){return new JobClaimService(mapper);}
        @Bean NotificationInboxService inbox(NotificationMapper mapper){return new NotificationInboxService(mapper);}
        @Bean NotificationCreationService creation(NotificationMapper m,NotificationInboxService inbox,RedisService redis,ObjectMapper json,NotificationPreferenceMapper p,NotificationPreferenceOwnerMapper o,io.micrometer.core.instrument.MeterRegistry meters,NotificationDeliveryService delivery){
            var settings=mock(cn.jualn.miniapp.module.setting.service.SettingService.class);when(settings.getSettingByUserId(anyLong())).thenReturn(cn.jualn.miniapp.module.setting.bo.UserSettingBO.builder().build());
            return new NotificationCreationService(m,
                meters,
                inbox,
                delivery,
                settings,
                redis,
                mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class),
                json,
                p,
                o,
                new cn.jualn.miniapp.module.user.service.UserNotificationIdentityService(mock(cn.jualn.miniapp.module.user.mapper.UserProfileMapper.class)),
                List.of());}
        @Bean NotificationDeliveryService delivery(NotificationDeliveryMapper d,NotificationMapper n,NotificationInboxService inbox,JobService jobs,ObjectMapper json,NotificationPreferenceMapper p,TransactionTemplate tx,io.micrometer.core.instrument.MeterRegistry meters){return new NotificationDeliveryServiceImpl(d,n,meters,inbox,jobs,json,cn.jualn.miniapp.module.notify.TestNotificationChannels.create(mock(cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService.class),mock(cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService.class),json),mock(cn.jualn.miniapp.module.setting.service.SettingService.class),p,tx,new NotificationEligibility(List.of(),mock(NotifyPlanMapper.class)));}
        @Bean NotifyService notify(NotificationMapper m,NotificationAcceptance creation,RedisService redis,TransactionTemplate tx,JobService jobs,io.micrometer.core.instrument.MeterRegistry meters){return new NotifyServiceImpl(m,meters,mock(NotifyPlanMapper.class),mock(cn.jualn.miniapp.module.user.service.UserService.class),List.of(),redis,Mappers.getMapper(NotifyConverter.class),jobs,tx,creation,List.of(),mock(cn.jualn.miniapp.module.notify.reminder.ReminderReconcileService.class));}
        @Bean NotificationCenterService center(NotificationMapper m,RedisService redis,TransactionTemplate tx,ObjectMapper json,io.micrometer.core.instrument.MeterRegistry meters){return new NotificationCenterServiceImpl(m,new NotificationStreamCursorCodec("foundation-integration-shared-cursor-secret"),new NotificationSnapshotProjection(json,meters),redis,tx,meters);}
        @Bean PersonalNotificationInbox canonical(NotificationMapper m,RedisService redis){return new PersonalNotificationInbox(m,redis);}
        @Bean NotificationPreferenceBridgeService preferenceBridge(NotificationPreferenceMapper p,NotificationPreferenceOwnerMapper o){return new cn.jualn.miniapp.module.notify.service.preference.NotificationPreferenceBridgeServiceImpl(p,o);}
        @Bean NotificationPreferences personalPreferences(NotificationPreferenceMapper p,RedisService redis,NotificationPreferenceBridgeService bridge){return new NotificationPreferences(p,redis,bridge);}
        @Bean CanonicalNotificationController canonicalController(PersonalNotificationInbox c,NotificationCenterService n,NotificationPreferences preferences){return new CanonicalNotificationController(c,
                preferences,mock(NotificationCapabilityQueries.class),new NotificationPersonalConverter(),n,Mappers.getMapper(NotificationCenterConverter.class));}
        @Bean NotifyController legacyController(NotifyService n){return new NotifyController(n,Mappers.getMapper(NotifyConverter.class));}
        @Bean cn.jualn.miniapp.module.authorization.service.BusinessAuthorizationLease recoveryAuthority() {
            var lease=mock(cn.jualn.miniapp.module.authorization.service.BusinessAuthorizationLease.class);
            var decision=mock(cn.jualn.miniapp.module.authorization.model.RbacModels.Decision.class);
            var grant=mock(cn.jualn.miniapp.module.authorization.model.RbacModels.Grant.class);
            when(grant.assignmentId()).thenReturn("foundation-root");when(decision.grants()).thenReturn(List.of(grant));
            when(decision.owns("foundation-root")).thenReturn(true);
            doAnswer(call->{((java.util.function.Consumer<cn.jualn.miniapp.module.authorization.model.RbacModels.Decision>)call.getArgument(1)).accept(decision);return null;})
                .when(lease).require(anyLong(),any());
            return lease;
        }
        @Bean NotificationDeliveryResolutionService resolution(cn.jualn.miniapp.module.authorization.service.BusinessAuthorizationLease authority,NotificationLifecycleMapper mapper){return new NotificationDeliveryResolutionService(authority,mapper);}
        @Bean GlobalExceptionHandler errors(){return new GlobalExceptionHandler();}
        @Bean NotificationLifecycleService cleanup(NotificationLifecycleMapper l,NotificationMapper m,TransactionTemplate tx,io.micrometer.core.instrument.MeterRegistry meters){return new NotificationLifecycleService(l,m,tx,meters);}
    }
}
