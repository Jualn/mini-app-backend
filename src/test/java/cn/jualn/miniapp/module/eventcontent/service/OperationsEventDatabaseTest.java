package cn.jualn.miniapp.module.eventcontent.service;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.eventcontent.mapper.*;
import cn.jualn.miniapp.module.eventcontent.service.impl.EventContentServiceImpl;
import cn.jualn.miniapp.module.eventcontent.bo.*;
import cn.jualn.miniapp.module.exam.bo.*;
import cn.jualn.miniapp.module.exam.mapper.*;
import cn.jualn.miniapp.module.exam.converter.ExamConverter;
import cn.jualn.miniapp.module.exam.service.*;
import cn.jualn.miniapp.module.exam.service.impl.ExamServiceImpl;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.aop.framework.ProxyFactory;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL + MyBatis + Spring transaction proxy; media/network boundaries remain mocked. */
class OperationsEventDatabaseTest {
    ExamService service;
    MediaService media;
    JdbcTemplate jdbc;
    ExamInfoMapper mapper;
    ActivityMapper activityMapper;
    DataSourceTransactionManager transactions;
    SqlSessionTemplate session;
    @BeforeEach void setup() throws Exception {
        String url=System.getProperty("event.test.jdbcUrl");
        Assumptions.assumeTrue(url!=null,"Disposable V1..V6 database required");
        var ds=new DriverManagerDataSource(url,"root","");
        jdbc=new JdbcTemplate(ds);
        transactions=new DataSourceTransactionManager(ds);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        factory.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig()
                .setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/ExamInfoMapper.xml"),new ClassPathResource("mapper/ActivityMapper.xml"));
        var sf=factory.getObject();
        config.addMapper(EventSectionMapper.class);config.addMapper(EventActionMapper.class);
        config.addMapper(ExamSubscriptionMapper.class);
        config.addMapper(cn.jualn.miniapp.module.activity.mapper.ActivityEnrollmentMapper.class);
        session=new SqlSessionTemplate(sf);
        mapper=session.getMapper(ExamInfoMapper.class);activityMapper=session.getMapper(ActivityMapper.class);
        media=mock(MediaService.class);
        var content=new EventContentServiceImpl(session.getMapper(EventSectionMapper.class),session.getMapper(EventActionMapper.class),media);
        var owner=new ExamServiceImpl(content,mapper,mock(ExamSubscriptionMapper.class),org.mapstruct.factory.Mappers.getMapper(ExamConverter.class),
                media,mock(TimelineService.class),mock(UserService.class),mock(RedisService.class),mock(InteractService.class),
                mock(ExamSubscriptionService.class),mock(NotifyService.class));
        var proxy=new ProxyFactory(owner);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        service=(ExamService)proxy.getProxy();
    }
    AdminPublicEventSaveBO command() {
        return AdminPublicEventSaveBO.builder().operatorId(9L).title("ops integration test")
                .category(0).eventType(2).editionLabel("2026").audienceScope(1).registrationMode(3).participantMode(2)
                .capacity(18).capacityUnit(2).startPrecision(0).endPrecision(0)
                .registrationStartPrecision(0).registrationEndPrecision(0)
                .sections(List.of(EventSectionBO.builder().sectionType("INTRO").title("介绍").content("第一段").build(),
                        EventSectionBO.builder().sectionType("RULES").title("规则").content("第二段").build()))
                .actions(List.of(EventActionBO.builder().actionType(1).label("入口").targetValue("https://example.org/register").build()))
                .build();
    }
    @Test void fullOperationsLifecycleAndNullableFieldsRoundTrip() {
        var request=command();
        var draft=service.createAdminPublicEvent(request);long id=draft.getId();
        assertEquals(0,draft.getPublishStatus());assertEquals(2,draft.getSections().size());
        assertEquals(18,draft.getCapacity());assertEquals(2,draft.getCapacityUnit());
        assertThrows(BusinessException.class,()->service.getExamDetail(id));
        service.publishAdminPublicEvent(id,9L);
        assertEquals(1,service.getExamDetail(id).getPublishStatus());
        assertEquals(0,mapper.selectByIdNotDeleted(id).getAuditStatus());
        request.setId(id);request.setTitle("ops changed");request.setSections(null);request.setActions(null);
        request.setCapacity(null);request.setCapacityUnit(null);
        var saved=service.updateAdminPublicEvent(request);
        assertEquals(1,saved.getPublishStatus());assertEquals(2,saved.getSections().size());
        assertEquals(1,saved.getActions().size());assertNull(saved.getCapacity());
        service.takeDownAdminPublicEvent(id,9L,"维护");
        assertThrows(BusinessException.class,()->service.getExamDetail(id));
        service.publishAdminPublicEvent(id,9L);
        service.cancelAdminPublicEvent(id,9L,"取消原因");
        assertEquals("取消原因",service.getAdminPublicEvent(id).getCancelReason());
        assertNotNull(service.getAdminPublicEvent(id).getCancelledAt());
        assertThrows(BusinessException.class,()->service.publishAdminPublicEvent(id,9L));
        assertThrows(BusinessException.class,()->service.updateAdminPublicEvent(request));
    }
    @Test void childFailureRollsBackOwnerAndSections() {
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM exam_info",Long.class);
        long sections=jdbc.queryForObject("SELECT COUNT(*) FROM event_section WHERE target_type=3",Long.class);
        doThrow(new IllegalStateException("simulated media bind failure")).when(media).replaceAttachments(any());
        var request=command();request.setAttachments(List.of(AttachmentItemBO.builder().type(MediaType.IMAGE).objectKey("test").build()));
        assertThrows(IllegalStateException.class,()->service.createAdminPublicEvent(request));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM exam_info",Long.class));
        assertEquals(sections,jdbc.queryForObject("SELECT COUNT(*) FROM event_section WHERE target_type=3",Long.class));
    }
    @Test void legacyBodyCannotFlattenStructuredPublishedEvent() {
        var request=command();var saved=service.createAdminPublicEvent(request);service.publishAdminPublicEvent(saved.getId(),9L);
        request.setId(saved.getId());request.setSections(null);request.setContent("flattened");
        assertThrows(BusinessException.class,()->service.updateAdminPublicEvent(request));
        assertEquals(2,service.getAdminPublicEvent(saved.getId()).getSections().size());
    }
    @Test void publicEventCoverForeignKeyClearsOnDelete() {
        var saved=service.createAdminPublicEvent(command());long id=saved.getId();
        jdbc.update("INSERT INTO media_attachment(target_type,target_id,type,url) VALUES(3,?,1,'https://example.org/image')",id);
        long mediaId=jdbc.queryForObject("SELECT MAX(id) FROM media_attachment WHERE target_type=3 AND target_id=?",Long.class,id);
        jdbc.update("UPDATE exam_info SET cover_attachment_id=? WHERE id=?",mediaId,id);
        jdbc.update("DELETE FROM media_attachment WHERE id=?",mediaId);
        assertNull(mapper.selectByIdNotDeleted(id).getCoverAttachmentId());
    }
    @Test void publicListsHideDraftsAndDoNotRequireAudit() {
        var draft=service.createAdminPublicEvent(command());var published=service.createAdminPublicEvent(command());
        service.publishAdminPublicEvent(published.getId(),9L);
        var rows=mapper.selectPageExams(2,null,null,null,100);
        assertTrue(rows.stream().anyMatch(r->r.getId().equals(published.getId())));
        assertTrue(rows.stream().noneMatch(r->r.getId().equals(draft.getId())));
        var query=new AdminPublicEventQueryBO();query.setPublishStatus(0);
        assertTrue(service.pageAdminPublicEvents(query).getItems().stream().allMatch(r->r.getPublishStatus()==0));
    }
    @Test void activityDirectSqlIsVisibleWithoutAuditAndKeepsCancellationReason() {
        jdbc.update("INSERT INTO activity(user_id,title,content,registration_mode) VALUES(9,'ops activity','text',1)");
        long id=jdbc.queryForObject("SELECT MAX(id) FROM activity",Long.class);
        assertEquals(1,activityMapper.publishDirectly(id));
        assertTrue(activityMapper.selectPageActivities(null,null,null,null,100).stream().anyMatch(r->r.getId()==id));
        assertEquals(1,activityMapper.cancelAdminActivity(id,"场地取消"));
        assertEquals("场地取消",activityMapper.selectForUpdate(id).getCancelReason());
    }
    @Test void publishingWaitsForConcurrentSaveOwnerLock() throws Exception {
        long id=service.createAdminPublicEvent(command()).getId();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            Future<?> editing=executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                mapper.selectForUpdate(id);locked.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout"); }
                catch(InterruptedException e){throw new IllegalStateException(e);}
                jdbc.update("UPDATE exam_info SET registration_mode=0 WHERE id=?",id);return null;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            Future<?> publishing=executor.submit(()->service.publishAdminPublicEvent(id,9L));
            assertThrows(TimeoutException.class,()->publishing.get(150,TimeUnit.MILLISECONDS));
            release.countDown();editing.get(5,TimeUnit.SECONDS);
            ExecutionException rejected=assertThrows(ExecutionException.class,()->publishing.get(5,TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class,rejected.getCause());
            assertEquals(0,service.getAdminPublicEvent(id).getPublishStatus());
        } finally { release.countDown();executor.shutdownNow(); }
    }
    @Test void publicSubscriptionCancelAndResumeRetainsSingleRecord() {
        long id=service.createAdminPublicEvent(command()).getId();service.publishAdminPublicEvent(id,9L);
        var subscriptions=new cn.jualn.miniapp.module.exam.service.impl.ExamSubscriptionServiceImpl(
                mapper,session.getMapper(ExamSubscriptionMapper.class),mock(RedisService.class),
                mock(cn.jualn.miniapp.infrastructure.validator.TargetValidator.class));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        cn.jualn.miniapp.common.constant.UserContext.setUserId(991L);
        try {
            tx.execute(s->{subscriptions.subscribeExam(id);return null;});
            tx.execute(s->{subscriptions.subscribeExam(id);return null;});
            tx.execute(s->{subscriptions.unsubscribeExam(id);return null;});
            assertTrue(subscriptions.listSubscriberUserIds(id,0,10).isEmpty());
            tx.execute(s->{subscriptions.subscribeExam(id);return null;});
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM exam_subscription WHERE exam_info_id=? AND user_id=991",Integer.class,id));
            assertEquals(List.of(991L),subscriptions.listSubscriberUserIds(id,0,10));
            service.takeDownAdminPublicEvent(id,9L,"维护");
            assertThrows(BusinessException.class,()->tx.execute(s->{subscriptions.subscribeExam(id);return null;}));
        } finally {cn.jualn.miniapp.common.constant.UserContext.clear();}
    }
    @Test void activitySubscriptionRestoresAndPagesUsingUserId() {
        jdbc.update("INSERT INTO activity(user_id,title,content,registration_mode,publish_status,status) VALUES(9,'subscription test','text',1,1,2)");
        long id=jdbc.queryForObject("SELECT MAX(id) FROM activity",Long.class);
        var subscriptions=new cn.jualn.miniapp.module.activity.service.impl.ActivityEnrollmentServiceImpl(
                session.getMapper(cn.jualn.miniapp.module.activity.mapper.ActivityEnrollmentMapper.class),activityMapper,
                mock(RedisService.class),mock(cn.jualn.miniapp.infrastructure.validator.TargetValidator.class));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        cn.jualn.miniapp.common.constant.UserContext.setUserId(991L);
        try {
            tx.execute(s->{subscriptions.enrollActivity(id);return null;});
            tx.execute(s->{subscriptions.unEnrollActivity(id);return null;});
            tx.execute(s->{subscriptions.enrollActivity(id);return null;});
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM activity_enrollment WHERE activity_id=?",Integer.class,id));
            jdbc.update("INSERT INTO activity_enrollment(activity_id,user_id,status,notify_enable) VALUES(?,20,1,1),(?,10,2,1),(?,30,1,0)",id,id,id);
            assertEquals(List.of(20L),subscriptions.listEnrolledUserIds(id,0,1));
            assertEquals(List.of(991L),subscriptions.listEnrolledUserIds(id,20,10));
        } finally {cn.jualn.miniapp.common.constant.UserContext.clear();}
    }
}
