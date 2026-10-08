package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.module.activity.bo.ActivityDetailBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityDetailBO;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivitySaveRequest;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineScheduleRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminActivityConverterTest {
    private final AdminActivityConverter converter = new AdminActivityConverter();

    @Test
    void canonicalDraftMapsStableKeysReferencesAndOffsetTimes() {
        var request = validRequest();
        var attachment = new AdminActivitySaveRequest.AttachmentLink();
        attachment.setAttachmentId("31");
        attachment.setDisplayOrder(4);
        request.setAttachments(List.of(attachment));
        request.setCoverAttachmentId("31");

        var result = converter.toSaveBO(request, 7L, 9L);

        assertTrue(result.isCanonicalFullReplacement());
        assertEquals("node-a", result.getTimelineItems().get(0).getNodeKey());
        assertEquals("ACTIVITY_START", result.getTimelineItems().get(0).getNodeType());
        assertEquals(4, result.getAttachmentLinks().get(0).displayOrder());
        assertEquals(31L, result.getCoverAttachmentId());
        assertEquals(2, result.getTimelineItems().get(0).getStartPrecision());
    }

    @Test
    void coverMustReferenceDraftAttachments() {
        var request = validRequest();
        request.setCoverAttachmentId("31");
        assertThrows(BusinessException.class, () -> converter.toSaveBO(request, null, 9L));
    }

    @Test
    void officialSiteActionRequiresHttpUrl() {
        var request = validRequest();
        var action = new AdminActivitySaveRequest.Action();
        action.setActionKey("official");
        action.setType("OFFICIAL_SITE");
        action.setTitle("官网");
        action.setDescription("查看官网");
        action.setDisplayOrder(0);
        request.setActions(List.of(action));
        assertThrows(BusinessException.class, () -> converter.toSaveBO(request, null, 9L));
    }

    @Test
    void detailUsesZeroOrderForLegacyAttachmentWithoutSortOrder() {
        var detail = AdminActivityDetailBO.builder().id(7L)
                .attachments(List.of(MediaAttachmentBO.builder().id(31L).build()))
                .build();

        var result = converter.toDetailVO(detail);

        assertEquals(0, result.draft().getAttachments().get(0).displayOrder());
    }

    @Test
    void adminSavedActionsKeepTheirTypesWhenReadAsPublicDetail() {
        var request = validRequest();
        var types = List.of("OFFICIAL_SITE", "JOIN_GROUP", "EMAIL_SUBMISSION", "OFFICIAL_NOTICE",
                "VIEW_ATTACHMENT", "DOWNLOAD", "EXTERNAL_REGISTRATION", "OTHER");
        var actions = java.util.stream.IntStream.range(0, types.size()).mapToObj(index -> {
            var action = new AdminActivitySaveRequest.Action();
            action.setActionKey("action-" + index);
            action.setType(types.get(index));
            action.setTitle("入口 " + index);
            action.setDescription("Synthetic action");
            action.setUrl("EMAIL_SUBMISSION".equals(types.get(index))
                    ? "mailto:office@example.test" : "https://example.test/action/" + index);
            action.setDisplayOrder(index);
            return action;
        }).toList();
        request.setActions(actions);
        var saved = converter.toSaveBO(request, 7L, 9L);
        var detail = ActivityDetailBO.builder().id(7L)
                .category(ActivityCategory.OTHER).audienceScope(1)
                .registrationMode(3).participantMode(1).publishStatus(1).lifecycleStatus(0)
                .participationState("EXTERNAL").evaluatedAt(java.time.LocalDateTime.of(2026, 10, 8, 13, 0))
                .contacts(List.of()).sections(List.of()).actions(saved.getActions())
                .timelineItems(List.of()).attachmentItems(List.of()).build();
        var response = new ActivityResourceConverter(new ObjectMapper()).detail(detail);
        assertEquals(types, response.getActions().stream().map(action -> action.type()).toList());
        assertEquals(actions.stream().map(action -> action.getActionKey()).toList(),
                response.getActions().stream().map(action -> action.actionKey()).toList());
    }

    private AdminActivitySaveRequest validRequest() {
        var request = new AdminActivitySaveRequest();
        request.setTitle("校园活动");
        request.setSummary("活动摘要");
        request.setCategory("COMPETITION");
        request.setOrganizer("学生会");
        var audience = new AdminActivitySaveRequest.AudienceScope();
        audience.setType("CAMPUS");
        request.setAudienceScope(audience);
        request.setAudienceSummary("全院学生");
        request.setParticipantMode("INDIVIDUAL");
        request.setRegistrationMode("NONE");
        var node = new AdminActivitySaveRequest.TimelineNode();
        node.setNodeKey("node-a");
        node.setType("ACTIVITY_START");
        node.setTitle("开始");
        var schedule = new TimelineScheduleRequest();
        schedule.setKind("EXACT_POINT");
        schedule.setTime(OffsetDateTime.parse("2027-03-01T09:00:00+08:00"));
        node.setSchedule(schedule);
        node.setDisplayOrder(0);
        request.setTimeline(List.of(node));
        request.setSections(List.of());
        request.setActions(List.of());
        request.setContacts(List.of());
        request.setAttachments(List.of());
        return request;
    }
}
