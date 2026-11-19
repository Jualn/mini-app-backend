package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.entity.*;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.mapper.*;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.payload.data.CommentNoticeData;
import cn.jualn.miniapp.module.notify.service.NotificationEligibility;
import cn.jualn.miniapp.module.notify.service.impl.NotificationCreationService;
import cn.jualn.miniapp.module.notify.service.impl.NotificationDeliveryServiceImpl;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationInboxService;
import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.*;
import cn.jualn.miniapp.module.wx.service.*;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real production config and notification services; database, jobs, identities and WeChat are doubles. */
class PostCommentMiniNotificationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationMapper notifications = mock(NotificationMapper.class);
    private final NotificationDeliveryMapper deliveries = mock(NotificationDeliveryMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final WxClient wx = mock(WxClient.class);
    private final JobService jobs = mock(JobService.class);
    private final AtomicReference<Notification> storedNotification = new AtomicReference<>();
    private final AtomicReference<NotificationDelivery> storedDelivery = new AtomicReference<>();
    private NotificationCreationService creation;
    private NotificationDeliveryServiceImpl delivery;
    private WxMiniNoticeSendService mini;

    @BeforeEach void setup() throws Exception {
        var env = new MockEnvironment();
        env.setProperty("WX_NOTIFICATION_MINI_PROGRAM_ENABLED", "true"); // Explicit local test override.
        new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yaml"))
                .forEach(source -> env.getPropertySources().addLast(source));
        var configuration = Binder.get(env).bind("wx.notification", Bindable.of(WxNotificationTemplateProperties.class)).get();
        var registry = new WxNotificationTemplateRegistry(new WxMpNoticeTemplateProperties(), configuration);
        var accounts = new WxProperties(new WxProperties.Mp("fixture-mp", "fixture-secret", null, null, null),
                new WxProperties.Ma("fixture-ma", "fixture-secret", null, null));
        var identities = mock(UserWechatIdentityService.class);
        when(identities.getMiniOpenid(2L)).thenReturn("fixture-recipient-two");
        mini = new WxMiniNoticeSendService(wx, accounts, identities, registry, new WxNotificationTemplateRenderer());
        var official = new WxMpNoticeSendService(wx, accounts, identities, registry, new WxNotificationTemplateRenderer());
        var channels = cn.jualn.miniapp.module.notify.TestNotificationChannels.create(official, mini, json);
        var preferences = mock(NotificationPreferenceMapper.class);
        var inbox = mock(NotificationInboxService.class);
        var redis = mock(RedisService.class);
        delivery = new NotificationDeliveryServiceImpl(deliveries, notifications, meters, inbox, jobs, json, channels,
                settings, preferences, null, new NotificationEligibility(List.of(), mock(NotifyPlanMapper.class)));
        var targets = mock(SubjectExistenceService.class);
        when(targets.exists(TargetType.POST, 42L)).thenReturn(true);
        creation = new NotificationCreationService(notifications, meters, inbox, delivery, settings, redis, targets,
                json, preferences, mock(NotificationPreferenceOwnerMapper.class),
                new UserNotificationIdentityService(mock(UserProfileMapper.class)), List.of());
        when(settings.getSettingByUserId(2L)).thenReturn(UserSettingBO.builder().notifyComment(true).notifyReply(true).build());
        doAnswer(call -> { var row = call.<Notification>getArgument(0); row.setId(71L); storedNotification.set(row); return 1; })
                .when(notifications).insert(any(Notification.class));
        doAnswer(call -> { var row = call.<NotificationDelivery>getArgument(0); row.setId(41L); storedDelivery.set(row); return 1; })
                .when(deliveries).insert(any(NotificationDelivery.class));
        when(notifications.selectById(71L)).thenAnswer(call -> storedNotification.get());
        when(deliveries.selectDelivery(41L)).thenAnswer(call -> storedDelivery.get());
        when(deliveries.startProviderAttempt(eq(41L), any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach void cleanup() {
        TransactionSynchronizationManager.clearSynchronization();
        meters.close();
    }

    private NotifyPayload payload(NotifyType type) {
        return payload(type, null);
    }

    private NotifyPayload payload(NotifyType type, Set<NotificationChannel> allowed) {
        return NotifyPayload.builder().receiverId(2L).senderId(3L).type(type).targetType(TargetType.POST).targetId(42L)
                .title("有人评论了你").content("这是一条一级评论")
                .allowedChannels(allowed)
                .contentData(new CommentNoticeData(42L, 19L, "帖子标题", "这是一条一级评论", "评论同学",
                        LocalDateTime.of(2026, 11, 19, 10, 20), "这是帖子原文内容")).build();
    }

    @Test void localLegacyCommentFreezesDeliveryAndSendsConfiguredTemplateToPostOwnerTwo() throws Exception {
        creation.acceptLegacy(payload(NotifyType.POST_COMMENTED));
        assertNotNull(storedDelivery.get());
        assertEquals("WECHAT_MINI_PROGRAM", storedDelivery.get().getChannel());
        assertEquals("LEGACY", storedNotification.get().getInboxGeneration());
        assertEquals("这是帖子原文内容", json.readTree(storedNotification.get().getContentPayload()).path("originalContent").asText());
        verify(jobs).create(any());
        var accepted = new MiniSubscribeMessageResponse(); accepted.setErrcode(0);
        when(wx.sendMiniSubscribeMessage(any())).thenReturn(accepted);
        delivery.execute(41L);
        var request = org.mockito.ArgumentCaptor.forClass(MiniSubscribeMessageRequest.class);
        verify(wx).sendMiniSubscribeMessage(request.capture());
        assertEquals("BG9O2XEYbm3G6ulpVMmB91jHBNLScKjs6Vq620uvAHQ", request.getValue().templateId());
        assertEquals("fixture-recipient-two", request.getValue().toUser());
        assertEquals("这是帖子原文内容", request.getValue().data().get("thing15").value());
        assertEquals("评论同学", request.getValue().data().get("thing5").value());
        assertEquals("这是一条一级评论", request.getValue().data().get("thing2").value());
        assertEquals("2026-11-19 10:20", request.getValue().data().get("time3").value());
        assertEquals("subpkg_community/pages/detail/detail?postId=42&notificationId=71", request.getValue().page());
        verify(deliveries).markDelivered(eq(41L), isNull(), any());
        verify(wx, never()).sendMpSubscribeMessage(any());
    }

    @Test void replyAndOtherCommentTypesDoNotUseTopLevelPostTemplate() {
        for (var type : List.of(NotifyType.COMMENT_REPLIED, NotifyType.COMMENTED_ME, NotifyType.POST_LIKED))
            assertFalse(mini.isDeliveryAvailable(type));
        creation.acceptLegacy(payload(NotifyType.COMMENT_REPLIED));
        verifyNoInteractions(deliveries, jobs, wx);
    }

    @Test void disabledCommentSettingPreventsPlanning() {
        when(settings.getSettingByUserId(2L)).thenReturn(UserSettingBO.builder().notifyComment(false).build());
        creation.acceptLegacy(payload(NotifyType.POST_COMMENTED));
        verifyNoInteractions(deliveries, jobs, wx);
    }

    @Test void callerChannelRestrictionCannotBeBypassed() {
        creation.acceptLegacy(payload(NotifyType.POST_COMMENTED, Set.of(NotificationChannel.WECHAT_OFFICIAL_ACCOUNT)));
        verifyNoInteractions(deliveries, jobs, wx);
    }

    @Test void commentSettingIsRecheckedBeforeSending() {
        creation.acceptLegacy(payload(NotifyType.POST_COMMENTED));
        when(settings.getSettingByUserId(2L)).thenReturn(UserSettingBO.builder().notifyComment(false).build());
        delivery.execute(41L);
        verify(deliveries).markTerminal(eq(41L), eq("SKIPPED"), eq("validation"), eq("channel_disabled"), any());
        verifyNoInteractions(wx);
    }

    @Test void actualTemplateUsesUnicodeSummariesAndExplicitEmptyContentFallbacks() {
        var frozen = new HashMap<String, Object>(payload(NotifyType.POST_COMMENTED).getContentData().toMap());
        frozen.put("targetId", 42L); frozen.put("notificationId", "71");
        frozen.put("originalContent", "😀".repeat(25));
        frozen.put("replyUserName", ""); frozen.put("replyContent", "");
        var snapshot = mini.prepareDelivery(NotifyType.POST_COMMENTED, frozen);
        assertEquals("😀".repeat(20), snapshot.data().get("thing15"));
        assertEquals("同学", snapshot.data().get("thing5"));
        assertEquals("查看评论详情", snapshot.data().get("thing2"));
        frozen.remove("originalContent");
        assertEquals("查看帖子详情", mini.prepareDelivery(NotifyType.POST_COMMENTED, frozen).data().get("thing15"));
    }
}
