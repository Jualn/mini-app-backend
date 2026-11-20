package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.config.MybatisPlusConfig;
import cn.jualn.miniapp.infrastructure.async.job.AsyncJobMapper;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.bo.NotificationCatalogBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPreferencesBO;
import cn.jualn.miniapp.module.notify.channel.NotificationChannels;
import cn.jualn.miniapp.module.notify.controller.CanonicalNotificationController;
import cn.jualn.miniapp.module.notify.converter.NotificationCatalogConverter;
import cn.jualn.miniapp.module.notify.converter.NotificationCenterConverter;
import cn.jualn.miniapp.module.notify.converter.NotificationPersonalConverter;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.mapper.NotificationDeliveryMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationMonitoringMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotificationAcceptance;
import cn.jualn.miniapp.module.notify.service.NotificationContentAccess;
import cn.jualn.miniapp.module.notify.service.NotificationCreator;
import cn.jualn.miniapp.module.notify.service.NotificationDeliveryService;
import cn.jualn.miniapp.module.notify.service.NotificationEligibility;
import cn.jualn.miniapp.module.notify.service.NotificationMonitoringService;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCapabilityQueries;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCatalogQueries;
import cn.jualn.miniapp.module.notify.service.impl.NotificationCreationService;
import cn.jualn.miniapp.module.notify.service.impl.NotificationDeliveryServiceImpl;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationCenterService;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationCenterServiceImpl;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationSnapshotProjection;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationStreamCursorCodec;
import cn.jualn.miniapp.module.notify.service.inbox.PersonalNotificationInbox;
import cn.jualn.miniapp.module.notify.service.preference.NotificationPreferences;
import cn.jualn.miniapp.module.user.service.UserNotificationIdentityService;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.wx.notice.WxNotificationChannelAdapter;
import cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateProperties;
import cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateRegistry;
import cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateRenderer;
import cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageRequest;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageResponse;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Owned MySQL only. Real Spring transactions, MyBatis, jobs and MVC; identity, domain and provider doubles. */
@EnabledIfSystemProperty(named="notification.v1.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/notification_v1_test_[a-zA-Z0-9_]+")
class NotificationFoundationDatabaseTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate db;
    static ObjectMapper json;
    NotificationPreferences preferences;
    NotificationCreator creator;
    NotificationCenterService center;
    NotificationDeliveryService delivery;
    MockMvc mvc;
    @BeforeAll static void migrate() {
        String url=System.getProperty("notification.v1.jdbcUrl")+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true";
        var source=new DriverManagerDataSource(url,"root","");
        db=new JdbcTemplate(source);
        assertEquals(System.getProperty("notification.v1.serverUuid"),db.queryForObject("SELECT @@server_uuid",String.class),"Owned MySQL UUID required");
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        flyway.migrate();flyway.validate();
        context=new AnnotationConfigApplicationContext(Config.class);
        json=context.getBean(ObjectMapper.class);
    }
    @AfterAll static void stop(){if(context!=null)context.close();}
    @BeforeEach void setup(){
        // This schema is fresh and exclusively owned by this test run; no application DB is read.
        for(String table:List.of("notification_delivery_resolution","async_job","notification_source_receipt","notification","notification_inbox_counter","notification_preference_v1"))db.update("DELETE FROM "+table);
        UserContext.setUserId(2L);
        preferences=context.getBean(NotificationPreferences.class);creator=context.getBean(NotificationCreator.class);
        center=context.getBean(NotificationCenterService.class);delivery=context.getBean(NotificationDeliveryService.class);
        reset(context.getBean(WxClient.class));
        mvc=MockMvcBuilders.standaloneSetup(context.getBean(CanonicalNotificationController.class))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json))
                .addInterceptors(new HandlerInterceptor(){
                    public boolean preHandle(jakarta.servlet.http.HttpServletRequest r,jakarta.servlet.http.HttpServletResponse s,Object h){
                        if(r.getHeader("X-Test-User")==null)throw new BusinessException(cn.jualn.miniapp.common.result.ResultCode.UNAUTHORIZED);
                        UserContext.setUserId(Long.parseLong(r.getHeader("X-Test-User")));return true;
                    }
                }).build();
    }
    @AfterEach void clear(){UserContext.clear();}
    NotifyPayload payload(NotifyType type,String source){
        return NotifyPayload.builder().receiverId(2L).senderId(3L).type(type).title(type.getDesc()).content("安全摘要")
                .sourceKey(source).contentData(type==NotifyType.POST_COMMENTED?new cn.jualn.miniapp.module.notify.payload.data.CommentNoticeData(
                        42L,19L,"帖子标题","一级评论","评论同学",LocalDateTime.of(2026,11,20,10,20),"原文正文"):null).targetType(type==NotifyType.POST_COMMENTED?cn.jualn.miniapp.common.enums.TargetType.POST:null)
                .targetId(type==NotifyType.POST_COMMENTED?42L:null).build();
    }
    void set(NotificationCategory category,NotificationChannel channel,Boolean value){
        preferences.updatePreferences(new NotificationPreferencesBO.Patch(List.of(new NotificationPreferencesBO.Change(category,channel,value))));
    }
    @Test void migrationKeepsHistoryAndDoesNotImportOldSettings(){
        assertEquals(27,preferences.getPreferences().items().size());
        assertEquals(9,preferences.getPreferences().items().stream().filter(i->i.enabled()).count());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_preference_v1",Integer.class));
        assertNotNull(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='notification_preference'",Integer.class));
        db.update("INSERT INTO notification_preference(user_id,category,channel,enabled,source) VALUES(2,'ACTIVITY','WECHAT_MINI_PROGRAM',1,'USER_OVERRIDE') ON DUPLICATE KEY UPDATE enabled=1");
        assertFalse(preferences.getPreferences().items().stream().filter(i->i.category().equals("ACTIVITY")&&i.channel().equals("WECHAT_MINI_PROGRAM")).findFirst().orElseThrow().enabled());
    }
    @Test void firstReleaseLifecycleNeverExpiresOrDisposesHistoricalRows(){
        long id=creator.create(payload(NotifyType.SYSTEM,"history:untouched")).notificationId();
        db.update("UPDATE notification SET inbox_generation='LEGACY',created_at=DATE_SUB(NOW(),INTERVAL 200 DAY) WHERE id=?",id);
        db.update("UPDATE notification_delivery SET status='PENDING' WHERE notification_id=?",id);
        long deliveryId=db.queryForObject("SELECT id FROM notification_delivery WHERE notification_id=?",Long.class,id);
        var lifecycle=context.getBean(cn.jualn.miniapp.module.notify.mapper.NotificationLifecycleMapper.class);
        var now=NotificationObservation.now();
        assertTrue(lifecycle.candidates(now,100).isEmpty());
        assertTrue(lifecycle.expiredPending(now,100).isEmpty());
        assertEquals(0,lifecycle.expireDelivery(deliveryId,now));
        assertEquals(0,lifecycle.dispose(id,now));
        assertEquals(0,lifecycle.redact(id,now));
        assertEquals(0,lifecycle.redactTemplates(id));
        assertEquals("PENDING",db.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,deliveryId));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification WHERE id=?",Integer.class,id));
    }
    @Test void settingsResetDuplicateAndAtomicDatabaseFailure(){
        set(NotificationCategory.COMMENT,NotificationChannel.WECHAT_MINI_PROGRAM,true);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification_preference_v1",Integer.class));
        set(NotificationCategory.COMMENT,NotificationChannel.WECHAT_MINI_PROGRAM,null);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_preference_v1",Integer.class));
        var change=new NotificationPreferencesBO.Change(NotificationCategory.COMMENT,NotificationChannel.IN_APP,false);
        assertThrows(ContractProblemException.class,()->preferences.updatePreferences(new NotificationPreferencesBO.Patch(List.of(change,change))));
        db.execute("CREATE TRIGGER notification_v1_reject_test BEFORE INSERT ON notification_preference_v1 FOR EACH ROW BEGIN IF NEW.category='POINTS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'; END IF; END");
        try {
            assertThrows(ContractProblemException.class,()->preferences.updatePreferences(new NotificationPreferencesBO.Patch(List.of(change,
                    new NotificationPreferencesBO.Change(NotificationCategory.POINTS,NotificationChannel.IN_APP,false)))));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_preference_v1",Integer.class));
        }finally{db.execute("DROP TRIGGER notification_v1_reject_test");}
    }
    @Test void concurrentDifferentCombinationsAndResetDoNotLoseChoices() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try {
            var a=pool.submit(()->{UserContext.setUserId(2L);try{barrier.await();set(NotificationCategory.COMMENT,NotificationChannel.WECHAT_MINI_PROGRAM,true);return true;}finally{UserContext.clear();}});
            var c=pool.submit(()->{UserContext.setUserId(2L);try{barrier.await();set(NotificationCategory.POINTS,NotificationChannel.IN_APP,false);return true;}finally{UserContext.clear();}});
            assertTrue(a.get(20,TimeUnit.SECONDS));assertTrue(c.get(20,TimeUnit.SECONDS));
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM notification_preference_v1",Integer.class));
            set(NotificationCategory.COMMENT,NotificationChannel.WECHAT_MINI_PROGRAM,null);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification_preference_v1",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void allThirtyThreeLegalTypesEnterOneInboxWithoutExternalTemplates(){
        int expected=0;
        for(var type:NotificationCategory.types()){assertEquals(NotificationCreator.Outcome.CREATED,creator.create(payload(type,"type:"+type)).outcome());expected++;}
        assertEquals(33,expected);
        assertEquals(33,db.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE channel='IN_APP' AND status='DELIVERED' AND notification_type IS NOT NULL",Integer.class));
        assertEquals(33,db.queryForObject("SELECT COUNT(*) FROM notification WHERE inbox_generation='V1' AND inbox_seq IS NOT NULL",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM async_job",Integer.class));
        assertEquals(33,context.getBean(PersonalNotificationInbox.class).unreadCount());
    }
    @Test void existingRealCommentTemplatePlansDurableJobAndRetainsNotificationIdentity(){
        set(NotificationCategory.COMMENT,NotificationChannel.WECHAT_MINI_PROGRAM,true);
        var result=creator.create(payload(NotifyType.POST_COMMENTED,"comment:19:receiver:2:approved"));
        long id=db.queryForObject("SELECT id FROM notification_delivery WHERE channel='WECHAT_MINI_PROGRAM'",Long.class);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type='notification.wechat-deliver' AND schema_version=2",Integer.class));
        var accepted=new MiniSubscribeMessageResponse();accepted.setErrcode(0);
        when(context.getBean(WxClient.class).sendMiniSubscribeMessage(any())).thenReturn(accepted);
        delivery.execute(id);
        assertEquals("DELIVERED",db.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,id));
        var request=org.mockito.ArgumentCaptor.forClass(MiniSubscribeMessageRequest.class);
        verify(context.getBean(WxClient.class)).sendMiniSubscribeMessage(request.capture());
        assertEquals("BG9O2XEYbm3G6ulpVMmB91jHBNLScKjs6Vq620uvAHQ",request.getValue().templateId());
        assertTrue(request.getValue().page().contains("notificationId="+result.notificationId()));
        assertNull(db.queryForObject("SELECT read_at FROM notification WHERE id=?",java.sql.Timestamp.class,result.notificationId()));
        assertEquals(NotificationCreator.Outcome.DUPLICATE,creator.create(payload(NotifyType.POST_COMMENTED,"comment:19:receiver:2:approved")).outcome());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM notification WHERE source_key='comment:19:receiver:2:approved'",Integer.class));
    }
    @Test void noInAppPreferenceDoesNotEnterInboxAndReenableDoesNotReplay(){
        set(NotificationCategory.SYSTEM,NotificationChannel.IN_APP,false);
        var first=creator.create(payload(NotifyType.SYSTEM,"system:hidden"));
        assertEquals(0,context.getBean(PersonalNotificationInbox.class).unreadCount());
        assertNotNull(center.get(first.notificationId().toString()));
        set(NotificationCategory.SYSTEM,NotificationChannel.IN_APP,true);
        creator.create(payload(NotifyType.SYSTEM,"system:hidden"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notification_delivery",Integer.class));
        creator.create(payload(NotifyType.SYSTEM,"system:visible"));
        assertEquals(1,context.getBean(PersonalNotificationInbox.class).unreadCount());
    }
    @Test void nativeRefusalAndUnknownAreTerminalWithoutFallbackOrReadSideEffects(){
        set(NotificationCategory.COMMENT,NotificationChannel.WECHAT_MINI_PROGRAM,true);
        for(boolean unknown:List.of(false,true)){
            var result=creator.create(payload(NotifyType.POST_COMMENTED,"provider:"+unknown));
            long id=db.queryForObject("SELECT id FROM notification_delivery WHERE notification_id=? AND channel='WECHAT_MINI_PROGRAM'",Long.class,result.notificationId());
            var response=new MiniSubscribeMessageResponse();response.setErrcode(43101);
            if(unknown)when(context.getBean(WxClient.class).sendMiniSubscribeMessage(any())).thenThrow(new ExternalServiceException(cn.jualn.miniapp.common.result.ResultCode.WX_API_ERROR,"wechat","unknown"));
            else when(context.getBean(WxClient.class).sendMiniSubscribeMessage(any())).thenReturn(response);
            delivery.execute(id);delivery.execute(id);
            assertEquals(unknown?"UNKNOWN":"FAILED",db.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,id));
            assertNull(db.queryForObject("SELECT read_at FROM notification WHERE id=?",java.sql.Timestamp.class,result.notificationId()));
        }
        verify(context.getBean(WxClient.class),times(2)).sendMiniSubscribeMessage(any());
        verify(context.getBean(WxClient.class),never()).sendMpSubscribeMessage(any());
    }
    @Test void serviceAccountIsStoppedBeforeAttemptAndPrivateRowsAreNeverPublic(){
        var id=creator.create(payload(NotifyType.SYSTEM,"official:pending")).notificationId();
        db.update("INSERT INTO notification_delivery(notification_id,notification_type,preference_category,channel,status) VALUES(?,7,'SYSTEM','WECHAT_OFFICIAL_ACCOUNT','PENDING')",id);
        long deliveryId=db.queryForObject("SELECT id FROM notification_delivery WHERE channel='WECHAT_OFFICIAL_ACCOUNT'",Long.class);
        delivery.execute(deliveryId);
        assertEquals("SKIPPED",db.queryForObject("SELECT status FROM notification_delivery WHERE id=?",String.class,deliveryId));
        assertEquals(0,db.queryForObject("SELECT attempt_count FROM notification_delivery WHERE id=?",Integer.class,deliveryId));
        verifyNoInteractions(context.getBean(WxClient.class));
        UserContext.setUserId(3L);assertThrows(ContractProblemException.class,()->center.get(id.toString()));
    }
    @Test void monitoringReadsRealDeliveryAggregatesAndRejectsUnattributableHistory() throws Exception {
        set(NotificationCategory.COMMENT,NotificationChannel.WECHAT_MINI_PROGRAM,true);
        creator.create(payload(NotifyType.POST_COMMENTED,"monitor:comment"));
        creator.create(payload(NotifyType.SYSTEM,"monitor:system"));
        var service=context.getBean(NotificationMonitoringService.class);
        var from=OffsetDateTime.now().minusHours(1);var until=from.plusHours(2);
        var result=service.summary(9L,from,until,null,null);
        assertEquals(3,result.items().stream().mapToLong(NotificationCatalogBO.SummaryItem::count).sum());
        assertEquals(2,service.summary(9L,from,until,null,NotificationCategory.COMMENT).items().stream().mapToLong(NotificationCatalogBO.SummaryItem::count).sum());
        assertTrue(service.summary(9L,from,until,NotificationChannel.WECHAT_MINI_PROGRAM,NotificationCategory.POINTS).items().isEmpty());
        String encoded=json.writeValueAsString(result);assertFalse(encoded.contains("userId"));assertFalse(encoded.contains("原文正文"));
        Path out=Path.of("target/notification-first-release");Files.createDirectories(out);
        var evidence=new LinkedHashMap<String,Object>();var converter=Mappers.getMapper(NotificationCatalogConverter.class);evidence.put("status",converter.status(service.status(9L)));evidence.put("summary",converter.summary(result));
        json.writeValue(out.resolve("monitoring.json").toFile(),evidence);
        db.update("UPDATE notification_delivery SET preference_category=NULL WHERE channel='WECHAT_MINI_PROGRAM'");
        assertThrows(ContractProblemException.class,()->service.summary(9L,from,until,null,null));
    }

    @Test void actualHttpShapesRejectOldRepresentationAndBoundaries() throws Exception {
        var responses=new LinkedHashMap<String,Object>();
        for(String path:List.of("notification-preferences","notification-channel-capabilities","notification-subscription-options","notifications")){
            var response=mvc.perform(get("/v1/users/me/"+path).header("X-Test-User","2")).andReturn().getResponse();
            assertEquals(200,response.getStatus(),response.getContentAsString());assertEquals("no-store",response.getHeader("Cache-Control"));
            responses.put(path,json.readTree(response.getContentAsByteArray()));
        }
        assertEquals(27,json.valueToTree(responses.get("notification-preferences")).path("items").size());
        assertEquals(99,json.valueToTree(responses.get("notification-channel-capabilities")).path("items").size());
        assertEquals(400,mvc.perform(get("/v1/users/me/notifications?representation=structured").header("X-Test-User","2")).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get("/v1/users/me/notification-subscription-options?openid=arbitrary").header("X-Test-User","2")).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/v1/users/me/notification-preferences")).andReturn().getResponse().getStatus());
        String body="{\"changes\":[{\"category\":\"POINTS\",\"channel\":\"WECHAT_MINI_PROGRAM\",\"enabled\":true}]}";
        assertEquals(200,mvc.perform(post("/v1/users/me/notification-preferences:batch-update").header("X-Test-User","2").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(patch("/v1/users/me/notification-preferences").header("X-Test-User","2").contentType("application/json").content(body.replace(",\"enabled\":true",""))).andReturn().getResponse().getStatus());
        Path out=Path.of("target/notification-first-release");Files.createDirectories(out);json.writeValue(out.resolve("responses.json").toFile(),responses);
    }
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    @MapperScan("cn.jualn.miniapp.module.notify.mapper")
    static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(System.getProperty("notification.v1.jdbcUrl")+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true","root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean TransactionTemplate tx(DataSourceTransactionManager tm){return new TransactionTemplate(tm);}
        @Bean ObjectMapper objectMapper(){return new JacksonConfig().objectMapper(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder());}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds)throws Exception{var f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);f.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig().setMetaObjectHandler(new MybatisPlusConfig().metaObjectHandler()));f.setMapperLocations(new ClassPathResource("mapper/NotificationMapper.xml"),new ClassPathResource("mapper/NotificationPreferenceMapper.xml"),new ClassPathResource("mapper/AsyncJobMapper.xml"));return f.getObject();}
        @Bean MapperFactoryBean<AsyncJobMapper> jobMapper(SqlSessionFactory sf){var f=new MapperFactoryBean<>(AsyncJobMapper.class);f.setSqlSessionFactory(sf);return f;}
        @Bean RedisService redis(){return mock(RedisService.class);}
        @Bean io.micrometer.core.instrument.MeterRegistry meters(){return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();}
        @Bean JobService jobs(AsyncJobMapper m,ObjectMapper json){return new JobService(m,json);}
        @Bean NotificationPreferences preferences(NotificationPreferenceMapper m){return new NotificationPreferences(m);}
        @Bean WxClient wx(){return mock(WxClient.class);}
        @Bean WxNotificationTemplateRegistry templates()throws Exception{
            var env=new org.springframework.mock.env.MockEnvironment();new org.springframework.boot.env.YamlPropertySourceLoader().load("base",new ClassPathResource("application.yaml")).forEach(s->env.getPropertySources().addLast(s));
            var p=org.springframework.boot.context.properties.bind.Binder.get(env).bind("wx.notification",org.springframework.boot.context.properties.bind.Bindable.of(WxNotificationTemplateProperties.class)).get();
            return new WxNotificationTemplateRegistry(p,accounts());
        }
        @Bean WxProperties accounts(){return new WxProperties(new WxProperties.Mp("fixture-mp","fixture-secret",null,null,null),new WxProperties.Ma("fixture-ma","fixture-secret",null,null));}
        @Bean UserWechatIdentityService identities(){var m=mock(UserWechatIdentityService.class);when(m.getMiniOpenid(anyLong())).thenReturn("synthetic-mini-recipient");return m;}
        @Bean UserNotificationIdentityService identityFacts(){var m=mock(UserNotificationIdentityService.class);when(m.miniProgramUsers(any())).thenAnswer(c->new HashSet<>(c.<Collection<Long>>getArgument(0)));when(m.identity(anyLong())).thenReturn(new UserNotificationIdentityService.Identity(true,false));return m;}
        @Bean NotificationChannels channels(WxClient wx,WxProperties p,UserWechatIdentityService identities,WxNotificationTemplateRegistry r,ObjectMapper json){
            var mini=new WxMiniNoticeSendService(wx,p,identities,r,new WxNotificationTemplateRenderer());var official=new WxMpNoticeSendService(wx,p,identities,r,new WxNotificationTemplateRenderer());
            return new NotificationChannels(List.of(new WxNotificationChannelAdapter(mini.channel(),mini,mini::isTemplateConfigured,json,r),new WxNotificationChannelAdapter(official.channel(),official,official::isDeliveryAvailable,json,r)));
        }
        @Bean NotificationInboxService inbox(NotificationMapper m){return new NotificationInboxService(m);}
        @Bean NotificationDeliveryService delivery(NotificationDeliveryMapper d,NotificationMapper n,io.micrometer.core.instrument.MeterRegistry meters,NotificationInboxService inbox,JobService j,ObjectMapper json,NotificationChannels c,NotificationPreferenceMapper p,TransactionTemplate tx){return new NotificationDeliveryServiceImpl(d,n,meters,inbox,j,json,c,p,tx,new NotificationEligibility(List.of(),mock(NotifyPlanMapper.class)));}
        @Bean NotificationAcceptance creation(NotificationMapper n,io.micrometer.core.instrument.MeterRegistry meters,NotificationInboxService inbox,NotificationDeliveryService d,RedisService r,ObjectMapper json,NotificationPreferenceMapper p,UserNotificationIdentityService u){var target=mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class);when(target.exists(any(),anyLong())).thenReturn(true);return new NotificationCreationService(n,meters,inbox,d,r,target,json,p,u,List.of());}
        @Bean NotificationContentAccess contentAccess(){return (u,t,i)->new NotificationContentAccess.Decision(false,true,null);}
        @Bean PersonalNotificationInbox personal(NotificationMapper m,RedisService r){return new PersonalNotificationInbox(m,r);}
        @Bean NotificationCenterService center(NotificationMapper m,RedisService r,TransactionTemplate t,ObjectMapper json,io.micrometer.core.instrument.MeterRegistry meters){return new NotificationCenterServiceImpl(m,new NotificationStreamCursorCodec("notification-v1-test-cursor-secret-only"),new NotificationSnapshotProjection(json,meters,List.of()),r,t,meters);}
        @Bean NotificationCapabilityQueries capabilities(UserNotificationIdentityService u,NotificationChannels c){return new NotificationCapabilityQueries(u,c);}
        @Bean NotificationCatalogQueries catalog(NotificationChannels c){return new NotificationCatalogQueries(c);}
        @Bean cn.jualn.miniapp.module.authorization.service.CurrentAuthorization monitorAuthorization(){return mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class);}
        @Bean NotificationMonitoringService monitoring(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization a,NotificationCatalogQueries c,NotificationMonitoringMapper m){return new NotificationMonitoringService(a,c,m);}
        @Bean CanonicalNotificationController controller(PersonalNotificationInbox p,NotificationPreferences n,NotificationCapabilityQueries c,NotificationCenterService s,NotificationCatalogQueries q){return new CanonicalNotificationController(p,n,c,new NotificationPersonalConverter(),s,Mappers.getMapper(NotificationCenterConverter.class),q,Mappers.getMapper(NotificationCatalogConverter.class));}
    }
}
