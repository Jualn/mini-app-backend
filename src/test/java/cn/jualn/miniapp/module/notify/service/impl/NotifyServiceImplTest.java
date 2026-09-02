package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.contract.DelayQueueProducer;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.notify.converter.NotifyConverter;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.wx.assembler.WxNoticePayloadAssembler;
import cn.jualn.miniapp.module.wx.notice.data.CommentNoticeData;
import cn.jualn.miniapp.module.wx.support.NotifyPlanNoticeDataFactory;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.service.WxMpNoticeSendService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifyServiceImplTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Notification.class);
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
    private QueueProducer queueProducer;
    @Mock
    private DelayQueueProducer delayQueueProducer;
    @Mock
    private WxClient wxClient;
    @Mock
    private WxProperties wxProperties;
    @Mock
    private WxMpNoticeSendService wxMpNoticeSendService;
    @Mock
    private WxNoticePayloadAssembler wxNoticePayloadAssembler;
    @Mock
    private NotifyPlanNoticeDataFactory notifyPlanNoticeDataFactory;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private NotifyServiceImpl createService() {
        return new NotifyServiceImpl(notificationMapper, notifyPlanMapper, enrollmentService, examSubService, userService,
                settingService, redisService, targetValidator, notifyConverter, queueProducer, delayQueueProducer,
                wxClient, wxProperties, wxMpNoticeSendService, wxNoticePayloadAssembler, notifyPlanNoticeDataFactory);
    }

    @Test
    void countCurrentUnreadNotifications_shouldReturnCachedValueClampedToZero() {
        UserContext.setUserId(3L);
        NotifyServiceImpl service = createService();
        when(redisService.getLong(RedisKeyConstant.userUnreadCount(3L))).thenReturn(-5L);

        Long count = service.countCurrentUnreadNotifications();

        assertEquals(0L, count);
        verify(notificationMapper, never()).selectCount(any());
    }

    @Test
    void markAsRead_shouldUpdateAndDecrementUnreadCount() {
        UserContext.setUserId(3L);
        NotifyServiceImpl service = createService();
        when(notificationMapper.selectIsReadByIdAndUserId(9L, 3L)).thenReturn(0);
        when(redisService.getLong(RedisKeyConstant.userUnreadCount(3L))).thenReturn(2L);

        service.markAsRead(9L);

        verify(notificationMapper).update(any());
        verify(redisService).decrement(RedisKeyConstant.userUnreadCount(3L));
    }

    @Test
    void enqueueNotifyPlan_shouldSkipInvalidPlanAndSendValidPlan() {
        NotifyServiceImpl service = createService();

        when(notifyPlanMapper.selectById(1L)).thenReturn(null);
        service.enqueueNotifyPlan(1L);
        verifyNoInteractions(delayQueueProducer);

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

        verify(delayQueueProducer).send(any(), any(), any());
    }

    @Test
    void notifyActivitySubscribers_shouldEnqueueEachActiveSubscriber() {
        NotifyServiceImpl service = createService();
        when(enrollmentService.listEnrolledUserIds(8L, 0L, 100)).thenReturn(List.of(3L, 4L));

        service.notifyActivitySubscribers(8L, "活动已取消", "活动变更说明");

        verify(queueProducer, times(2)).send(any(NotifyPayload.class));
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
        verify(redisService).increment(RedisKeyConstant.userUnreadCount(3L));
        verify(userService, never()).getMiniOpenid(any());
        verify(wxClient, never()).sendMpTemplateMessage(any());
    }
}
