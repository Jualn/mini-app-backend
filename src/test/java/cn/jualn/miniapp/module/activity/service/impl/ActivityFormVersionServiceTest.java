package cn.jualn.miniapp.module.activity.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO;
import cn.jualn.miniapp.module.eventcontent.bo.EventActionBO;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.activity.converter.AdminActivityConverter;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.ActivityFormAvailability;
import cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy;
import cn.jualn.miniapp.module.activity.service.ActivityRegistrationService;
import cn.jualn.miniapp.module.eventcontent.service.EventContactCodec;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import java.util.stream.IntStream;

class ActivityFormVersionServiceTest {
    private static final long ACTIVITY_ID = 17L;
    private static final String FORM_VERSION = "activity-17-form-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ActivityMapper activities;
    private TimelineService timelines;
    private EventContentService content;
    private ActivityRegistrationService registrations;
    private ActivityServiceImpl service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Activity.class);
    }

    @BeforeEach
    void setUp() {
        activities = mock(ActivityMapper.class);
        timelines = mock(TimelineService.class);
        content = mock(EventContentService.class);
        registrations = mock(ActivityRegistrationService.class);
        var availability = new ActivityFormAvailability(true);
        service = new ActivityServiceImpl(content, activities, mock(ActivityConverter.class),
                mock(ActivityEnrollmentService.class), mock(MediaService.class), mock(UserService.class), timelines,
                mock(RedisService.class), mock(InteractService.class), mock(NotifyService.class),
                new EventContactCodec(JSON), new ActivityParticipationPolicy(availability), registrations, availability);
        when(activities.update(isNull(), any())).thenReturn(1);
    }

    @Test
    void draftSaveWithPlatformFormDoesNotCreateVersion() {
        Activity draft = activity(0, 2, null);
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(draft, draft);

        service.updateAdminActivity(command(2));

        verify(activities, never()).initializeFormVersionIfAbsent(anyLong(), any());
        assertNull(draft.getFormVersion());
    }

    @Test
    void firstPublishUsesTheSharedInitialVersion() {
        Activity draft = activity(0, 2, null);
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(draft);
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(registrationTimeline());
        when(activities.publishDirectly(ACTIVITY_ID, FORM_VERSION)).thenReturn(1);

        service.publishAdminActivity(ACTIVITY_ID, 9L);

        verify(activities).publishDirectly(ACTIVITY_ID, FORM_VERSION);
    }

    @Test
    void publishedPutOpeningPlatformFormInitializesVersionAndReturnsPersistedValue() {
        Activity before = activity(1, 1, null);
        Activity saved = activity(1, 2, null);
        Activity persisted = activity(1, 2, FORM_VERSION);
        persisted.setContractVersion(2L);
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(before, before, saved);
        when(activities.initializeFormVersionIfAbsent(ACTIVITY_ID, FORM_VERSION)).thenReturn(1);
        when(activities.selectAdminActivityById(ACTIVITY_ID)).thenReturn(persisted);
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(registrationTimeline());

        var result = service.replaceAdminActivity(command(2), "\"activity-17-v1\"");

        verify(activities).initializeFormVersionIfAbsent(ACTIVITY_ID, FORM_VERSION);
        assertEquals(FORM_VERSION, result.getFormVersion());
        assertEquals(FORM_VERSION, new AdminActivityConverter().toDetailVO(result).formVersion());
    }

    @Test
    void failedPublishedValidationDoesNotAttemptVersionInitialization() {
        Activity before = activity(1, 1, null);
        Activity saved = activity(1, 2, null);
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(before, before, saved);
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(List.of());

        assertThrows(BusinessException.class,
                () -> service.replaceAdminActivity(command(2), "\"activity-17-v1\""));

        verify(activities, never()).initializeFormVersionIfAbsent(anyLong(), any());
    }

    @Test
    void existingVersionSurvivesPlatformModeSwitch() {
        Activity before = activity(1, 2, FORM_VERSION);
        Activity saved = activity(1, 4, FORM_VERSION);
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(before, before, saved);
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(registrationTimeline());
        when(content.actions(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(List.of(
                cn.jualn.miniapp.module.eventcontent.bo.EventActionBO.builder()
                        .actionType(7).targetValue("https://example.test/register").build()));

        service.updateAdminActivity(command(4));

        verify(activities, never()).initializeFormVersionIfAbsent(anyLong(), any());
        assertEquals(FORM_VERSION, saved.getFormVersion());
    }

    @Test
    void existingVersionSurvivesRepeatedSaveAndAllowModificationChange() {
        Activity before = activity(1, 2, FORM_VERSION);
        Activity saved = activity(1, 2, FORM_VERSION);
        saved.setFormSchema(formSchema(false).toString());
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(before, saved);
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(registrationTimeline());
        var command = command(2);
        command.setFormSchema(formSchema(false));

        service.updateAdminActivity(command);

        verify(activities, never()).initializeFormVersionIfAbsent(anyLong(), any());
        assertEquals(FORM_VERSION, saved.getFormVersion());
    }

    @Test
    void publishedNonPlatformSaveDoesNotCreateVersion() {
        Activity before = activity(1, 1, null);
        Activity saved = activity(1, 1, null);
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(before, saved);

        service.updateAdminActivity(command(1));

        verify(activities, never()).initializeFormVersionIfAbsent(anyLong(), any());
    }

    @Test
    void formLockConflictKeepsVersionAndStopsBeforeAnyWrite() {
        Activity before = activity(1, 2, FORM_VERSION);
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(before);
        org.mockito.Mockito.doThrow(cn.jualn.miniapp.common.exception.ContractProblemException
                        .conflict("form-locked", "表单已冻结"))
                .when(registrations).validateFormChange(anyLong(), any(), any(), any(), any(), any(), any());

        var problem = assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                () -> service.updateAdminActivity(command(2)));

        assertEquals("/problems/form-locked", problem.getType());
        verify(activities, never()).update(any(), any());
        verify(activities, never()).initializeFormVersionIfAbsent(anyLong(), any());
        assertEquals(FORM_VERSION, before.getFormVersion());
    }

    @ParameterizedTest
    @MethodSource("externalParticipationModesAndTypes")
    void publishesExternalParticipationWithAnyLegalActionType(int mode, int type) {
        Activity draft = activity(0, mode, null);
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(draft);
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(registrationTimeline());
        when(content.actions(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(List.of(participationAction(type)));
        String version = mode == 4 ? FORM_VERSION : null;
        when(activities.publishDirectly(ACTIVITY_ID, version)).thenReturn(1);

        service.publishAdminActivity(ACTIVITY_ID, 9L);

        verify(activities).publishDirectly(ACTIVITY_ID, version);
    }

    @ParameterizedTest
    @MethodSource("externalParticipationModesAndTypes")
    void publishedEditAcceptsAnyLegalActionType(int mode, int type) {
        String version = mode == 4 ? FORM_VERSION : null;
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(
                activity(1, mode, version), activity(1, mode, version));
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(registrationTimeline());
        var actions = List.of(participationAction(type));
        when(content.actions(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(actions);
        var command = command(mode);
        command.setActions(actions);

        service.updateAdminActivity(command);

        verify(content).saveActions(TargetType.ACTIVITY, ACTIVITY_ID, actions);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void publishRejectsExternalParticipationWithoutAnAction(int mode) {
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(activity(0, mode, null));
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(registrationTimeline());
        when(content.actions(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(List.of());

        var problem = assertThrows(ContractProblemException.class,
                () -> service.publishAdminActivity(ACTIVITY_ID, 9L));

        assertEquals("/problems/publish-validation-failed", problem.getType());
        verify(activities, never()).publishDirectly(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void publishedEditRejectsRemovingAllExternalParticipationActions(int mode) {
        String version = mode == 4 ? FORM_VERSION : null;
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(
                activity(1, mode, version), activity(1, mode, version));
        when(timelines.listTimelinesByTarget(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(registrationTimeline());
        when(content.actions(TargetType.ACTIVITY, ACTIVITY_ID)).thenReturn(List.of());
        var command = command(mode);
        command.setActions(List.of());

        var problem = assertThrows(ContractProblemException.class, () -> service.updateAdminActivity(command));

        assertEquals("/problems/publish-validation-failed", problem.getType());
    }

    @Test
    void externalDraftMayBeSavedWithoutAnAction() {
        when(activities.selectForUpdate(ACTIVITY_ID)).thenReturn(activity(0, 3, null), activity(0, 3, null));
        service.updateAdminActivity(command(3));
        verify(content).saveActions(TargetType.ACTIVITY, ACTIVITY_ID, List.of());
    }

    private static Stream<Arguments> externalParticipationModesAndTypes() {
        return IntStream.of(3, 4).boxed().flatMap(mode ->
                IntStream.rangeClosed(1, 8).mapToObj(type -> Arguments.of(mode, type)));
    }

    private EventActionBO participationAction(int type) {
        String url = type == 3 ? "mailto:office@example.test"
                : (type == 2 || type == 8) ? null : "https://example.test/participate";
        return EventActionBO.builder().actionKey("participate").actionType(type).label("参与入口")
                .description("按说明完成参与步骤").targetValue(url).sortOrder(0).build();
    }

    private AdminActivitySaveBO command(int registrationMode) {
        boolean platform = registrationMode == 2 || registrationMode == 4;
        return AdminActivitySaveBO.builder().canonicalFullReplacement(true).id(ACTIVITY_ID).operatorId(9L)
                .title("活动").summary("活动摘要").organizer("运营").audienceSummary("全院")
                .category(ActivityCategory.OTHER).audienceScope(1).registrationMode(registrationMode)
                .participantMode(1).contactsJson("[]")
                .formSchema(platform ? formSchema(true) : null)
                .timelineItems(platform ? List.of(TimelineItemBO.builder().nodeKey("end")
                        .nodeType("REGISTRATION_END").label("报名截止")
                        .startTime(LocalDateTime.of(2027, 1, 1, 12, 0)).startPrecision(2).endPrecision(0)
                        .sortOrder(0).build()) : List.of())
                .sections(List.of()).actions(registrationMode == 4 ? List.of(
                        cn.jualn.miniapp.module.eventcontent.bo.EventActionBO.builder().actionKey("external")
                                .actionType(7).label("外部步骤").targetValue("https://example.test/register")
                                .sortOrder(0).build()) : List.of())
                .attachmentLinks(List.of()).build();
    }

    private Activity activity(int publication, int registrationMode, String formVersion) {
        boolean platform = registrationMode == 2 || registrationMode == 4;
        return Activity.builder().id(ACTIVITY_ID).contractVersion(1L).publishStatus(publication).lifecycleStatus(0)
                .title("活动").summary("活动摘要").organizer("运营").audienceSummary("全院")
                .category(ActivityCategory.OTHER.getCode()).audienceScope(1).registrationMode(registrationMode)
                .participantMode(1).contactsJson("[]").formSchema(platform ? formSchema(true).toString() : null)
                .formVersion(formVersion).build();
    }

    private List<TimelineItemDTO> registrationTimeline() {
        return List.of(TimelineItemDTO.builder().nodeKey("end").nodeType("REGISTRATION_END")
                .label("报名截止").startTime(LocalDateTime.of(2027, 1, 1, 12, 0))
                .startPrecision(2).endPrecision(0).sortOrder(0).build());
    }

    private com.fasterxml.jackson.databind.JsonNode formSchema() {
        return formSchema(true);
    }

    private com.fasterxml.jackson.databind.JsonNode formSchema(boolean allowModification) {
        try {
            return JSON.readTree("{" +
                    "\"fields\":[{\"fieldKey\":\"name\",\"label\":\"姓名\",\"purpose\":\"CUSTOM\",\"type\":\"TEXT\",\"required\":true,\"displayOrder\":0,\"maxLength\":50}]," +
                    "\"allowModification\":" + allowModification + "}");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
