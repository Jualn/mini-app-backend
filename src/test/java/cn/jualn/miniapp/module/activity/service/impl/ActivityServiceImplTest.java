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
    void createActivity_shouldRejectLegacyWrite() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.createActivity(null));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void getActivityDetail_shouldLoadAndEnrichWhenCacheMiss() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        Activity activity = Activity.builder().id(5L).userId(11L).publishStatus(1).status(2).build();
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
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));

        service.increaseCommentCount(88L);

        verify(activityMapper).increaseCommentCount(88L);
        verify(redisService).delete(RedisKeyConstant.activityDetail(88L));
    }

    @Test
    void updateActivity_shouldRejectLegacyOwnerWrite() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.updateActivity(null));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void updateActivity_shouldThrowWhenUserIsNotOwner() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.updateActivity(null));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void removeActivity_shouldRejectLegacyAdminWrite() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.removeActivity(1L));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void removeActivity_shouldThrowWhenUserIsNotOwner() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.removeActivity(1L));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void pageAdminActivities_shouldDecodeAndReturnOpaqueCursor() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
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
    void submitAdminActivityReview_shouldRejectRetiredReview() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.submitAdminActivityReview(1L, 9L));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void cancelAdminActivity_shouldCancelPendingNotificationPlans() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        when(activityMapper.selectForUpdate(102L)).thenReturn(Activity.builder()
                .id(102L)
                .title("迎新活动")
                .status(ActivityStatus.SIGNUP.getCode())
                .build());
        when(activityMapper.cancelAdminActivity(102L, "场地不可用")).thenReturn(1);

        service.cancelAdminActivity(102L, 9L, "场地不可用");

        verify(notifyService).cancelActivityPlans(102L);
        verify(notifyService).notifyActivitySubscribers(102L, "活动已取消", "活动「迎新活动」已取消：场地不可用");
        verify(redisService).delete(RedisKeyConstant.activityDetail(102L));
    }

    @Test
    void updateAdminActivityPinned_shouldBeIdempotentWhenAlreadyPinned() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        when(activityMapper.selectForUpdate(103L)).thenReturn(Activity.builder()
                .id(103L)
                .status(ActivityStatus.SIGNUP.getCode())
                .isPinned(true)
                .build());

        service.updateAdminActivityPinned(103L, 9L, true);

        verify(activityMapper, never()).updateAdminPinned(103L, true);
    }
}




