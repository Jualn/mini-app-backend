package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.activity.bo.ActivityCreateBO;
import cn.jualn.miniapp.module.activity.bo.ActivityDetailBO;
import cn.jualn.miniapp.module.activity.bo.ActivityUpdateBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityPageBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityQueryBO;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.AdminActivityListRow;
import cn.jualn.miniapp.module.activity.mapper.AdminActivitySummaryRow;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailVO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityServiceImplTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Activity.class);
    }

    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private ActivityConverter activityConverter;
    @Mock
    private ActivityEnrollmentService activityEnrollmentService;
    @Mock
    private MediaService mediaService;
    @Mock
    private UserService userService;
    @Mock
    private TimelineService timelineService;
    @Mock
    private RedisService redisService;
    @Mock
    private InteractService interactService;
    @Mock
    private NotifyService notifyService;
    @Mock
    private AuditReservationService auditReservationService;
    @Mock
    private QueueProducer queueProducer;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createActivity_shouldCreateTimelineAndAttachments() {
        UserContext.setUserId(9L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        ActivityCreateBO command = ActivityCreateBO.builder()
                .timelineItems(List.of(TimelineItemBO.builder().label("t1").sortOrder(1).build()))
                .attachmentItems(List.of(AttachmentItemBO.builder().url("https://img").sortOrder(1).build()))
                .build();
        Activity entity = Activity.builder().id(66L).build();
        when(activityConverter.toEntity(command)).thenReturn(entity);

        Long id = service.createActivity(command);

        assertEquals(66L, id);
        verify(activityMapper).insert(entity);
        ArgumentCaptor<cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO> timelineCaptor = ArgumentCaptor.forClass(cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO.class);
        verify(timelineService).replaceTimelines(timelineCaptor.capture());
        assertEquals(TargetType.ACTIVITY, timelineCaptor.getValue().getTargetType());
        assertEquals(66L, timelineCaptor.getValue().getTargetId());

        ArgumentCaptor<cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO> mediaCaptor = ArgumentCaptor.forClass(cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO.class);
        verify(mediaService).replaceAttachments(mediaCaptor.capture());
        assertEquals(TargetType.ACTIVITY, mediaCaptor.getValue().getTargetType());
        assertEquals(66L, mediaCaptor.getValue().getTargetId());
    }

    @Test
    void getActivityDetail_shouldLoadAndEnrichWhenCacheMiss() {
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        Activity activity = Activity.builder().id(5L).userId(11L).build();
        ActivityDetailBO detailBO = ActivityDetailBO.builder().id(5L).userId(11L).build();
        ActivityDetailVO detailVO = ActivityDetailVO.builder().id(5L).build();

        when(redisService.get(RedisKeyConstant.activityDetail(5L), ActivityDetailBO.class)).thenReturn(null);
        when(activityMapper.selectByIdNotDeleted(5L)).thenReturn(activity);
        when(activityConverter.toDetailBO(activity)).thenReturn(detailBO);
        when(userService.getSimpleInfo(11L)).thenReturn(UserSimpleBO.builder().id(11L).nickname("author").build());
        when(mediaService.listAttachments(TargetType.ACTIVITY, 5L)).thenReturn(List.of());
        when(timelineService.listTimelinesByTarget(TargetType.ACTIVITY, 5L)).thenReturn(List.of());
        when(activityConverter.toDetailVO(detailBO)).thenReturn(detailVO);
        when(interactService.isLiked(TargetType.ACTIVITY, 5L)).thenReturn(true);
        when(activityEnrollmentService.isEnrolled(5L)).thenReturn(false);

        ActivityDetailVO result = service.getActivityDetail(5L);

        assertTrue(result.getLiked());
        assertFalse(result.getEnrolled());
        verify(redisService).set(RedisKeyConstant.activityDetail(5L), detailBO, RedisKeyConstant.ACTIVITY_DETAIL_TTL);
    }

    @Test
    void increaseCommentCount_shouldUpdateDbAndEvictDetailCache() {
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);

        service.increaseCommentCount(88L);

        verify(activityMapper).increaseCommentCount(88L);
        verify(redisService).delete(RedisKeyConstant.activityDetail(88L));
    }

    @Test
    void updateActivity_shouldAllowOwner() {
        UserContext.setUserId(9L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        Activity activity = Activity.builder().id(77L).userId(9L).build();
        ActivityUpdateBO command = ActivityUpdateBO.builder().id(77L).title("new").build();
        when(userService.getUserAuthInfo(9L)).thenReturn(UserAuthBO.builder().id(9L).role(UserRole.USER).build());
        when(activityMapper.selectOne(any())).thenReturn(activity);

        service.updateActivity(command);

        verify(activityConverter).updateEntityFromUpdateBO(activity, command);
        verify(activityMapper).updateById(activity);
        verify(redisService).delete(RedisKeyConstant.activityDetail(77L));
    }

    @Test
    void updateActivity_shouldThrowWhenUserIsNotOwner() {
        UserContext.setUserId(9L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        ActivityUpdateBO command = ActivityUpdateBO.builder().id(77L).build();
        when(userService.getUserAuthInfo(9L)).thenReturn(UserAuthBO.builder().id(9L).role(UserRole.USER).build());
        when(activityMapper.selectOne(any())).thenReturn(Activity.builder().id(77L).userId(10L).build());

        assertThrows(BusinessException.class, () -> service.updateActivity(command));

        verify(activityMapper, never()).updateById(any(Activity.class));
    }

    @Test
    void removeActivity_shouldAllowAdmin() {
        UserContext.setUserId(99L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        when(userService.getUserAuthInfo(99L)).thenReturn(UserAuthBO.builder().id(99L).role(UserRole.ADMIN).build());
        when(activityMapper.selectOne(any())).thenReturn(Activity.builder().id(88L).userId(11L).build());

        service.removeActivity(88L);

        ArgumentCaptor<Activity> activityCaptor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).updateById(activityCaptor.capture());
        assertEquals(ActivityStatus.DELETED.getCode(), activityCaptor.getValue().getStatus());
        verify(redisService).delete(RedisKeyConstant.activityDetail(88L));
        verify(redisService).delete(RedisKeyConstant.targetExists(TargetType.ACTIVITY.getKey(), 88L));
        verify(notifyService).cancelActivityPlans(88L);
    }

    @Test
    void removeActivity_shouldThrowWhenUserIsNotOwner() {
        UserContext.setUserId(9L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        when(userService.getUserAuthInfo(9L)).thenReturn(UserAuthBO.builder().id(9L).role(UserRole.USER).build());
        when(activityMapper.selectOne(any())).thenReturn(Activity.builder().id(88L).userId(10L).build());

        assertThrows(BusinessException.class, () -> service.removeActivity(88L));

        verify(activityMapper, never()).updateById(any(Activity.class));
    }

    @Test
    void pageAdminActivities_shouldDecodeAndReturnOpaqueCursor() {
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        String cursor = AdminIdCursorCodec.encode("latest", 200L);
        AdminActivityListRow row = new AdminActivityListRow();
        row.setId(101L);
        row.setCategory(0);
        row.setStatus(ActivityStatus.DRAFT.getCode());
        row.setAudienceScope(1);
        AdminActivitySummaryRow summary = new AdminActivitySummaryRow();
        summary.setEnrolling(0L);
        summary.setOngoing(0L);
        summary.setReviewing(0L);
        summary.setStartingSoon(0L);

        when(activityMapper.selectAdminActivityPage(
                null, null, null, null, null, "latest", 200L, 21))
                .thenReturn(List.of(row));
        when(activityMapper.selectAdminActivitySummary()).thenReturn(summary);

        AdminActivityPageBO result = service.pageAdminActivities(AdminActivityQueryBO.builder()
                .sort("latest")
                .cursor(cursor)
                .pageSize(20)
                .build());

        assertEquals(101L, AdminIdCursorCodec.decode(result.getNextCursor(), "latest"));
    }

    @Test
    void submitAdminActivityReview_shouldTransitionCompleteDraft() {
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        LocalDateTime startTime = LocalDateTime.now().plusDays(1);
        Activity draft = Activity.builder()
                .id(101L)
                .title("活动")
                .content("详情")
                .location("礼堂")
                .organizer("学生会")
                .category(0)
                .audienceScope(1)
                .startTime(startTime)
                .endTime(startTime.plusHours(2))
                .status(ActivityStatus.DRAFT.getCode())
                .build();
        when(activityMapper.selectAdminActivityById(101L)).thenReturn(draft);
        when(mediaService.listAttachments(TargetType.ACTIVITY, 101L)).thenReturn(List.of());
        when(auditReservationService.reserveAuditLogs(any())).thenReturn(AuditReserveResultBO.builder()
                .textAuditLogId(901L)
                .build());
        when(activityMapper.submitAdminReview(101L)).thenReturn(1);

        service.submitAdminActivityReview(101L, 9L);

        verify(activityMapper).submitAdminReview(101L);
        verify(redisService).delete(RedisKeyConstant.activityDetail(101L));
    }

    @Test
    void cancelAdminActivity_shouldCancelPendingNotificationPlans() {
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        when(activityMapper.selectAdminActivityById(102L)).thenReturn(Activity.builder()
                .id(102L)
                .title("迎新活动")
                .status(ActivityStatus.SIGNUP.getCode())
                .build());
        when(activityMapper.cancelAdminActivity(102L)).thenReturn(1);

        service.cancelAdminActivity(102L, 9L, "场地不可用");

        verify(notifyService).cancelActivityPlans(102L);
        verify(notifyService).notifyActivitySubscribers(102L, "活动已取消", "活动「迎新活动」已取消：场地不可用");
        verify(redisService).delete(RedisKeyConstant.activityDetail(102L));
    }

    @Test
    void updateAdminActivityPinned_shouldBeIdempotentWhenAlreadyPinned() {
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, auditReservationService, queueProducer);
        when(activityMapper.selectAdminActivityById(103L)).thenReturn(Activity.builder()
                .id(103L)
                .status(ActivityStatus.SIGNUP.getCode())
                .isPinned(true)
                .build());

        service.updateAdminActivityPinned(103L, 9L, true);

        verify(activityMapper, never()).updateAdminPinned(103L, true);
    }
}




