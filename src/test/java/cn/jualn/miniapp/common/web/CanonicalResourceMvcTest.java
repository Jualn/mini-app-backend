package cn.jualn.miniapp.common.web;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.bo.ActivityDetailBO;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationBO;
import cn.jualn.miniapp.module.activity.bo.RegistrationVersionBO;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationWriteBO;
import cn.jualn.miniapp.module.activity.bo.ActivitySubscriptionBO;
import cn.jualn.miniapp.module.activity.bo.ActivityResourcePageBO;
import cn.jualn.miniapp.module.activity.controller.CanonicalActivityController;
import cn.jualn.miniapp.module.activity.controller.CanonicalActivityRegistrationController;
import cn.jualn.miniapp.module.activity.converter.ActivityResourceConverter;
import cn.jualn.miniapp.module.activity.converter.PersonalRegistrationConverter;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.ActivityRegistrationService;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.eventcontent.bo.EventContactBO;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.bo.PublicEventSubscriptionBO;
import cn.jualn.miniapp.module.exam.controller.CanonicalPublicEventController;
import cn.jualn.miniapp.module.exam.converter.PublicEventResourceConverter;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.controller.CanonicalAttachmentController;
import cn.jualn.miniapp.module.media.converter.AttachmentConverter;
import cn.jualn.miniapp.module.media.service.AttachmentReadService;
import cn.jualn.miniapp.module.media.service.MediaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MVC binding/serialization; Service doubles do not claim database or authentication proof. */
class CanonicalResourceMvcTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ActivityService activities = mock(ActivityService.class);
    private final ActivityEnrollmentService subscriptions = mock(ActivityEnrollmentService.class);
    private final ActivityRegistrationService registrations = mock(ActivityRegistrationService.class);
    private final ExamService events = mock(ExamService.class);
    private final ExamSubscriptionService eventSubscriptions = mock(ExamSubscriptionService.class);
    private final AttachmentReadService attachments = mock(AttachmentReadService.class);
    private final MediaService media = mock(MediaService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(
                new CanonicalActivityController(activities, subscriptions, new ActivityResourceConverter(mapper)),
                new CanonicalActivityRegistrationController(registrations, new PersonalRegistrationConverter()),
                new CanonicalPublicEventController(events, eventSubscriptions, new PublicEventResourceConverter()),
                new CanonicalAttachmentController(attachments, media, new AttachmentConverter()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @Test
    void attachmentContainsOnlySafeMetadataAndMapsNotFound() throws Exception {
        when(attachments.getPublicAttachment(7L)).thenReturn(MediaAttachmentBO.builder().id(7L)
                .type(MediaType.PDF).originalName("guide.pdf").url("https://example.test/guide.pdf")
                .objectKey("private-storage-key").targetId(9L).targetType(2).build());
        mvc.perform(get("/v1/attachments/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentId").value("7"))
                .andExpect(jsonPath("$.kind").value("PDF"))
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.targetId").doesNotExist());
        when(attachments.getPublicAttachment(8L)).thenThrow(new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND));
        mvc.perform(get("/v1/attachments/8")).andExpect(status().isNotFound());
    }

    @Test
    void activityPreservesContactKeysAndDoesNotInventPlatformCount() throws Exception {
        var deadline = cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO.builder()
                .nodeKey("deadline").nodeType("REGISTRATION_END").label("截止")
                .startTime(LocalDateTime.of(2026, 10, 1, 18, 0)).startPrecision(2)
                .sortOrder(0).build();
        var value = ActivityDetailBO.builder().id(9L).title("Activity").summary("Summary")
                .category(ActivityCategory.OTHER).organizer("Campus").audienceScope(1).audienceSummary("Campus")
                .registrationMode(3).participantMode(1).publishStatus(1).lifecycleStatus(0)
                .cardTimeline(deadline)
                .evaluatedAt(LocalDateTime.of(2026, 9, 13, 12, 0)).participationState("EXTERNAL")
                .contacts(List.of(new EventContactBO("office-b", "B", "b@example.test", null),
                        new EventContactBO("office-a", "A", "a@example.test", "Evenings")))
                .sections(List.of()).actions(List.of()).timelineItems(List.of(deadline))
                .attachmentItems(List.of()).build();
        when(activities.getActivityResource(9L)).thenReturn(value);
        mvc.perform(get("/v1/activities/9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contacts[0].contactKey").value("office-b"))
                .andExpect(jsonPath("$.contacts[1].remark").value("Evenings"))
                .andExpect(jsonPath("$.availability.evaluatedAt").value("2026-09-13T12:00:00+08:00"))
                .andExpect(jsonPath("$.platformRegistrationCount").doesNotExist())
                .andExpect(jsonPath("$.registrationForm").doesNotExist())
                .andExpect(jsonPath("$.registrationStartTime").doesNotExist())
                .andExpect(jsonPath("$.primaryStartTime").doesNotExist())
                .andExpect(jsonPath("$.cardTimeline.nodeKey").value("deadline"))
                .andExpect(jsonPath("$.cardTimeline.schedule.kind").value("EXACT_POINT"))
                .andExpect(jsonPath("$.cardTimeline.schedule.time").value("2026-10-01T18:00:00+08:00"))
                .andExpect(jsonPath("$.cardTimeline.schedule.startTime").doesNotExist())
                .andExpect(jsonPath("$.timeline[0].nodeKey").value("deadline"));
    }

    @Test
    void publicEventPreservesMultipleContactsAndTerminalLifecycle() throws Exception {
        var value = ExamDetailBO.builder().id(4L).title("Results").summary("Summary")
                .eventType(1).sourceName("Office").publishStatus(1).lifecycleStatus(1)
                .contacts(List.of(new EventContactBO("one", "One", "123", null),
                        new EventContactBO("two", "Two", "456", null)))
                .sections(List.of()).actions(List.of()).timelineItems(List.of()).attachmentItems(List.of()).build();
        when(events.getPublicEventResource(4L)).thenReturn(value);
        mvc.perform(get("/v1/public-events/4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contacts.length()").value(2))
                .andExpect(jsonPath("$.contacts[1].contactKey").value("two"))
                .andExpect(jsonPath("$.lifecycleStatus").value("ENDED"));
    }

    @Test
    void subscriptionUsesPutAndOmitsTimestampForInactiveRelationship() throws Exception {
        when(subscriptions.subscribeWithState(9L)).thenReturn(new ActivitySubscriptionBO(true,
                LocalDateTime.of(2026, 9, 13, 12, 0)));
        when(eventSubscriptions.getSubscriptionState(4L)).thenReturn(new PublicEventSubscriptionBO(false, null));
        mvc.perform(put("/v1/activities/9/subscription")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.subscribedAt").value("2026-09-13T12:00:00+08:00"));
        mvc.perform(get("/v1/public-events/4/subscription")).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(false))
                .andExpect(jsonPath("$.subscribedAt").doesNotExist());
    }

    @Test
    void submissionAndRestorationHaveDistinctStatusAndConditionalHeaders() throws Exception {
        ActivityRegistrationBO receipt = receipt();
        when(registrations.submitContract(eq(9L), eq("form-v1"), any(), isNull()))
                .thenReturn(new ActivityRegistrationWriteBO(receipt, true));
        when(registrations.submitContract(eq(9L), eq("form-v1"), any(), eq(new RegistrationVersionBO(3L, 2L))))
                .thenReturn(new ActivityRegistrationWriteBO(receipt, false));
        String request = "{\"formVersion\":\"form-v1\",\"answers\":[{\"fieldKey\":\"name\",\"value\":\"Alice\"}]}";
        mvc.perform(post("/v1/activities/9/registrations").contentType("application/json").content(request))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/v1/activities/9/registrations/me"))
                .andExpect(header().exists("ETag")).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.registrationId").value("3"))
                .andExpect(jsonPath("$.canModify").value(false)).andExpect(jsonPath("$.userId").doesNotExist());
        mvc.perform(post("/v1/activities/9/registrations").header("If-Match", "\"activity-registration-3-v2\"")
                        .contentType("application/json").content(request))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.registrationId").value("3"));
        when(registrations.cancelMineContract(9L, new RegistrationVersionBO(3L, 1L)))
                .thenThrow(ContractProblemException.preconditionFailed());
        mvc.perform(post("/v1/activities/9/registrations/me:cancel").header("If-Match", "\"activity-registration-3-v1\""))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.status").value(412))
                .andExpect(jsonPath("$.type").value("/problems/revision-mismatch"));
    }

    @Test
    void malformedRequestAndUnsupportedSortAreRejectedByHttpBoundary() throws Exception {
        mvc.perform(post("/v1/activities/9/registrations").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/activities").param("sort", "id"))
                .andExpect(status().isBadRequest());
        when(activities.pageActivityResources(any())).thenReturn(new ActivityResourcePageBO(List.of(), null));
        mvc.perform(get("/v1/activities")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(post("/v1/activities/9/registrations/me:cancel")
                        .header("If-Match", "W/\"activity-registration-3-v1\""))
                .andExpect(status().isPreconditionFailed());
    }

    private ActivityRegistrationBO receipt() throws Exception {
        var value = new ActivityRegistrationBO();
        value.setId(3L);
        value.setActivityId(9L);
        value.setUserId(99L);
        value.setStatus(1);
        value.setContractVersion(3L);
        value.setFormVersion("form-v1");
        value.setFormData(mapper.readTree("{\"name\":\"Alice\"}"));
        value.setSubmittedAt(LocalDateTime.of(2026, 9, 13, 12, 0));
        value.setUpdatedAt(value.getSubmittedAt());
        value.setCanModify(false);
        value.setCanCancel(true);
        return value;
    }
}
