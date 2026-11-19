package cn.jualn.miniapp.module.notify.service;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationSnapshotProjection;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService;

import cn.jualn.miniapp.module.activity.service.ActivityDeliveryEligibility;
import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.config.MybatisPlusConfig;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.*;
import cn.jualn.miniapp.module.activity.service.*;
import cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService;
import cn.jualn.miniapp.module.notify.converter.NotifyConverter;
import cn.jualn.miniapp.module.notify.entity.*;
import cn.jualn.miniapp.module.notify.mapper.*;
import cn.jualn.miniapp.module.notify.reminder.*;
import cn.jualn.miniapp.module.notify.service.impl.*;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic fixtures only, fully migrated disposable MySQL 8.4. No application configuration is loaded. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/activity_reminder_test[0-9]*")
class ActivityReminderDatabaseTest {
    JdbcTemplate jdbc; TransactionTemplate tx; SqlSessionTemplate sql;
    NotifyPlanMapper plans; NotificationMapper notifications;
    ActivityReminderSubjectStatus status; ReminderReconcileService reconcile; NotifyServiceImpl notify;
    ActivityEnrollmentService enrollment; ActivityEnrollmentMapper recipients;
    JobService jobs; WxMpNoticeSendService provider; NotificationDeliveryServiceImpl delivery;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ActivityReminderPolicy policy = new ActivityReminderPolicy();
    final long activityId=71001, firstUser=72001;
    final LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
    String jdbcUrl() { return System.getProperty("event.test.jdbcUrl"); }
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(jdbcUrl()
                +"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        assertTrue(jdbc.queryForObject("SELECT VERSION()",String.class).startsWith("8.4."));
        assertEquals("84",jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",String.class));
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        factory.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig()
                .setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig().setLogicNotDeleteValue("NULL").setLogicDeleteValue("NOW()"))
                .setMetaObjectHandler(new MybatisPlusConfig().metaObjectHandler()));
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        var sessionFactory=factory.getObject();
        for(var mapper:List.of(ActivityEnrollmentMapper.class,NotificationDeliveryMapper.class,NotificationPreferenceMapper.class,
                NotificationPreferenceOwnerMapper.class,UserProfileMapper.class,NotifyPlanMapper.class,AsyncJobMapper.class))
            if(!config.hasMapper(mapper)) config.addMapper(mapper);
        sql=new SqlSessionTemplate(sessionFactory);
        plans=sql.getMapper(NotifyPlanMapper.class); notifications=sql.getMapper(NotificationMapper.class);
        recipients=sql.getMapper(ActivityEnrollmentMapper.class);
        var activities=sql.getMapper(ActivityMapper.class);
        var timelines=mock(TimelineService.class);
        when(timelines.listTimelinesByTarget(eq(TargetType.ACTIVITY),anyLong())).thenAnswer(i -> nodes());
        status=new ActivityReminderSubjectStatus(activities,timelines,recipients);
        enrollment=mock(ActivityEnrollmentService.class);
        when(enrollment.subscriberOrRegisteredUpperBound(anyLong())).thenAnswer(i -> recipients.selectSubscriberOrRegisteredUpperBound(i.getArgument(0)));
        when(enrollment.listSubscriberOrRegisteredUserIds(anyLong(),anyLong(),anyLong(),anyInt())).thenAnswer(i -> recipients.selectSubscriberOrRegisteredUserIds(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3)));
        when(enrollment.listEnrolledUserIds(anyLong(),anyLong(),anyInt())).thenAnswer(i -> jdbc.queryForList("SELECT user_id FROM activity_enrollment WHERE activity_id=? AND status=1 AND notify_enable=1 AND user_id>? ORDER BY user_id LIMIT ?",Long.class,i.getArgument(0),i.getArgument(1),i.getArgument(2)));
        jobs=proxied(new JobService(sql.getMapper(AsyncJobMapper.class),json));
        reconcile=proxied(new ReminderReconcileService(plans,jobs));
        var inbox=proxied(new NotificationInboxService(notifications)); var meters=new SimpleMeterRegistry();
        provider=mock(WxMpNoticeSendService.class);
        delivery=new NotificationDeliveryServiceImpl(sql.getMapper(NotificationDeliveryMapper.class),notifications,meters,inbox,jobs,json,cn.jualn.miniapp.module.notify.TestNotificationChannels.create(provider,mock(cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService.class),json),mock(SettingService.class),sql.getMapper(NotificationPreferenceMapper.class),tx,new cn.jualn.miniapp.module.notify.service.NotificationEligibility(java.util.List.of(new ActivityDeliveryEligibility(status)),plans));
        var validator=mock(SubjectExistenceService.class); when(validator.exists(eq(TargetType.ACTIVITY),anyLong())).thenReturn(true);
        notify=new NotifyServiceImpl(notifications,
                    meters,
                    plans,
                    mock(UserService.class),
                    java.util.List.of(new cn.jualn.miniapp.module.activity.service.ActivityNotificationSource(enrollment),new cn.jualn.miniapp.module.publicevent.service.PublicEventNotificationSource(mock(PublicEventSubscriptionService.class))),
                    mock(RedisService.class),
                    mock(NotifyConverter.class),
                    jobs,
                    tx,
                    new cn.jualn.miniapp.module.notify.service.impl.NotificationCreationService(notifications,
                meters,
                inbox,
                delivery,
                mock(SettingService.class),
                mock(RedisService.class),
                validator,
                json,
                sql.getMapper(NotificationPreferenceMapper.class),
                sql.getMapper(NotificationPreferenceOwnerMapper.class),
                new cn.jualn.miniapp.module.user.service.UserNotificationIdentityService(sql.getMapper(UserProfileMapper.class)),
                java.util.List.of(new cn.jualn.miniapp.module.activity.service.ActivityNotificationSource(enrollment),new cn.jualn.miniapp.module.publicevent.service.PublicEventNotificationSource(mock(PublicEventSubscriptionService.class)))),
                    List.of(status),
                    reconcile);
        clear();
        jdbc.update("INSERT INTO activity(id,user_id,title,publish_status,lifecycle_status,registration_mode,participant_mode) VALUES (?,?,'new title',1,0,3,1)",activityId,firstUser);
        for(long uid=firstUser;uid<firstUser+8;uid++) {
            jdbc.update("INSERT INTO user_profile(id,openid) VALUES (?,?)",uid,"reminder-"+uid);
            jdbc.update("INSERT INTO notification_preference_owner(user_id,owner,migrated_at) VALUES (?,'CANONICAL',NOW(3))",uid);
        }
    }
    @SuppressWarnings("unchecked")
    <T> T proxied(T implementation) {
        var factory=new org.springframework.aop.framework.ProxyFactory(implementation);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(tx.getTransactionManager(),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
    @AfterEach void cleanup() { if(jdbc!=null) clear(); }
    void clear() {
        jdbc.update("DELETE FROM notification_source_receipt");
        jdbc.update("DELETE FROM notification WHERE user_id BETWEEN ? AND ?",firstUser,firstUser+200);
        jdbc.update("DELETE FROM notification_inbox_counter WHERE user_id BETWEEN ? AND ?",firstUser,firstUser+200);
        jdbc.update("DELETE FROM async_job WHERE job_type LIKE 'notification.%'");
        jdbc.update("DELETE FROM notify_plan WHERE source_id=?",activityId);
        jdbc.update("DELETE FROM activity_registration WHERE activity_id=?",activityId);
        jdbc.update("DELETE FROM activity_enrollment WHERE activity_id=?",activityId);
        jdbc.update("DELETE FROM timeline WHERE target_type=2 AND target_id=?",activityId);
        jdbc.update("DELETE FROM activity WHERE id=?",activityId);
        jdbc.update("DELETE FROM notification_preference WHERE user_id BETWEEN ? AND ?",firstUser,firstUser+200);
        jdbc.update("DELETE FROM notification_preference_owner WHERE user_id BETWEEN ? AND ?",firstUser,firstUser+200);
        jdbc.update("DELETE FROM user_profile WHERE id BETWEEN ? AND ?",firstUser,firstUser+200);
    }
    void subscriber(long uid,int enabled) { jdbc.update("INSERT INTO activity_enrollment(activity_id,user_id,status,notify_enable) VALUES (?,?,1,?)",activityId,uid,enabled); }
    void registrant(long uid,int state) {
        jdbc.update("INSERT INTO activity_registration(activity_id,user_id,form_data,status,submitted_at,invalidated_at,invalidated_by,invalid_reason) VALUES (?,?,JSON_OBJECT(),?,NOW(),IF(?=3,NOW(),NULL),IF(?=3,9,NULL),IF(?=3,'fixture',NULL))",activityId,uid,state,state,state,state);
    }
    void node(String semantic,LocalDateTime time,boolean range) {
        jdbc.update("INSERT INTO timeline(target_type,target_id,node_key,node_type,label,start_time,start_precision,end_time,end_precision) VALUES (2,?,?,?,?,?,2,?,?)",activityId,semantic,semantic,"display",time,range?time.plusHours(2):null,range?2:0);
    }
    List<TimelineFactBO> nodes() {
        return jdbc.query("SELECT * FROM timeline WHERE target_type=2 AND target_id=? ORDER BY id",(rs,n) -> TimelineFactBO.builder()
                .id(rs.getLong("id")).nodeType(rs.getString("node_type")).label(rs.getString("label"))
                .startTime(rs.getObject("start_time",LocalDateTime.class))
                .endTime(rs.getObject("end_time",LocalDateTime.class))
                .startPrecision(rs.getInt("start_precision")).endPrecision(rs.getInt("end_precision")).location(rs.getString("location")).build(),activityId);
    }
    void reconcileAt(LocalDateTime evaluated,long version) {
        tx.executeWithoutResult(t -> {
            var activity=sql.getMapper(ActivityMapper.class).lockReminderStatus(activityId);
            reconcile.reconcile(1,activityId,version,policy.evaluate(activity,nodes(),evaluated),evaluated);
        });
    }
    long plan(int type) { return jdbc.queryForObject("SELECT id FROM notify_plan WHERE source_id=? AND notify_type=? AND status=0 ORDER BY id DESC LIMIT 1",Long.class,activityId,type); }
    List<Long> received(int type) { return jdbc.queryForList("SELECT user_id FROM notification WHERE type=? ORDER BY user_id",Long.class,type); }
    @Test void realUnionSqlUsesSubmittedOnlyAndKeepsRelationsIndependent() {
        subscriber(firstUser,1); registrant(firstUser+1,1); subscriber(firstUser+2,1); registrant(firstUser+2,1);
        registrant(firstUser+3,2); registrant(firstUser+4,3); subscriber(firstUser+5,0); registrant(firstUser+5,1);
        assertEquals(List.of(firstUser,firstUser+1,firstUser+2,firstUser+5),recipients.selectSubscriberOrRegisteredUserIds(activityId,0,firstUser+7,100));
        jdbc.update("UPDATE activity_enrollment SET status=2 WHERE user_id=?",firstUser+2);
        assertTrue(recipients.isSubmittedRegistrant(activityId,firstUser+2));
        jdbc.update("UPDATE activity_registration SET status=2 WHERE user_id=?",firstUser+2);
        assertFalse(recipients.isNotifyEnabledSubscriber(activityId,firstUser+2));
        assertEquals(List.of(firstUser+1),recipients.selectSubscriberOrRegisteredUserIds(activityId,firstUser,firstUser+1,100));
    }
    @Test void threeReminderFanoutsDeduplicateAndNewTypeReloadsInActivityBox() throws Exception {
        subscriber(firstUser,1); registrant(firstUser+1,1); subscriber(firstUser+2,1); registrant(firstUser+2,1);
        subscriber(firstUser+3,0); registrant(firstUser+3,1); registrant(firstUser+4,2);
        node("REGISTRATION_START",now.plusHours(2),false); node("REGISTRATION_END",now.plusDays(2),false); node("ACTIVITY_START",now.plusHours(3),true);
        reconcileAt(now,1);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM notify_plan",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM async_job",Integer.class));
        for(int type:List.of(23,9,8)) { long id=plan(type); notify.broadcastPlanFanOut(id); notify.broadcastPlanFanOut(id); }
        assertEquals(List.of(firstUser,firstUser+2),received(23)); assertEquals(List.of(firstUser,firstUser+2),received(9));
        assertEquals(List.of(firstUser,firstUser+1,firstUser+2,firstUser+3),received(8));
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE channel='IN_APP' AND status='DELIVERED'",Integer.class));
        var row=notifications.selectCanonicalInbox(firstUser,null,null,List.of(23),false,10).get(0);
        var view=new NotificationSnapshotProjection(json,new SimpleMeterRegistry()).item(row);
        assertEquals("ACTIVITY",view.category()); assertEquals("ACTIVITY_REGISTRATION_START_REMINDER",view.type());
        assertEquals("ACTIVITY_DETAIL",view.target().type()); assertEquals("new title",view.presentation().subjectTitle());
        assertTrue(notifications.selectCanonicalInbox(firstUser,null,null,null,true,10).isEmpty());
        reconcileAt(now,2); assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM notify_plan",Integer.class));
    }
    @Test void overdueMetadataEditPreservesPlanAndUsesNewTitleAndLocationWithoutRevokingHistory() {
        subscriber(firstUser,1); node("ACTIVITY_START",now.plusMinutes(30),false);
        reconcileAt(now.minusHours(2),1); long id=plan(8);
        jdbc.update("UPDATE notify_plan SET title='old title',subject_location='old room' WHERE id=?",id);
        jdbc.update("UPDATE activity SET title=?,contract_version=2 WHERE id=?","题".repeat(200),activityId);
        jdbc.update("UPDATE timeline SET location='new room' WHERE target_id=?",activityId);
        reconcileAt(now,2); assertEquals(id,plan(8));
        notify.broadcastPlanFanOut(id);
        assertEquals("题".repeat(200),jdbc.queryForObject("SELECT title FROM notification",String.class));
        assertTrue(jdbc.queryForObject("SELECT content_payload FROM notification",String.class).contains("new room"));
        jdbc.update("UPDATE activity SET lifecycle_status=2 WHERE id=?",activityId); reconcileAt(now,3);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE channel='IN_APP' AND status='DELIVERED'",Integer.class));
    }
    @Test void futureTimeChangeCancelsOldPlanAndJobAndCurrentOverdueIntentIsCreated() {
        node("ACTIVITY_START",now.plusHours(5),false); reconcileAt(now,1); long old=plan(8);
        jdbc.update("UPDATE timeline SET start_time=? WHERE target_id=?",now.plusHours(7),activityId); reconcileAt(now,2);
        assertEquals(2,plans.selectById(old).getStatus());
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM async_job WHERE dedupe_key=?",String.class,"plan:"+old+":fanout"));
        assertNotEquals(old,plan(8));
        jdbc.update("UPDATE timeline SET start_time=?,start_precision=2 WHERE target_id=?",now.plusMinutes(15),activityId); reconcileAt(now,3);
        assertEquals(1,plans.selectActiveBySource(1,activityId).size());
        jdbc.update("UPDATE timeline SET start_time=NULL,start_precision=0,time_description='待定' WHERE target_id=?",activityId); reconcileAt(now,4);
        assertTrue(plans.selectActiveBySource(1,activityId).isEmpty());
    }
    @Test void storageAcceptsContractMaximumAndFrozenScanBoundCannotGrow() {
        jdbc.update("INSERT INTO timeline(target_type,target_id,node_key,node_type,label,description,time_description) VALUES (2,?,'max','OTHER',?,?,?)",activityId,"题".repeat(120),"文".repeat(2000),"时".repeat(500));
        assertEquals(2000,jdbc.queryForObject("SELECT CHAR_LENGTH(description) FROM timeline WHERE node_key='max'",Integer.class));
        node("ACTIVITY_START",now.plusHours(5),false); subscriber(firstUser,1); reconcileAt(now,1); long id=plan(8);
        assertEquals(1,plans.freezeRecipientUpperBound(id,firstUser));
        subscriber(firstUser+1,1); assertEquals(0,plans.freezeRecipientUpperBound(id,firstUser+1));
        notify.broadcastPlanFanOut(id); assertEquals(List.of(firstUser),received(8));
    }
    @Test void multipleBatchesCanReplayAfterCrashWithoutDuplicateDeliveriesOrExpandedBound() {
        for(long uid=firstUser;uid<firstUser+105;uid++) {
            if(uid>=firstUser+8) {
                jdbc.update("INSERT INTO user_profile(id,openid) VALUES (?,?)",uid,"reminder-"+uid);
                jdbc.update("INSERT INTO notification_preference_owner(user_id,owner,migrated_at) VALUES (?,'CANONICAL',NOW(3))",uid);
            }
            subscriber(uid,1);
        }
        node("ACTIVITY_START",now.plusHours(3),false); reconcileAt(now,1); long id=plan(8);
        notify.broadcastPlanFanOut(id);
        assertEquals(105,received(8).size());
        jdbc.update("UPDATE notify_plan SET status=3,completed_at=NULL WHERE id=?",id);
        notify.broadcastPlanFanOut(id);
        assertEquals(105,received(8).size());
        assertEquals(105,jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery",Integer.class));
        assertEquals(firstUser+104,plans.selectById(id).getRecipientUpperUserId());
    }
    @Test void oldExternalDeliveryRechecksCurrentRelationshipAndAnchorWhileInAppHistoryPersists() throws Exception {
        subscriber(firstUser,1); node("ACTIVITY_START",now.plusMinutes(40),false); reconcileAt(now.minusHours(2),1);
        long id=plan(8); notify.broadcastPlanFanOut(id);
        var row=notifications.selectCanonicalInbox(firstUser,null,null,null,null,10).get(0);
        jdbc.update("INSERT INTO notification_preference(user_id,category,channel,enabled) VALUES (?,'ACTIVITY','WECHAT_OFFICIAL_ACCOUNT',1)",firstUser);
        jdbc.update("INSERT INTO notification_delivery(notification_id,channel,status) VALUES (?,'WECHAT_OFFICIAL_ACCOUNT','PENDING')",row.getId());
        long deliveryId=jdbc.queryForObject("SELECT id FROM notification_delivery WHERE channel='WECHAT_OFFICIAL_ACCOUNT'",Long.class);
        var templateSnapshot=new cn.jualn.miniapp.module.wx.notice.WxDeliveryTemplateSnapshot(1,"WECHAT_OFFICIAL_ACCOUNT","mp-primary",
                "test","fixture-account","OFFICIAL_ACCOUNT_SUBSCRIBE_BIZSEND","legacy:activity_start",1,"legacy:activity_start",1,
                "fixture-template",Map.of("thing1","frozen"),"/pages/message/message?notificationId="+row.getId(),"fixture-jump","fixture-digest");
        jdbc.update("UPDATE notification_delivery SET template_snapshot_version=1,template_snapshot=? WHERE id=?",json.writeValueAsString(templateSnapshot),deliveryId);
        when(provider.isFrozenAvailable(any(),any())).thenReturn(true);
        jdbc.update("UPDATE activity_enrollment SET status=2 WHERE user_id=?",firstUser);
        delivery.execute(deliveryId);
        assertEquals("SKIPPED",jdbc.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,deliveryId));
        assertEquals(0,jdbc.queryForObject("SELECT attempt_count FROM notification_delivery WHERE id=?",Integer.class,deliveryId));
        verify(provider,never()).deliver(anyLong(),any(),any(),any());
        assertEquals(1,notifications.selectCanonicalInbox(firstUser,null,null,null,null,10).size());
        jdbc.update("INSERT INTO notification_preference(user_id,category,channel,enabled) VALUES (?,'ACTIVITY','IN_APP',0)",firstUser);
        assertEquals(1,notifications.selectCanonicalInbox(firstUser,null,null,null,null,10).size());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE channel='IN_APP' AND status='DELIVERED'",Integer.class));
    }
    @Test void timezoneIsExplicitForDueClaimsAndTimestampFillEvenWhenJvmRunsInUtc() {
        TimeZone previous=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            var businessNow=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            tx.executeWithoutResult(t -> {
                jobs.create(new JobDefinition("test.clock",1,"clock-op","due","test","1",Map.of("id",1),businessNow.minusMinutes(1),3));
                jobs.create(new JobDefinition("test.clock",1,"clock-op","future","test","2",Map.of("id",2),businessNow.plusHours(1),3));
            });
            var claimed=tx.execute(t -> new JobClaimService(sql.getMapper(AsyncJobMapper.class)).claimDue("clock-owner",10));
            assertEquals(1,claimed.size()); assertEquals("due",claimed.get(0).getDedupeKey());
            node("ACTIVITY_START",businessNow.plusHours(3).withNano(0),false); reconcileAt(businessNow,1);
            assertTrue(plans.selectById(plan(8)).getCreatedAt().isAfter(businessNow.minusMinutes(1)));
        } finally {
            TimeZone.setDefault(previous);
            jdbc.update("DELETE FROM async_job WHERE job_type='test.clock'");
        }
    }
    @Test void reminderAndJobRollBackTogetherThroughRealSpringMandatoryProxy() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> reconcile.reconcile(1,activityId,1,List.of(),now));
        node("ACTIVITY_START",now.plusHours(3),false);
        assertThrows(IllegalStateException.class,() -> tx.executeWithoutResult(t -> {
            reconcileAt(now,1); throw new IllegalStateException("fixture rollback");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM notify_plan",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM async_job",Integer.class));
    }

    @Test void activeEventsUseCurrentUnionDeduplicateAndCancellationAndEarlyEndAreNotFilteredByActive() {
        subscriber(firstUser,1); registrant(firstUser+1,1); subscriber(firstUser+2,1); registrant(firstUser+2,1);
        var context=mock(JobExecutionContext.class);
        var snapshot=new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot(
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation("时间已变更",null,"new title",null,
                        List.of(new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Change("活动开始","原待定","2030-01-01T09:00:00+08:00")),"new title",null),null,
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject("ACTIVITY",Long.toString(activityId)),
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("ACTIVITY_DETAIL",null,null,Long.toString(activityId),null));
        var event=new cn.jualn.miniapp.module.notify.async.BusinessNotificationJobPayload(TargetType.ACTIVITY.getCode(),activityId,"activity:71001:time:v2",13,
                "SUBSCRIBERS_OR_REGISTERED_USERS","时间已变更","time",snapshot);
        notify.broadcastBusinessNotification(event,context); notify.broadcastBusinessNotification(event,context);
        assertEquals(List.of(firstUser,firstUser+1,firstUser+2),received(13));
        jdbc.update("UPDATE activity_enrollment SET status=2 WHERE user_id=?",firstUser+2);
        jdbc.update("UPDATE activity SET lifecycle_status=2 WHERE id=?",activityId);
        var cancelled=new cn.jualn.miniapp.module.notify.async.BusinessNotificationJobPayload(TargetType.ACTIVITY.getCode(),activityId,"activity:71001:cancel:v3",12,
                "SUBSCRIBERS_OR_REGISTERED_USERS","活动已取消","cancelled",null);
        notify.broadcastBusinessNotification(cancelled,context); notify.broadcastBusinessNotification(cancelled,context);
        assertEquals(List.of(firstUser,firstUser+1,firstUser+2),received(12));
        jdbc.update("UPDATE activity_registration SET status=2 WHERE user_id=?",firstUser+2);
        jdbc.update("UPDATE activity SET lifecycle_status=1 WHERE id=?",activityId);
        var ended=new cn.jualn.miniapp.module.notify.async.BusinessNotificationJobPayload(TargetType.ACTIVITY.getCode(),activityId,"activity:71001:end:v4",15,
                "SUBSCRIBERS_OR_REGISTERED_USERS","活动已提前结束","ended",null);
        notify.broadcastBusinessNotification(ended,context);
        assertEquals(List.of(firstUser,firstUser+1),received(15));
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE channel='IN_APP'",Integer.class));
    }

}
