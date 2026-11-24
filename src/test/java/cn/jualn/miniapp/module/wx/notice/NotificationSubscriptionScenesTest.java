package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.activity.bo.ActivityDetailBO;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationBO;
import cn.jualn.miniapp.module.activity.bo.ActivitySubscriptionBO;
import cn.jualn.miniapp.module.activity.service.ActivityQueries;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.ActivityRegistrationService;
import cn.jualn.miniapp.module.activity.service.ActivitySubscriptionOptions;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventDetailBO;
import cn.jualn.miniapp.module.publicevent.service.PublicEventQueries;
import cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionOptions;
import cn.jualn.miniapp.module.notify.channel.NotificationChannels;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationSubscriptionScene;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCatalogQueries;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.wx.service.WxMiniNoticeSendService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationSubscriptionScenesTest {
    @AfterEach void clear() { UserContext.clear(); }
    private NotificationCatalogQueries catalog() throws Exception {
        UserContext.setUserId(2L);
        var accounts=new WxProperties(null,new WxProperties.Ma("fixture-ma","synthetic-secret",null,null));
        var registry=new WxNotificationTemplateRegistry(WxNotificationDeploymentTemplatesTest.configuration(),accounts);
        var sender=new WxMiniNoticeSendService(mock(WxClient.class),accounts,mock(UserWechatIdentityService.class),registry,new WxNotificationTemplateRenderer());
        return new NotificationCatalogQueries(new NotificationChannels(List.of(new WxNotificationChannelAdapter(sender.channel(),sender,sender::isTemplateConfigured,new ObjectMapper(),registry))));
    }
    @Test void scenesSeparateQuestionsAndPostsAndShareReviewWithoutSharingPreferences() throws Exception {
        var queries=catalog();
        var post=queries.options(null,NotificationSubscriptionScene.POST_PUBLISH,null);
        var question=queries.options(null,NotificationSubscriptionScene.MENTOR_QUESTION_PUBLISH,null);
        assertEquals(1,post.items().size()); assertEquals("新的评论提醒",post.items().get(0).title());
        assertEquals("提问回复通知",question.items().get(0).title());
        assertNotEquals(post.items().get(0).templateId(),question.items().get(0).templateId());
        var learning=queries.options(null,NotificationSubscriptionScene.LEARNING_SUBMIT,null);
        var points=queries.options(null,NotificationSubscriptionScene.POINTS_APPLY,null);
        assertEquals(1,learning.items().size()); assertEquals(1,points.items().size());
        assertEquals(learning.items().get(0).templateId(),points.items().get(0).templateId());
        assertEquals(List.of("LEARNING_TASK"),learning.items().get(0).categories());
        assertEquals(List.of("POINTS"),points.items().get(0).categories());
        assertEquals(3,post.maxTemplatesPerRequest());
        for(var scene:List.of(NotificationSubscriptionScene.COMMENT_PUBLISH,NotificationSubscriptionScene.MENTOR_ANSWER_PUBLISH))
            assertEquals("新的回复提醒",queries.options(null,scene,null).items().get(0).title());
        assertThrows(ContractProblemException.class,()->queries.options(NotificationCategory.COMMENT,NotificationSubscriptionScene.POST_PUBLISH,null));
        assertThrows(ContractProblemException.class,()->queries.options(null,NotificationSubscriptionScene.ACTIVITY_REGISTER,null));
        assertThrows(ContractProblemException.class,()->queries.options(null,null,1L));
    }
    private TimelineFactBO node(String type, int days) {
        return TimelineFactBO.builder().id((long)days).nodeType(type).startPrecision(2).startTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(days)).build();
    }
    @Test void activityOwnerUsesCurrentRelationsAndExistingReminderRules() {
        var reads=mock(ActivityQueries.class); var follows=mock(ActivityEnrollmentService.class); var registrations=mock(ActivityRegistrationService.class);
        var owner=new ActivitySubscriptionOptions(reads,follows,registrations);
        var detail=ActivityDetailBO.builder().publishStatus(1).lifecycleStatus(0).stageState("NOT_STARTED").registrationMode(2)
                .participationState("OPEN").timelineItems(List.of(node("REGISTRATION_START",1),node("REGISTRATION_END",2),node("ACTIVITY_START",3))).build();
        when(reads.getActivityResource(42L)).thenReturn(detail); when(follows.getSubscriptionState(42L)).thenReturn(new ActivitySubscriptionBO(false,null));
        assertEquals(List.of(NotifyType.ACTIVITY_REGISTRATION_START_REMINDER,NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER),owner.types(NotificationSubscriptionScene.ACTIVITY_FOLLOW,42));
        assertEquals(List.of(NotifyType.ACTIVITY_CANCELLED,NotifyType.ACTIVITY_TIME_CHANGED,NotifyType.ACTIVITY_LOCATION_CHANGED,NotifyType.ACTIVITY_START_REMINDER),owner.types(NotificationSubscriptionScene.ACTIVITY_REGISTER,42));
        detail.setParticipationState("CLOSED"); assertTrue(owner.types(NotificationSubscriptionScene.ACTIVITY_REGISTER,42).isEmpty());
        var mine=new ActivityRegistrationBO(); mine.setStatus(1); when(registrations.getMine(42L)).thenReturn(mine);
        assertTrue(owner.types(NotificationSubscriptionScene.ACTIVITY_FOLLOW,42).isEmpty());
        when(registrations.getMine(42L)).thenReturn(null); when(follows.getSubscriptionState(42L)).thenReturn(new ActivitySubscriptionBO(true,null));
        assertTrue(owner.types(NotificationSubscriptionScene.ACTIVITY_FOLLOW,42).isEmpty());
        when(follows.getSubscriptionState(42L)).thenReturn(new ActivitySubscriptionBO(false,null));
        detail.setTimelineItems(List.of(node("REGISTRATION_START",1)));
        assertTrue(owner.types(NotificationSubscriptionScene.ACTIVITY_FOLLOW,42).isEmpty(),"start template must not invent its required deadline");
        detail.setLifecycleStatus(2); assertTrue(owner.types(NotificationSubscriptionScene.ACTIVITY_REGISTER,42).isEmpty());
    }
    @Test void publicEventOffersOnlyFutureDeadlineBeforeFollowing() {
        var reads=mock(PublicEventQueries.class); var owner=new PublicEventSubscriptionOptions(reads);
        var detail=PublicEventDetailBO.builder().publishStatus(1).lifecycleStatus(0).subscribed(false).timelineItems(List.of(node("REGISTRATION_END",1))).build();
        when(reads.getPublicEventResource(42L)).thenReturn(detail);
        assertEquals(List.of(NotifyType.PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER),owner.types(NotificationSubscriptionScene.PUBLIC_EVENT_FOLLOW,42));
        detail.setSubscribed(true); assertTrue(owner.types(NotificationSubscriptionScene.PUBLIC_EVENT_FOLLOW,42).isEmpty());
        detail.setSubscribed(false);detail.setTimelineItems(List.of(node("REGISTRATION_END",-1)));
        assertTrue(owner.types(NotificationSubscriptionScene.PUBLIC_EVENT_FOLLOW,42).isEmpty());
    }
}
