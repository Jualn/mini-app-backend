package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.ActivityCreateBO;
import cn.jualn.miniapp.module.activity.bo.ActivityDetailBO;
import cn.jualn.miniapp.module.activity.bo.ActivityUpdateBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityPageBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityListBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityQueryBO;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityPageQuery;
import cn.jualn.miniapp.module.activity.converter.AdminActivityConverter;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.AdminActivityListRow;
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
import jakarta.validation.Validation;
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

    @Test
    void adminPageQuery_shouldAcceptCanonicalSortAndRejectLegacySort() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var query = new AdminActivityPageQuery();
        query.setSort("-updatedAt");
        assertTrue(validator.validate(query).isEmpty());

        query.setSort("latest");
        assertFalse(validator.validate(query).isEmpty());
    }

    @Test
    void adminPageConverter_shouldExposeCanonicalPageShape() {
        var updatedAt = LocalDateTime.of(2026, 9, 14, 0, 30);
        var page = AdminActivityPageBO.builder()
                .items(List.of(AdminActivityListBO.builder()
                        .id(7L).title("活动").publishStatus(1).lifecycleStatus(0)
                        .updatedAt(updatedAt).build()))
                .page(2).pageSize(20).totalItems(21L).build();

        var result = new AdminActivityConverter().toPageVO(page);

        assertEquals(2, result.getPage());
        assertEquals(20, result.getPageSize());
        assertEquals(21L, result.getTotalItems());
        assertEquals("7", result.getItems().get(0).getActivityId());
        assertEquals("PUBLISHED", result.getItems().get(0).getPublishStatus());
        assertEquals("ACTIVE", result.getItems().get(0).getLifecycleStatus());
        assertEquals(updatedAt.atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime(), result.getItems().get(0).getUpdatedAt());
    }

    @Test
    void adminDetailConverter_shouldAlwaysExposeDraftCollections() {
        var value = cn.jualn.miniapp.module.activity.bo.AdminActivityDetailBO.builder()
                .id(7L).title("活动").publishStatus(0).lifecycleStatus(0)
                .createdAt(LocalDateTime.of(2026, 9, 14, 0, 30))
                .updatedAt(LocalDateTime.of(2026, 9, 14, 0, 31))
                .build();

        var result = new AdminActivityConverter().toDetailVO(value);

        assertEquals("7", result.activityId());
        assertEquals("DRAFT", result.publishStatus());
        assertEquals(List.of(), result.draft().getAttachments());
        assertEquals(List.of(), result.draft().getTimeline());
        assertEquals(List.of(), result.draft().getSections());
        assertEquals(List.of(), result.draft().getActions());
    }

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

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void publishedUpdateFreezesTimeAndLocationBeforeLaterSubjectChanges() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        var previous = Activity.builder().id(102L).publishStatus(1).lifecycleStatus(0).location("old room").build();
        var saved = Activity.builder().id(102L).publishStatus(1).lifecycleStatus(0).contractVersion(2L)
                .title("frozen title").summary("summary").organizer("org").audienceSummary("all")
                .category(0).audienceScope(1).participantMode(1).registrationMode(1).location("new room").build();
        var oldNode = cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO.builder().id(10L).nodeType("ACTIVITY_START")
                .label("start").startPrecision(2).startTime(LocalDateTime.of(2030, 1, 1, 15, 0)).build();
        var newNode = cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO.builder().id(10L).nodeType("ACTIVITY_START")
                .label("start").startPrecision(2).startTime(LocalDateTime.of(2030, 1, 1, 17, 0)).build();
        when(activityMapper.selectForUpdate(102L)).thenReturn(previous, saved);
        when(activityMapper.update(org.mockito.ArgumentMatchers.<Activity>isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(timelineService.listTimelinesByTarget(TargetType.ACTIVITY, 102L)).thenReturn(List.of(oldNode), List.of(newNode));
        service.updateAdminActivity(cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO.builder()
                .id(102L).operatorId(9L).title("frozen title").location("new room").build());
        var snapshot = org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot.class);
        verify(notifyService).enqueueBusinessNotification(org.mockito.ArgumentMatchers.eq(TargetType.ACTIVITY), org.mockito.ArgumentMatchers.eq(102L),
                org.mockito.ArgumentMatchers.eq("activity:102:time:v2"), org.mockito.ArgumentMatchers.eq(NotifyType.ACTIVITY_TIME_CHANGED),
                org.mockito.ArgumentMatchers.eq("SUBSCRIBERS_OR_REGISTERED_USERS"), any(), any(), snapshot.capture());
        newNode.setStartTime(LocalDateTime.of(2030, 1, 1, 19, 0)); saved.setTitle("changed later");
        assertTrue(snapshot.getValue().presentation().changes().get(0).before().contains("15:00"));
        assertTrue(snapshot.getValue().presentation().changes().get(0).after().contains("17:00"));
        assertEquals("frozen title", snapshot.getValue().presentation().context());
        assertEquals("frozen title", snapshot.getValue().presentation().subjectTitle());
        var location = org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot.class);
        verify(notifyService).enqueueBusinessNotification(org.mockito.ArgumentMatchers.eq(TargetType.ACTIVITY), org.mockito.ArgumentMatchers.eq(102L),
                any(), org.mockito.ArgumentMatchers.eq(NotifyType.ACTIVITY_LOCATION_CHANGED), any(), any(), any(), location.capture());
        assertEquals("old room", location.getValue().presentation().changes().get(0).before());
        assertEquals("new room", location.getValue().presentation().changes().get(0).after());
    }

    @Test
    void createActivity_shouldRejectLegacyWrite() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.createActivity(null));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void getActivityDetail_shouldLoadAndEnrichWhenCacheMiss() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        Activity activity = Activity.builder().id(5L).userId(11L).publishStatus(1).lifecycleStatus(0).build();
        ActivityDetailBO detailBO = ActivityDetailBO.builder().id(5L).userId(11L).build();

        when(redisService.get(RedisKeyConstant.activityDetail(5L), ActivityDetailBO.class)).thenReturn(null);
        when(activityMapper.selectByIdNotDeleted(5L)).thenReturn(activity);
        when(activityConverter.toDetailBO(activity)).thenReturn(detailBO);
        when(userService.getSimpleInfo(11L)).thenReturn(UserSimpleBO.builder().id(11L).nickname("author").build());
        when(mediaService.listAttachments(TargetType.ACTIVITY, 5L)).thenReturn(List.of());
        when(timelineService.listTimelinesByTarget(TargetType.ACTIVITY, 5L)).thenReturn(List.of());
        when(interactService.isLiked(TargetType.ACTIVITY, 5L)).thenReturn(true);
        when(activityEnrollmentService.isEnrolled(5L)).thenReturn(false);

        ActivityDetailBO result = service.getActivityDetail(5L);

        assertTrue(result.getLiked());
        assertFalse(result.getEnrolled());
        verify(redisService).set(RedisKeyConstant.activityDetail(5L), detailBO, RedisKeyConstant.ACTIVITY_DETAIL_TTL);
    }

    @Test
    void increaseCommentCount_shouldUpdateDbAndEvictDetailCache() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));

        service.increaseCommentCount(88L);

        verify(activityMapper).increaseCommentCount(88L);
        verify(redisService).delete(RedisKeyConstant.activityDetail(88L));
    }

    @Test
    void updateActivity_shouldRejectLegacyOwnerWrite() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.updateActivity(null));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void updateActivity_shouldThrowWhenUserIsNotOwner() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.updateActivity(null));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void removeActivity_shouldRejectLegacyAdminWrite() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.removeActivity(1L));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void removeActivity_shouldThrowWhenUserIsNotOwner() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.removeActivity(1L));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void pageAdminActivities_shouldReturnCanonicalPageAndTotal() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        AdminActivityListRow row = new AdminActivityListRow();
        row.setId(101L);
        row.setCategory(0);
        row.setPublishStatus(1);
        row.setLifecycleStatus(0);
        row.setAudienceScope(1);

        when(activityMapper.selectAdminActivityPage(
                1, 0, null, null, 20, 20))
                .thenReturn(List.of(row));
        when(activityMapper.countAdminActivities(1, 0, null, null)).thenReturn(41L);

        AdminActivityPageBO result = service.pageAdminActivities(AdminActivityQueryBO.builder()
                .publishStatus(1)
                .lifecycleStatus(0)
                .sort("-updatedAt")
                .page(2)
                .pageSize(20)
                .build());

        assertEquals(2, result.getPage());
        assertEquals(20, result.getPageSize());
        assertEquals(41L, result.getTotalItems());
        assertEquals(101L, result.getItems().get(0).getId());
    }

    @Test
    void submitAdminActivityReview_shouldRejectRetiredReview() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> service.submitAdminActivityReview(1L, 9L));
        org.mockito.Mockito.verifyNoInteractions(activityMapper, mediaService, notifyService);
    }

    @Test
    void cancelAdminActivity_shouldCancelPendingNotificationPlans() {
        ActivityServiceImpl service = new ActivityServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class), activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, notifyService, new cn.jualn.miniapp.module.eventcontent.service.EventContactCodec(new com.fasterxml.jackson.databind.ObjectMapper()), new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false)), org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.service.ActivityRegistrationService.class), new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false));
        when(activityMapper.selectForUpdate(102L)).thenReturn(Activity.builder()
                .id(102L)
                .title("迎新活动")
                .publishStatus(1)
                .lifecycleStatus(0)
                .build());
        when(activityMapper.cancelAdminActivity(102L, "场地不可用")).thenReturn(1);

        service.cancelAdminActivity(102L, 9L, "场地不可用");

        verify(notifyService).cancelActivityPlans(102L);
        var snapshot = org.mockito.ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot.class);
        verify(notifyService).enqueueBusinessNotification(org.mockito.ArgumentMatchers.eq(TargetType.ACTIVITY), org.mockito.ArgumentMatchers.eq(102L),
                org.mockito.ArgumentMatchers.eq("activity:102:cancel:v2"), org.mockito.ArgumentMatchers.eq(NotifyType.ACTIVITY_CANCELLED),
                org.mockito.ArgumentMatchers.eq("SUBSCRIBERS_OR_REGISTERED_USERS"), org.mockito.ArgumentMatchers.eq("活动已取消"),
                org.mockito.ArgumentMatchers.eq("活动「迎新活动」已取消：场地不可用"), snapshot.capture());
        assertEquals("迎新活动", snapshot.getValue().presentation().subjectTitle());
        verify(redisService).delete(RedisKeyConstant.activityDetail(102L));
    }

}




