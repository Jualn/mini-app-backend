package cn.jualn.miniapp.module.exam.converter;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.dto.admin.AdminPublicEventSaveRequest;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import java.time.OffsetDateTime;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineScheduleRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPublicEventConverterTest {
    private final AdminPublicEventConverter converter = new AdminPublicEventConverter();

    @Test
    void canonicalDraftMapsStableKeysReferencesAndOffsetTimes() {
        var request = validRequest();
        var attachment = new AdminPublicEventSaveRequest.AttachmentLink();
        attachment.setAttachmentId("31");
        attachment.setDisplayOrder(4);
        request.setAttachments(List.of(attachment));
        request.setCoverAttachmentId("31");

        var result = converter.toSaveBO(request, 7L, 9L);

        assertTrue(result.isCanonicalFullReplacement());
        assertEquals(2, result.getEventType());
        assertEquals("node-a", result.getTimeline().get(0).getNodeKey());
        assertEquals("PUBLIC_EVENT_START", result.getTimeline().get(0).getNodeType());
        assertEquals(4, result.getAttachmentLinks().get(0).displayOrder());
        assertEquals(31L, result.getCoverAttachmentId());
        assertEquals(OffsetDateTime.parse("2027-03-01T09:00:00+08:00").toLocalDateTime(),
                result.getTimeline().get(0).getStartTime());
    }

    @Test
    void coverMustReferenceDraftAttachments() {
        var request = validRequest();
        request.setCoverAttachmentId("31");
        assertThrows(ContractProblemException.class, () -> converter.toSaveBO(request, null, 9L));
    }

    @Test
    void detailUsesZeroOrderForLegacyAttachmentWithoutSortOrder() {
        var detail = ExamDetailBO.builder().id(7L)
                .attachmentItems(List.of(MediaAttachmentBO.builder().id(31L).build()))
                .build();

        var result = converter.toDetailVO(detail);

        assertEquals(0, result.draft().getAttachments().get(0).displayOrder());
    }

    private AdminPublicEventSaveRequest validRequest() {
        var request = new AdminPublicEventSaveRequest();
        request.setTitle("程序设计竞赛");
        request.setSummary("竞赛摘要");
        request.setType("COMPETITION");
        request.setSourceName("组委会");
        request.setSourceUrl("https://example.org/notice");
        var node = new AdminPublicEventSaveRequest.TimelineNode();
        node.setNodeKey("node-a");
        node.setType("PUBLIC_EVENT_START");
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
