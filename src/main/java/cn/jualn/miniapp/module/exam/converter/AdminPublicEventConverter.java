package cn.jualn.miniapp.module.exam.converter;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.eventcontent.bo.EventActionBO;
import cn.jualn.miniapp.module.eventcontent.bo.EventContactBO;
import cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO;
import cn.jualn.miniapp.module.exam.bo.AdminPublicEventPageBO;
import cn.jualn.miniapp.module.exam.bo.AdminPublicEventQueryBO;
import cn.jualn.miniapp.module.exam.bo.AdminPublicEventSaveBO;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.dto.admin.AdminPublicEventPageQuery;
import cn.jualn.miniapp.module.exam.dto.admin.AdminPublicEventSaveRequest;
import cn.jualn.miniapp.module.exam.vo.PublicEventDetailVO;
import cn.jualn.miniapp.module.exam.vo.admin.AdminPublicEventDetailResourceVO;
import cn.jualn.miniapp.module.exam.vo.admin.AdminPublicEventDraftResourceVO;
import cn.jualn.miniapp.module.exam.vo.admin.AdminPublicEventListVO;
import cn.jualn.miniapp.module.exam.vo.admin.AdminPublicEventPageVO;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.model.TimelineSemantic;
import cn.jualn.miniapp.module.timeline.service.TimelineSchedulePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Canonical admin representation mapping; business state remains in ExamService. */
@Component
public class AdminPublicEventConverter {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();

    public AdminPublicEventQueryBO toQueryBO(AdminPublicEventPageQuery query) {
        return AdminPublicEventQueryBO.builder().keyword(trim(query.getQ()))
                .publishStatus(publication(query.getPublishStatus()))
                .lifecycleStatus(lifecycle(query.getLifecycleStatus())).sort(query.getSort())
                .page(query.getPage()).pageSize(query.getPageSize()).build();
    }

    public AdminPublicEventSaveBO toSaveBO(AdminPublicEventSaveRequest request, Long id, Long operatorId) {
        validateDraft(request);
        return AdminPublicEventSaveBO.builder().canonicalFullReplacement(true).id(id).operatorId(operatorId)
                .title(request.getTitle().trim()).summary(trim(request.getSummary()))
                .eventType(type(request.getType())).sourceName(trim(request.getSourceName()))
                .sourceUrl(trim(request.getSourceUrl())).officialUrl(trim(request.getOfficialUrl()))
                .coverAttachmentId(id(request.getCoverAttachmentId(), "coverAttachmentId"))
                .timeline(request.getTimeline().stream().map(this::timeline).toList())
                .sections(request.getSections().stream().map(this::section).toList())
                .actions(request.getActions().stream().map(this::action).toList())
                .contactsJson(JSON.valueToTree(request.getContacts()).toString())
                .attachmentLinks(request.getAttachments().stream()
                        .map(value -> new AttachmentLinkBO(id(value.getAttachmentId(), "attachmentId"), value.getDisplayOrder()))
                        .toList()).build();
    }

    public AdminPublicEventPageVO toPageVO(AdminPublicEventPageBO page) {
        return new AdminPublicEventPageVO(page.getItems().stream().map(value -> new AdminPublicEventListVO(
                value.getId().toString(), value.getTitle(), publication(value.getPublishStatus()),
                lifecycle(value.getLifecycleStatus()), time(value.getUpdatedAt()))).toList(),
                page.getPage(), page.getPageSize(), page.getTotalItems());
    }

    public AdminPublicEventDetailResourceVO toDetailVO(ExamDetailBO value) {
        var draft = new AdminPublicEventDraftResourceVO();
        draft.setTitle(value.getTitle());
        draft.setSummary(value.getSummary());
        draft.setCoverAttachmentId(string(value.getCoverAttachmentId()));
        draft.setType(type(value.getEventType()));
        draft.setSourceName(value.getSourceName());
        draft.setSourceUrl(value.getSourceUrl());
        draft.setOfficialUrl(value.getOfficialUrl());
        draft.setTimeline(safe(value.getTimelineItems()).stream().sorted(java.util.Comparator
                .comparingInt(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO::getSortOrder)
                .thenComparing(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO::getNodeKey)).map(item ->
                new PublicEventDetailVO.TimelineNode(item.getNodeKey(), timelineType(item.getNodeType()), item.getLabel(),
                        item.getDescription(), TimelineSchedulePolicy.view(item), item.getLocation(), item.getSortOrder())).toList());
        draft.setSections(safe(value.getSections()).stream().sorted(java.util.Comparator
                .comparingInt(EventSectionBO::getSortOrder).thenComparing(EventSectionBO::getSectionKey)).map(item ->
                new PublicEventDetailVO.Section(item.getSectionKey(), item.getTitle(), item.getContent(),
                        Integer.valueOf(1).equals(item.getContentFormat()) ? "MARKDOWN" : "PLAIN_TEXT", item.getSortOrder())).toList());
        draft.setActions(safe(value.getActions()).stream().sorted(java.util.Comparator
                .comparingInt(EventActionBO::getSortOrder).thenComparing(EventActionBO::getActionKey)).map(item ->
                new PublicEventDetailVO.Action(item.getActionKey(), actionType(item.getActionType()), item.getLabel(),
                        item.getDescription(), item.getTargetValue(), string(item.getAttachmentId()), item.getSortOrder())).toList());
        draft.setContacts(contacts(value.getContactsJson()).stream().map(item ->
                new PublicEventDetailVO.Contact(item.contactKey(), item.name(), item.contact(), item.remark())).toList());
        draft.setAttachments(safe(value.getAttachmentItems()).stream().map(item ->
                new AdminPublicEventDraftResourceVO.AttachmentLink(item.getId().toString(), displayOrder(item.getSortOrder()))).toList());
        return new AdminPublicEventDetailResourceVO(value.getId().toString(), draft,
                publication(value.getPublishStatus()), lifecycle(value.getLifecycleStatus()),
                time(value.getCreatedAt()), time(value.getUpdatedAt()), time(value.getPublishedAt()));
    }

    private TimelineItemBO timeline(AdminPublicEventSaveRequest.TimelineNode value) {
        var builder = TimelineItemBO.builder().nodeKey(value.getNodeKey()).nodeType(value.getType())
                .label(value.getTitle().trim()).description(trim(value.getDescription()))
                .location(trim(value.getLocation())).sortOrder(value.getDisplayOrder());
        try { TimelineSchedulePolicy.apply(value.getSchedule(), builder); }
        catch (IllegalArgumentException exception) { throw invalid("/timeline", "INVALID_SCHEDULE", exception.getMessage()); }
        return builder.build();
    }

    private EventSectionBO section(AdminPublicEventSaveRequest.Section value) {
        return EventSectionBO.builder().sectionKey(value.getSectionKey()).title(value.getTitle().trim())
                .content(value.getContent()).contentFormat("MARKDOWN".equals(value.getFormat()) ? 1 : 0)
                .sortOrder(value.getDisplayOrder()).build();
    }

    private EventActionBO action(AdminPublicEventSaveRequest.Action value) {
        return EventActionBO.builder().actionKey(value.getActionKey()).actionType(actionType(value.getType()))
                .label(value.getTitle().trim()).description(trim(value.getDescription())).targetValue(trim(value.getUrl()))
                .attachmentId(id(value.getAttachmentId(), "attachmentId")).sortOrder(value.getDisplayOrder()).build();
    }

    private void validateDraft(AdminPublicEventSaveRequest value) {
        unique(value.getTimeline().stream().map(AdminPublicEventSaveRequest.TimelineNode::getNodeKey).toList(), "/timeline", "nodeKey");
        unique(value.getSections().stream().map(AdminPublicEventSaveRequest.Section::getSectionKey).toList(), "/sections", "sectionKey");
        unique(value.getActions().stream().map(AdminPublicEventSaveRequest.Action::getActionKey).toList(), "/actions", "actionKey");
        unique(value.getContacts().stream().map(AdminPublicEventSaveRequest.Contact::getContactKey).toList(), "/contacts", "contactKey");
        unique(value.getAttachments().stream().map(AdminPublicEventSaveRequest.AttachmentLink::getAttachmentId).toList(), "/attachments", "attachmentId");
        Set<String> attachments = new HashSet<>(value.getAttachments().stream()
                .map(AdminPublicEventSaveRequest.AttachmentLink::getAttachmentId).toList());
        if (value.getCoverAttachmentId() != null && !attachments.contains(value.getCoverAttachmentId()))
            throw invalid("/coverAttachmentId", "INVALID_REFERENCE", "封面必须引用attachments");
        for (var action : value.getActions()) {
            if (action.getDescription() == null && action.getUrl() == null && action.getAttachmentId() == null)
                throw invalid("/actions", "INVALID_VALUE", "Action至少需要一种目标信息");
            if (action.getAttachmentId() != null && !attachments.contains(action.getAttachmentId()))
                throw invalid("/actions", "INVALID_REFERENCE", "Action附件必须引用attachments");
            if (Set.of("OFFICIAL_SITE", "EXTERNAL_REGISTRATION", "OFFICIAL_NOTICE").contains(action.getType())
                    && (action.getUrl() == null || !action.getUrl().matches("^https?://.+"))) throw invalid("/actions", "INVALID_VALUE", "该Action需要HTTP(S)地址");
            if ("EMAIL_SUBMISSION".equals(action.getType()) && (action.getUrl() == null || !action.getUrl().startsWith("mailto:")))
                throw invalid("/actions", "INVALID_VALUE", "邮件提交需要mailto地址");
        }
    }

    private List<EventContactBO> contacts(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<EventContactBO> result = new ArrayList<>();
            for (JsonNode item : JSON.readTree(value)) result.add(new EventContactBO(item.path("contactKey").asText(),
                    item.path("name").asText(), item.path("contact").asText(),
                    item.hasNonNull("remark") ? item.get("remark").asText() : null));
            return result;
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid stored contacts", exception); }
    }

    private Integer publication(String value) { return value == null ? null : switch (value) { case "DRAFT" -> 0; case "PUBLISHED" -> 1; case "UNPUBLISHED" -> 2; default -> throw invalid("/publishStatus", "INVALID_VALUE", "publishStatus不合法"); }; }
    private String publication(Integer value) { return Integer.valueOf(1).equals(value) ? "PUBLISHED" : Integer.valueOf(2).equals(value) ? "UNPUBLISHED" : "DRAFT"; }
    private Integer lifecycle(String value) { return value == null ? null : switch (value) { case "ACTIVE" -> 0; case "ENDED" -> 1; case "CANCELLED" -> 2; default -> throw invalid("/lifecycleStatus", "INVALID_VALUE", "lifecycleStatus不合法"); }; }
    private String lifecycle(Integer value) { return Integer.valueOf(1).equals(value) ? "ENDED" : Integer.valueOf(2).equals(value) ? "CANCELLED" : "ACTIVE"; }
    private Integer type(String value) { return value == null ? null : switch (value) { case "OTHER" -> 0; case "EXAM" -> 1; case "COMPETITION" -> 2; case "CERTIFICATION" -> 3; default -> throw invalid("/type", "INVALID_VALUE", "type不合法"); }; }
    private String type(Integer value) { return value == null ? null : switch (value) { case 0 -> "OTHER"; case 1 -> "EXAM"; case 2 -> "COMPETITION"; case 3 -> "CERTIFICATION"; default -> throw new IllegalStateException("Unsupported public event type"); }; }
    private int actionType(String value) { return switch (value) { case "OFFICIAL_SITE" -> 1; case "JOIN_GROUP" -> 2; case "EMAIL_SUBMISSION" -> 3; case "OFFICIAL_NOTICE" -> 4; case "VIEW_ATTACHMENT" -> 5; case "DOWNLOAD" -> 6; case "EXTERNAL_REGISTRATION" -> 7; default -> 8; }; }
    private String actionType(Integer value) { return switch (value == null ? 8 : value) { case 1 -> "OFFICIAL_SITE"; case 2 -> "JOIN_GROUP"; case 3 -> "EMAIL_SUBMISSION"; case 4 -> "OFFICIAL_NOTICE"; case 5 -> "VIEW_ATTACHMENT"; case 6 -> "DOWNLOAD"; case 7 -> "EXTERNAL_REGISTRATION"; default -> "OTHER"; }; }
    private String timelineType(String value) { return TimelineSemantic.normalizeForRead(value); }
    private LocalDateTime local(OffsetDateTime value) { return value == null ? null : value.atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime(); }
    private OffsetDateTime time(LocalDateTime value) { return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime(); }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private String string(Long value) { return value == null ? null : value.toString(); }
    private int displayOrder(Integer value) { return value == null ? 0 : value; }
    private Long id(String value, String field) { if (value == null) return null; try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; } catch (NumberFormatException exception) { throw invalid("/" + field, "INVALID_VALUE", field + "不是有效标识"); } }
    private void unique(List<String> values, String pointer, String field) { if (new HashSet<>(values).size() != values.size()) throw invalid(pointer, "DUPLICATE_KEY", field + "必须唯一"); }
    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private ContractProblemException invalid(String pointer, String code, String message) {
        return ContractProblemException.validation(new ContractProblemException.Violation(
                "body", pointer, code, message));
    }
}
