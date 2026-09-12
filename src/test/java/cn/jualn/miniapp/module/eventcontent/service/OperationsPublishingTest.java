package cn.jualn.miniapp.module.eventcontent.service;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.impl.ActivityServiceImpl;
import cn.jualn.miniapp.module.activity.service.impl.ActivityAuditCallback;
import cn.jualn.miniapp.module.exam.bo.*;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.mapper.*;
import cn.jualn.miniapp.module.exam.converter.ExamConverter;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.exam.service.impl.ExamServiceImpl;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OperationsPublishingTest {
    @Mock ActivityMapper activityMapper;
    @Mock ActivityConverter activityConverter;
    @Mock ActivityEnrollmentService enrollment;
    @Mock ExamInfoMapper examInfoMapper;
    @Mock ExamSubscriptionMapper examSubscriptionMapper;
    @Mock ExamConverter examConverter;
    @Mock ExamSubscriptionService subscription;
    @Mock EventContentService content;
    @Mock MediaService media;
    @Mock TimelineService timeline;
    @Mock UserService user;
    @Mock InteractService interact;
    @Mock RedisService redis;
    @Mock NotifyService notify;
    ActivityServiceImpl activity;
    ExamServiceImpl exam;
    @BeforeEach void setup() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        activity = new ActivityServiceImpl(content, activityMapper, activityConverter, enrollment,
                media, user, timeline, redis, interact, notify, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        exam = new ExamServiceImpl(content, examInfoMapper, examSubscriptionMapper, examConverter,
                media, timeline, user, redis, interact, subscription, notify);
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(config, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Activity.class);
    }
    @AfterEach void teardown() { TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(false); }
    Activity draft() {
        return Activity.builder().id(7L).title("讲座").content("说明").organizer("学院").category(4)
                .audienceScope(1).registrationMode(1).publishStatus(0).status(0)
                .startTime(LocalDateTime.now().plusDays(2)).startPrecision(2).build();
    }
    @Test void activityPublishesWithoutAuditAndEnqueuesOnlyAfterCommit() {
        Activity row=draft();
        when(activityMapper.selectForUpdate(7L)).thenReturn(row);
        when(activityMapper.publishDirectly(7L)).thenReturn(1);
        activity.publishAdminActivity(7L,9L);
        var order=inOrder(activityMapper,notify);
        order.verify(activityMapper).selectForUpdate(7L);
        order.verify(activityMapper).publishDirectly(7L);
        order.verify(notify).replaceEventReminder(TargetType.ACTIVITY,7L,"讲座",row.getStartTime().minusHours(1));
        verifyNoInteractions(redis);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        verify(redis).delete(anyString());
    }
    @Test void unknownRegistrationCannotPublish() {
        Activity row=draft(); row.setRegistrationMode(0);
        when(activityMapper.selectForUpdate(7L)).thenReturn(row);
        assertThrows(BusinessException.class,()->activity.publishAdminActivity(7L,9L));
        verify(activityMapper,never()).publishDirectly(anyLong());
    }
    @Test void cancelledActivityCannotRepublish() {
        Activity row=draft(); row.setPublishStatus(3);
        when(activityMapper.selectForUpdate(7L)).thenReturn(row);
        assertThrows(BusinessException.class,()->activity.publishAdminActivity(7L,9L));
    }
    @Test void repeatedActivityPublishDoesNotDuplicateReminder() {
        Activity row=draft(); row.setPublishStatus(1);
        when(activityMapper.selectForUpdate(7L)).thenReturn(row);
        activity.publishAdminActivity(7L,9L);
        verifyNoInteractions(notify);
    }
    @Test void failedPublishDoesNotCreateReminder() {
        when(activityMapper.selectForUpdate(7L)).thenReturn(draft());
        assertThrows(BusinessException.class,()->activity.publishAdminActivity(7L,9L));
        verifyNoInteractions(notify);
    }
    @Test void publishedActivityCanBeEditedWithoutBecomingDraft() {
        Activity row=draft();row.setPublishStatus(1);
        when(activityMapper.selectForUpdate(7L)).thenReturn(row);
        when(activityMapper.update(isNull(),any())).thenReturn(1);
        when(content.saveSections(eq(TargetType.ACTIVITY),eq(7L),isNull(),any())).thenReturn("新正文");
        AdminActivitySaveBO command=AdminActivitySaveBO.builder().id(7L).operatorId(9L).title("新标题")
                .content("新正文").organizer("学院").category(ActivityCategory.fromCode(4)).audienceScope(1)
                .registrationMode(1).participantMode(1).build();
        activity.updateAdminActivity(command);
        var wrappers=ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(activityMapper,times(2)).update(isNull(),wrappers.capture());
        var first=(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>)wrappers.getAllValues().get(0);
        assertTrue(first.getSqlSet().contains("publish_status="));
        assertEquals(1,row.getPublishStatus());
        verify(notify).replaceEventReminder(eq(TargetType.ACTIVITY),eq(7L),anyString(),any());
    }
    @Test void legacyWritesAndReviewCannotChangePublication() {
        assertThrows(BusinessException.class,()->activity.createActivity(null));
        assertThrows(BusinessException.class,()->activity.submitAdminActivityReview(7L,9L));
        assertThrows(BusinessException.class,()->activity.approveActivityReview(7L,9L,null));
        assertThrows(BusinessException.class,()->exam.createExam(null));
        new ActivityAuditCallback().onPass(7L);
        verifyNoInteractions(activityMapper,examInfoMapper,notify);
    }
    @Test void publicEventPublishAndTakeDownUseOwnerLock() {
        ExamInfo row=ExamInfo.builder().id(8L).title("考试").content("说明").registrationMode(3).publishStatus(0)
                .registrationStart(LocalDateTime.now().plusDays(3)).registrationStartPrecision(2).build();
        when(examInfoMapper.selectForUpdate(8L)).thenReturn(row);
        when(examInfoMapper.publishDirectly(8L)).thenReturn(1);
        exam.publishAdminPublicEvent(8L,9L);
        verify(notify).replaceEventReminder(TargetType.EXAM,8L,"考试",row.getRegistrationStart().minusHours(1));
        when(examInfoMapper.takeDownDirectly(8L)).thenReturn(1);
        exam.takeDownAdminPublicEvent(8L,9L,"维护");
        verify(notify).replaceEventReminder(TargetType.EXAM,8L,"考试",null);
    }
    @Test void dateOnlyPublicEventDoesNotCreatePreciseReminder() {
        ExamInfo row=ExamInfo.builder().id(8L).title("考试").content("说明").registrationMode(3).publishStatus(0)
                .registrationStart(LocalDateTime.now().plusDays(3).toLocalDate().atStartOfDay()).registrationStartPrecision(1).build();
        when(examInfoMapper.selectForUpdate(8L)).thenReturn(row);
        when(examInfoMapper.publishDirectly(8L)).thenReturn(1);
        exam.publishAdminPublicEvent(8L,9L);
        verify(notify).replaceEventReminder(TargetType.EXAM,8L,"考试",null);
    }
    @Test void publicEventCursorDoesNotLoadDetailBody() {
        var first=new AdminPublicEventListBO();first.setId(20L);
        var second=new AdminPublicEventListBO();second.setId(10L);
        when(examInfoMapper.selectOperationsPage(any(),isNull(),eq(2))).thenReturn(List.of(first,second));
        var query=new AdminPublicEventQueryBO();query.setPageSize(1);
        var result=exam.pageAdminPublicEvents(query);
        assertEquals(1,result.getItems().size());assertTrue(result.getHasMore());assertNotNull(result.getNextCursor());
        verifyNoInteractions(content,media,timeline);
    }
    @Test void publicEventRejectsPlatformFormsAndMissingOperator() {
        assertThrows(BusinessException.class,()->exam.createAdminPublicEvent(AdminPublicEventSaveBO.builder().build()));
        assertThrows(BusinessException.class,()->exam.createAdminPublicEvent(AdminPublicEventSaveBO.builder()
                .operatorId(9L).registrationMode(2).build()));
        verifyNoInteractions(examInfoMapper);
    }
}
