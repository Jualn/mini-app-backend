package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.ActivityNotificationSource;
import cn.jualn.miniapp.module.notify.bo.ReminderPlanBO;
import cn.jualn.miniapp.module.notify.channel.NotificationChannels;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCatalogQueries;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageRequest;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real deployment mappings and provider DTOs; no network or real recipient. */
class WxNotificationDeploymentTemplatesTest {
    private final WxProperties accounts = new WxProperties(null, new WxProperties.Ma("fixture-ma", "synthetic-secret", null, null));
    private final WxClient provider = mock(WxClient.class);
    private final UserWechatIdentityService identities = mock(UserWechatIdentityService.class);
    private final WxNotificationTemplateRenderer renderer = new WxNotificationTemplateRenderer();

    static WxNotificationTemplateProperties configuration() throws Exception {
        var environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("deployment", new ClassPathResource("application.yaml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment).bind("wx.notification", Bindable.of(WxNotificationTemplateProperties.class)).get();
    }

    private WxMiniNoticeSendService sender(WxNotificationTemplateRegistry registry) {
        return new WxMiniNoticeSendService(provider, accounts, identities, registry, renderer);
    }

    private Map<String, Object> frozen() {
        var values = new HashMap<String, Object>();
        values.put("notificationId", "71"); values.put("targetId", "42");
        values.put("title", "校园交流活动"); values.put("noticeTitle", "学习成果审核通过");
        values.put("noticeSummary", "结果已更新，请进入小程序查看。");
        values.put("eventTime", "2026-11-21T10:20:00");
        values.put("originalContent", "原帖或问题正文"); values.put("replyUserName", "回答同学");
        values.put("replyContent", "公开回复内容"); values.put("replyTime", "2026-11-21T10:15:00");
        values.put("startTime", "2026-11-22T09:30:00"); values.put("location", "交流中心");
        values.put("registrationStartTime", "2026-11-21T12:00:00");
        values.put("registrationDeadlineTime", "2026-11-21T20:00:00");
        values.put("auditResult", "已通过"); values.put("auditTime", "2026-11-21T10:10:00");
        values.put("auditReason", "PRIVATE_REVIEW_NOTE_MUST_NOT_LEAK");
        values.put("presentation", Map.of("subjectTitle", "校园交流活动", "context", "天气原因",
                "changes", List.of(Map.of("label", "地点", "before", "一楼", "after", "二楼"))));
        values.put("target", Map.of("type", "ACTIVITY_DETAIL", "activityId", "42"));
        return values;
    }

    @AfterEach void clearUser() { UserContext.clear(); }

    @Test void directoryContainsAllNineRealTemplatesAndDeduplicatesSharedBindings() throws Exception {
        var properties = configuration();
        var registry = new WxNotificationTemplateRegistry(properties, accounts);
        var sender = sender(registry);
        var adapter = new WxNotificationChannelAdapter(sender.channel(), sender, sender::isTemplateConfigured,
                new ObjectMapper(), registry);
        UserContext.setUserId(2L);
        var options = new NotificationCatalogQueries(new NotificationChannels(List.of(adapter))).options(null);
        assertEquals(9, properties.getDefinitions().size());
        assertEquals(9, options.items().size());
        assertEquals(9, options.items().stream().map(item -> item.templateId()).distinct().count());
        assertTrue(options.items().stream().allMatch(item -> item.subscriptionKind().equals("ONE_TIME")));
        assertEquals(Set.of("新的评论提醒", "活动取消通知", "活动变更通知", "报名截止通知", "活动报名通知", "审核结果通知",
                "提问回复通知", "活动开始提醒", "新的回复提醒"), options.items().stream().map(item -> item.title()).collect(java.util.stream.Collectors.toSet()));
        var reviews = options.items().stream().filter(item -> item.title().equals("审核结果通知")).findFirst().orElseThrow();
        assertEquals(Set.of("AUDIT_RESULT", "LEARNING_TASK", "POINTS"), Set.copyOf(reviews.categories()));
        assertFalse(reviews.notificationTypes().contains("POST_REPORT_RESOLVED"));
        assertFalse(reviews.notificationTypes().contains("POINT_GRANT_REVERSED"));
    }

    @Test void everyBoundTypeRendersExactNativeFieldsAndBuildsTheIntendedProviderRequest() throws Exception {
        var properties = configuration();
        var registry = new WxNotificationTemplateRegistry(properties, accounts);
        var sender = sender(registry);
        when(identities.getMiniOpenid(2L)).thenReturn("synthetic-recipient");
        var accepted = new MiniSubscribeMessageResponse(); accepted.setErrcode(0); accepted.setErrmsg("ok");
        when(provider.sendMiniSubscribeMessage(any())).thenReturn(accepted);
        for (var entry : properties.getBindings().entrySet()) {
            var binding = entry.getValue();
            var values = frozen();
            if (binding.getScenario().equals("MENTOR_QA_ANSWER")) values.put("mentorAnswer", true);
            var snapshot = sender.prepareDelivery(NotifyType.valueOf(binding.getNotificationType()), values);
            assertEquals(entry.getKey(), snapshot.bindingRef());
            assertEquals(properties.getDefinitions().get(binding.getDefinitionRef()).getFields().keySet(), snapshot.data().keySet());
            assertTrue(snapshot.pagePath().contains("notificationId=71"));
            assertFalse(snapshot.data().toString().contains("PRIVATE_REVIEW_NOTE"));
            assertTrue(snapshot.data().values().stream().noneMatch(String::isBlank), entry.getKey());
            if (snapshot.data().containsKey("date5")) assertEquals("2026-11-22 09:30", snapshot.data().get("date5"));
            if (snapshot.data().containsKey("date4")) assertEquals("2026-11-21 10:10", snapshot.data().get("date4"));
            if (snapshot.data().containsKey("date3")) assertEquals("2026-11-21 10:15", snapshot.data().get("date3"));
            sender.sendFrozen(2L, snapshot, "71", () -> { });
        }
        var requests = ArgumentCaptor.forClass(MiniSubscribeMessageRequest.class);
        verify(provider, times(properties.getBindings().size())).sendMiniSubscribeMessage(requests.capture());
        assertEquals(9, requests.getAllValues().stream().map(MiniSubscribeMessageRequest::templateId).distinct().count());
    }

    @Test void formalMentorAnswerNeverUsesTheOrdinaryCommentTemplateOrFallback() throws Exception {
        var properties = configuration();
        var registry = new WxNotificationTemplateRegistry(properties, accounts);
        var sender = sender(registry);
        var ordinary = sender.prepareDelivery(NotifyType.POST_COMMENTED, frozen());
        var formal = frozen(); formal.put("mentorAnswer", true);
        var answer = sender.prepareDelivery(NotifyType.POST_COMMENTED, formal);
        assertEquals("BG9O2XEYbm3G6ulpVMmB91jHBNLScKjs6Vq620uvAHQ", ordinary.templateId());
        assertEquals("DC9OS51VOfw7Ysf9wwxrWNOw3OTqylM40v5MthgERlg", answer.templateId());
        assertNotEquals(ordinary.bindingRef(), answer.bindingRef());
        properties.getBindings().remove("mini-mentor-answer");
        var withoutAnswer = new WxNotificationTemplateRegistry(properties, accounts);
        assertNotNull(withoutAnswer.resolve("POST_COMMENTED", sender.channel()));
        assertNull(withoutAnswer.resolve("POST_COMMENTED", sender.channel(), formal));
        assertThrows(BusinessException.class, () -> sender(withoutAnswer).prepareDelivery(NotifyType.POST_COMMENTED, formal));
    }

    @Test void mandatoryReviewFactsAndRegistrationDeadlineCannotBeInvented() throws Exception {
        var registry = new WxNotificationTemplateRegistry(configuration(), accounts);
        var values = frozen(); values.put("auditResult", "处理完毕");
        assertThrows(BusinessException.class, () -> sender(registry).prepareDelivery(NotifyType.AUDIT_RESULT, values));
        values.remove("registrationDeadlineTime");
        assertThrows(BusinessException.class, () -> sender(registry).prepareDelivery(NotifyType.ACTIVITY_REGISTRATION_START_REMINDER, values));
    }

    @Test void learningReviewUsesTheBusinessInstantWithoutLosingTheShanghaiOffset() {
        var notices=mock(cn.jualn.miniapp.module.notify.service.NotifyService.class);
        var clock=java.time.Clock.fixed(java.time.Instant.parse("2026-11-20T18:30:00Z"),java.time.ZoneOffset.UTC);
        var service=new cn.jualn.miniapp.module.learningtask.service.LearningTaskNotifications(
                mock(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper.class),
                mock(cn.jualn.miniapp.module.learningtask.mapper.LearningAssignmentMapper.class),
                mock(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper.class),notices,
                mock(cn.jualn.miniapp.infrastructure.async.job.JobService.class),clock,
                mock(cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"noticeLedger",mock(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskNoticeMapper.class));
        service.accept(List.of(new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice("review:1","REVIEW_APPROVED",1,2,2L,"学习任务",null,null,null,null,null,"AVAILABLE")));
        var capture=ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.payload.NotifyPayload.class);
        verify(notices).processNotificationPayload(capture.capture());
        assertEquals(LocalDateTime.of(2026,11,21,2,30),capture.getValue().getContentData().toMap().get("auditTime"));
        assertEquals("已通过",capture.getValue().getContentData().toMap().get("auditResult"));
    }

    @Test void registrationOpeningFreezesTheUniqueExactDeadlineFromTheTimelineOwner() {
        var timelines = mock(TimelineService.class);
        var deadline = new TimelineFactBO(); deadline.setNodeType("REGISTRATION_END");
        deadline.setStartPrecision(2); deadline.setStartTime(LocalDateTime.of(2026, 11, 22, 20, 0));
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, 42L)).thenReturn(List.of(deadline));
        var source = new ActivityNotificationSource(mock(ActivityEnrollmentService.class), timelines);
        var plan = ReminderPlanBO.builder().sourceId(42L).notifyType(NotifyType.ACTIVITY_REGISTRATION_START_REMINDER.getCode())
                .subjectStartsAt(LocalDateTime.of(2026, 11, 21, 12, 0)).title("校园活动").build();
        var batch = source.reminders(List.of(2L,3L,4L), plan);
        verify(timelines,times(1)).listTimelinesByTarget(TargetType.ACTIVITY,42L);
        assertTrue(batch.stream().allMatch(item -> deadline.getStartTime().equals(item.getRegistrationDeadlineTime())));
        var payload = batch.get(0);
        assertEquals(deadline.getStartTime(), payload.getRegistrationDeadlineTime());
        deadline.setStartTime(LocalDateTime.of(2026, 11, 23, 20, 0));
        assertEquals(LocalDateTime.of(2026, 11, 22, 20, 0), payload.getRegistrationDeadlineTime());
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, 42L)).thenReturn(List.of(deadline, deadline));
        assertNull(source.reminder(2, plan).getRegistrationDeadlineTime());
    }
}
