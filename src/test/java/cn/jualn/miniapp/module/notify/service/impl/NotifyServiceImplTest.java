package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.notify.converter.NotifyConverter;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceOwnerMapper;
import cn.jualn.miniapp.module.notify.entity.NotificationPreference;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotificationDeliveryService;
import cn.jualn.miniapp.module.notify.reminder.ReminderSubjectStatus;
import cn.jualn.miniapp.module.notify.reminder.ReminderReconcileService;
import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.wx.notice.data.CommentNoticeData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class NotifyServiceImplTest {
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Notification.class);
        TableInfoHelper.initTableInfo(assistant, NotifyPlan.class);
    }

    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private NotifyPlanMapper notifyPlanMapper;
    @Mock
    private ActivityEnrollmentService enrollmentService;
    @Mock
    private ExamSubscriptionService examSubService;
    @Mock
    private UserService userService;
    @Mock
    private SettingService settingService;
    @Mock
    private RedisService redisService;
    @Mock
    private TargetValidator targetValidator;
    @Mock
    private NotifyConverter notifyConverter;
    @Mock
    private JobService jobService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private NotificationDeliveryService deliveryService;
    @Mock
    private ReminderReconcileService reminderReconcileService;
    @Mock
    private NotificationPreferenceMapper preferenceMapper;
    @Mock
    private NotificationPreferenceOwnerMapper preferenceOwnerMapper;
    @Mock
    private UserProfileMapper userProfileMapper;

    @AfterEach
    void tearDown() {
        UserContext.clear();
        MDC.clear();
    }

    private NotifyServiceImpl createService() {
        return createService(List.of());
    }

    private NotifyServiceImpl createService(List<ReminderSubjectStatus> statuses) {
        return new NotifyServiceImpl(notificationMapper, meters,
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.NotificationInboxService.class),
                notifyPlanMapper, enrollmentService, examSubService, userService,
                settingService, redisService, targetValidator, notifyConverter, jobService, transactionTemplate,
                deliveryService, new ObjectMapper().findAndRegisterModules(), statuses,
                reminderReconcileService, preferenceMapper, preferenceOwnerMapper, userProfileMapper);
    }

    @Test
    void replaceEventReminder_withoutFutureTime_onlyCancelsPendingPlans() {
        createService().replaceEventReminder(TargetType.EXAM, 7L, "事项", null);

        verify(reminderReconcileService).reconcile(eq(2), eq(7L), eq(0L), eq(List.of()), any());
        verify(notifyPlanMapper, never()).insert(any(NotifyPlan.class));
        verifyNoInteractions(jobService);
    }

    @Test
    void replaceEventReminder_withLegacyFutureTime_doesNotInventUnconfirmedRule() {
        LocalDateTime sendAt = LocalDateTime.now().plusDays(1);

        createService().replaceEventReminder(TargetType.EXAM, 7L, "事项", sendAt);

        verify(notifyPlanMapper, never()).insert(any(NotifyPlan.class));
        verifyNoInteractions(jobService);
    }

    @Test
    void replaceEventReminder_doesNotPersistPlanBeforeRuleMatrixIsConfirmed() {
        LocalDateTime sendAt = LocalDateTime.now().plusDays(1);

        createService().replaceEventReminder(TargetType.ACTIVITY, 7L, "活动", sendAt);

        verify(notifyPlanMapper, never()).insert(any(NotifyPlan.class));
        verifyNoInteractions(jobService);
    }

    @Test
    void countCurrentUnreadNotifications_shouldReturnCachedValueClampedToZero() {
        UserContext.setUserId(3L);
        NotifyServiceImpl service = createService();
        when(notificationMapper.selectUnreadProjectionVersion(3L)).thenReturn("10:3");
        when(redisService.getLong(RedisKeyConstant.userUnreadCount(3L) + ":10:3")).thenReturn(-5L);

        Long count = service.countCurrentUnreadNotifications();

        assertEquals(0L, count);
        verify(notificationMapper, never()).selectCount(any());
    }

    @Test
    void markAsRead_shouldUpdateCanonicalSetAndInvalidateUnreadCount() {
        UserContext.setUserId(3L);
        NotifyServiceImpl service = createService();
        when(notificationMapper.selectCanonicalReadState(9L, 3L)).thenReturn(0);

        service.markAsRead(9L);

        verify(notificationMapper).markCanonicalRead(9L, 3L);
        verify(redisService).delete(RedisKeyConstant.userUnreadCount(3L));
    }

    @Test
    void enqueueNotifyPlan_shouldSkipInvalidPlanAndSendValidPlan() {
        NotifyServiceImpl service = createService();

        when(notifyPlanMapper.selectById(1L)).thenReturn(null);
        service.enqueueNotifyPlan(1L);
        verifyNoInteractions(jobService);

        NotifyPlan plan = NotifyPlan.builder()
                .id(2L)
                .status(0)
                .sendAt(LocalDateTime.now().plusMinutes(5))
                .notifyType(1)
                .title("t")
                .content("c")
                .scope(1)
                .sourceType(3)
                .build();
        when(notifyPlanMapper.selectById(2L)).thenReturn(plan);

        service.enqueueNotifyPlan(2L);

        verify(jobService).create(any());
    }

    @Test
    void notifyActivitySubscribers_shouldEnqueueEachActiveSubscriber() {
        NotifyServiceImpl service = createService();
        when(enrollmentService.listEnrolledUserIds(8L, 0L, 100)).thenReturn(List.of(3L, 4L));

        service.notifyActivitySubscribers(8L, "cancel:v2", "活动已取消", "活动变更说明");

        verify(transactionTemplate, times(2)).executeWithoutResult(any());
    }

    @Test
    void reminderFanOutResolvesCurrentUnregisteredSubscribersInOnePagedQuery() {
        ReminderSubjectStatus status = org.mockito.Mockito.mock(ReminderSubjectStatus.class);
        when(status.sourceType()).thenReturn(1);
        when(status.isCurrentAndEligible(eq(8L), eq(4L), eq(null), any())).thenReturn(true);
        NotifyPlan plan = NotifyPlan.builder().id(20L).status(3).sourceType(1).sourceId(8L)
                .generation(4L).recipientScope("SUBSCRIBERS_NOT_REGISTERED").scope(0)
                .notifyType(NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER.getCode())
                .subjectStartsAt(LocalDateTime.of(2026, 10, 1, 15, 0))
                .title("活动").content("报名即将截止").build();
        when(notifyPlanMapper.selectById(20L)).thenReturn(plan);
        when(enrollmentService.listNotifyEnabledUnregisteredUserIds(8L, 0L, 100))
                .thenReturn(List.of(3L, 7L));

        createService(List.of(status)).broadcastPlanFanOut(20L);

        verify(enrollmentService).listNotifyEnabledUnregisteredUserIds(8L, 0L, 100);
        verify(enrollmentService, never()).listEnrolledUserIds(8L, 0L, 100);
        verify(transactionTemplate, times(3)).executeWithoutResult(any());
    }

    @Test
    void processNotificationPayload_shouldInsertAndSkipWeChatWhenDisabled() {
        NotifyServiceImpl service = createService();
        NotifyPayload payload = NotifyPayload.builder()
                .receiverId(3L)
                .senderId(1L)
                .type(NotifyType.COMMENTED_ME)
                .title("title")
                .content("content")
                .targetType(TargetType.POST)
                .targetId(99L)
                .wxData(new CommentNoticeData(99L, 1L, "测试标题", "测试内容", "小明", LocalDateTime.now()))
                .build();
        when(targetValidator.exists(TargetType.POST, 99L)).thenReturn(true);
        when(settingService.getSettingByUserId(3L)).thenReturn(UserSettingBO.builder()
                .notifyComment(false)
                .notifyReply(false)
                .notifyLike(false)
                .notifyActivityRemind(false)
                .notifyExamRemind(false)
                .notifySystem(false)
                .notifyAuditResult(false)
                .build());

        service.processNotificationPayload(payload);

        verify(notificationMapper).insert(any(Notification.class));
        verify(redisService).delete(RedisKeyConstant.userUnreadCount(3L));
        verify(userService, never()).getMiniOpenid(any());
        verifyNoInteractions(deliveryService);
    }

    @Test
    void canonicalActivityNotificationPlansVisibleInAppDeliveryAtomically() {
        NotifyServiceImpl service = createService();
        NotifyPayload payload = NotifyPayload.builder().receiverId(3L)
                .type(NotifyType.ACTIVITY_CANCELLED).title("活动已取消").content("内容")
                .targetType(TargetType.ACTIVITY).targetId(8L).sourceKey("activity:8:cancel:v2:user:3").build();
        when(targetValidator.exists(TargetType.ACTIVITY, 8L)).thenReturn(true);
        when(preferenceOwnerMapper.selectCanonicalUserIds(List.of(3L))).thenReturn(List.of(3L));
        when(preferenceMapper.selectByUserIds(List.of(3L))).thenReturn(List.of());
        doAnswer(invocation -> {
            ((Notification) invocation.getArgument(0)).setId(91L);
            return 1;
        }).when(notificationMapper).insert(any(Notification.class));
        when(deliveryService.planCanonicalDeliveries(any(), eq(NotifyType.ACTIVITY_CANCELLED),
                eq(java.util.Set.of(NotificationChannel.IN_APP)))).thenReturn(true);

        service.processNotificationPayload(payload);

        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(notification.capture());
        assertEquals("CANONICAL", notification.getValue().getInboxGeneration());
        verify(deliveryService).planCanonicalDeliveries(eq(notification.getValue()),
                eq(NotifyType.ACTIVITY_CANCELLED), eq(java.util.Set.of(NotificationChannel.IN_APP)));
        verify(redisService).delete(RedisKeyConstant.userUnreadCount(3L));
        assertEquals(1.0, meters.get("jualn.notification.creation").tag("result", "created").counter().count());
        assertEquals(List.of(new io.micrometer.core.instrument.ImmutableTag("result", "created")),
                meters.get("jualn.notification.creation").counter().getId().getTags());
    }

    @Test void failedCacheRebuildStillReturnsDurableUnreadAndUsesBoundedResultTag() {
        UserContext.setUserId(3L);
        when(notificationMapper.selectUnreadProjectionVersion(3L)).thenReturn("10:3");
        when(notificationMapper.countCanonicalUnread(3L)).thenReturn(2L);
        when(redisService.getLong(RedisKeyConstant.userUnreadCount(3L) + ":10:3")).thenReturn(null);
        assertEquals(2L, createService().countCurrentUnreadNotifications());
        var counter = meters.get("jualn.notification.unread.rebuild").tag("result", "cache_failure").counter();
        assertEquals(1.0, counter.count());
        assertEquals(List.of(new io.micrometer.core.instrument.ImmutableTag("result", "cache_failure")), counter.getId().getTags());
    }

    @Test
    void canonicalInAppDisableCreatesContentFactWithoutMakingItVisible() {
        NotifyServiceImpl service = createService();
        NotifyPayload payload = NotifyPayload.builder().receiverId(3L)
                .type(NotifyType.ACTIVITY_TIME_CHANGED).title("时间变化").content("内容")
                .targetType(TargetType.ACTIVITY).targetId(8L).sourceKey("activity:8:time:v3:user:3").build();
        when(targetValidator.exists(TargetType.ACTIVITY, 8L)).thenReturn(true);
        when(preferenceOwnerMapper.selectCanonicalUserIds(List.of(3L))).thenReturn(List.of(3L));
        NotificationPreference disabled = new NotificationPreference();
        disabled.setUserId(3L);
        disabled.setCategory("ACTIVITY");
        disabled.setChannel("IN_APP");
        disabled.setEnabled(false);
        when(preferenceMapper.selectByUserIds(List.of(3L))).thenReturn(List.of(disabled));

        service.processNotificationPayload(payload);

        verify(deliveryService).planCanonicalDeliveries(any(), eq(NotifyType.ACTIVITY_TIME_CHANGED),
                eq(java.util.Set.of()));
        verify(redisService, never()).delete(RedisKeyConstant.userUnreadCount(3L));
    }

    @Test
    void canonicalOfficialAccountPreferenceIsFilteredWhenIdentityIsMissing() {
        NotifyServiceImpl service = createService();
        NotifyPayload payload = NotifyPayload.builder().receiverId(3L)
                .type(NotifyType.ACTIVITY_START_REMINDER).title("活动开始").content("内容")
                .targetType(TargetType.ACTIVITY).targetId(8L).sourceKey("plan:7:user:3").build();
        when(targetValidator.exists(TargetType.ACTIVITY, 8L)).thenReturn(true);
        when(preferenceOwnerMapper.selectCanonicalUserIds(List.of(3L))).thenReturn(List.of(3L));
        NotificationPreference official = new NotificationPreference();
        official.setUserId(3L);
        official.setCategory("ACTIVITY");
        official.setChannel("WECHAT_OFFICIAL_ACCOUNT");
        official.setEnabled(true);
        when(preferenceMapper.selectByUserIds(List.of(3L))).thenReturn(List.of(official));
        when(userProfileMapper.selectOfficialAccountUserIds(List.of(3L))).thenReturn(List.of());

        service.processNotificationPayload(payload);

        verify(deliveryService).planCanonicalDeliveries(any(), eq(NotifyType.ACTIVITY_START_REMINDER),
                eq(java.util.Set.of(NotificationChannel.IN_APP)));
    }
}
