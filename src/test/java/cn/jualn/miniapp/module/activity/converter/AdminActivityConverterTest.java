package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.activity.bo.AdminActivityDetailBO;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivitySaveRequest;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
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
