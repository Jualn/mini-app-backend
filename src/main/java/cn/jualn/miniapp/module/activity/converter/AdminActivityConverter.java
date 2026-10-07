package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.bo.AdminActivityDetailBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityListBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityPageBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityQueryBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityPageQuery;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivitySaveRequest;
import cn.jualn.miniapp.module.activity.service.ActivityFormPolicy;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailResourceVO;
import cn.jualn.miniapp.module.activity.vo.ActivitySummaryVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityDetailResourceVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityDraftResourceVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityListVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityPageVO;
import cn.jualn.miniapp.module.eventcontent.bo.EventActionBO;
import cn.jualn.miniapp.module.eventcontent.bo.EventContactBO;
import cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.model.TimelineSemantic;
import cn.jualn.miniapp.module.timeline.service.TimelineSchedulePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Maps the canonical admin protocol; persistence and lifecycle rules stay in services. */
@Component
public class AdminActivityConverter {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public AdminActivityQueryBO toQueryBO(AdminActivityPageQuery query) {
        return AdminActivityQueryBO.builder().keyword(trim(query.getQ()))
                .publishStatus(publication(query.getPublishStatus()))
                .lifecycleStatus(lifecycle(query.getLifecycleStatus())).sort(query.getSort())
                .page(query.getPage()).pageSize(query.getPageSize()).build();
    }

    public AdminActivitySaveBO toSaveBO(AdminActivitySaveRequest request, Long id, Long operatorId) {
        validateDraft(request);
        return AdminActivitySaveBO.builder().canonicalFullReplacement(true).id(id).operatorId(operatorId)
                .title(request.getTitle().trim()).summary(trim(request.getSummary()))
                .category(category(request.getCategory())).organizer(trim(request.getOrganizer()))
                .audienceScope(audienceMask(request.getAudienceScope()))
                .audienceDepartmentIds(departmentJson(request.getAudienceScope()))
                .audienceSummary(trim(request.getAudienceSummary())).location(trim(request.getPrimaryLocation()))
                .registrationMode(registrationMode(request.getRegistrationMode()))
                .participantMode(participantMode(request.getParticipantMode()))
                .capacity(request.getCapacity()).capacityUnit(capacityUnit(request.getCapacityUnit()))
                .coverAttachmentId(id(request.getCoverAttachmentId(), "coverAttachmentId"))
                .formSchema(request.getRegistrationForm() == null ? null : JSON.valueToTree(request.getRegistrationForm()))
                .timelineItems(request.getTimeline().stream().map(this::timeline).toList())
                .sections(request.getSections().stream().map(this::section).toList())
                .actions(request.getActions().stream().map(this::action).toList())
                .contactsJson(JSON.valueToTree(request.getContacts()).toString())
                .attachmentLinks(request.getAttachments().stream()
                        .map(value -> new AttachmentLinkBO(id(value.getAttachmentId(), "attachmentId"), value.getDisplayOrder()))
                        .toList()).build();
    }

    public AdminActivityPageVO toPageVO(AdminActivityPageBO page) {
        return AdminActivityPageVO.builder().items(page.getItems().stream().map(this::summary).toList())
                .page(page.getPage()).pageSize(page.getPageSize()).totalItems(page.getTotalItems()).build();
    }

    public AdminActivityDetailResourceVO toDetailVO(AdminActivityDetailBO value) {
        var draft = new AdminActivityDraftResourceVO();
        draft.setTitle(value.getTitle());
        draft.setSummary(value.getSummary());
        draft.setCoverAttachmentId(string(value.getCoverAttachmentId()));
        draft.setCategory(category(value.getCategory()));
        draft.setOrganizer(value.getOrganizer());
        draft.setAudienceScope(audience(value.getAudienceScope(), value.getAudienceDepartmentIds()));
        draft.setAudienceSummary(value.getAudienceSummary());
        draft.setPrimaryLocation(value.getLocation());
        draft.setRegistrationMode(registrationMode(value.getRegistrationMode()));
        draft.setParticipantMode(participantMode(value.getParticipantMode()));
        draft.setCapacity(value.getOfficialCapacity());
        draft.setCapacityUnit(capacityUnit(value.getCapacityUnit()));
        draft.setRegistrationForm(form(value.getFormSchema()));
        draft.setTimeline(safe(value.getTimeline()).stream().sorted(java.util.Comparator
                .comparingInt(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO::getSortOrder)
                .thenComparing(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO::getNodeKey)).map(item -> new ActivityDetailResourceVO.TimelineNode(
                item.getNodeKey(), timelineType(item.getNodeType()), item.getLabel(), item.getDescription(),
                TimelineSchedulePolicy.view(item), item.getLocation(), item.getSortOrder())).toList());
        draft.setSections(safe(value.getSections()).stream().sorted(java.util.Comparator
                .comparingInt(EventSectionBO::getSortOrder).thenComparing(EventSectionBO::getSectionKey)).map(item -> new ActivityDetailResourceVO.Section(
                item.getSectionKey(), item.getTitle(), item.getContent(),
                Integer.valueOf(1).equals(item.getContentFormat()) ? "MARKDOWN" : "PLAIN_TEXT", item.getSortOrder())).toList());
        draft.setActions(safe(value.getActions()).stream().sorted(java.util.Comparator
                .comparingInt(EventActionBO::getSortOrder).thenComparing(EventActionBO::getActionKey)).map(item -> new ActivityDetailResourceVO.Action(
                item.getActionKey(), actionType(item.getActionType()), item.getLabel(), item.getDescription(),
                item.getTargetValue(), string(item.getAttachmentId()), item.getSortOrder())).toList());
        draft.setContacts(contacts(value.getContactsJson(), null, null).stream().map(item -> new ActivityDetailResourceVO.Contact(
                item.contactKey(), item.name(), item.contact(), item.remark())).toList());
        draft.setAttachments(safe(value.getAttachments()).stream().map(item ->
                new AdminActivityDraftResourceVO.AttachmentLink(item.getId().toString(), displayOrder(item.getSortOrder()))).toList());
        return new AdminActivityDetailResourceVO(value.getId().toString(), draft,
                publication(value.getPublishStatus()), lifecycle(value.getLifecycleStatus()),
                time(value.getCreatedAt()), time(value.getUpdatedAt()), time(value.getPublishedAt()), value.getFormVersion());
    }

    private AdminActivityListVO summary(AdminActivityListBO value) {
        return AdminActivityListVO.builder().activityId(value.getId().toString()).title(value.getTitle())
                .publishStatus(publication(value.getPublishStatus())).lifecycleStatus(lifecycle(value.getLifecycleStatus()))
                .updatedAt(time(value.getUpdatedAt())).build();
    }

    private TimelineItemBO timeline(AdminActivitySaveRequest.TimelineNode value) {
        var builder = TimelineItemBO.builder().nodeKey(value.getNodeKey()).nodeType(value.getType())
                .label(value.getTitle().trim()).description(trim(value.getDescription()))
                .location(trim(value.getLocation())).sortOrder(value.getDisplayOrder());
        try {
            TimelineSchedulePolicy.apply(value.getSchedule(), builder);
        } catch (IllegalArgumentException exception) {
            throw contractInvalid("/timeline", "INVALID_TIME_RANGE".equals(exception.getMessage())
                    ? "INVALID_TIME_RANGE" : "INVALID_SCHEDULE", exception.getMessage());
        }
        return builder.build();
    }

    private EventSectionBO section(AdminActivitySaveRequest.Section value) {
        return EventSectionBO.builder().sectionKey(value.getSectionKey()).title(value.getTitle().trim())
                .content(value.getContent()).contentFormat("MARKDOWN".equals(value.getFormat()) ? 1 : 0)
                .sortOrder(value.getDisplayOrder()).build();
    }

    private EventActionBO action(AdminActivitySaveRequest.Action value) {
        return EventActionBO.builder().actionKey(value.getActionKey()).actionType(actionType(value.getType()))
                .label(value.getTitle().trim()).description(trim(value.getDescription())).targetValue(trim(value.getUrl()))
                .attachmentId(id(value.getAttachmentId(), "attachmentId")).sortOrder(value.getDisplayOrder()).build();
    }

    private void validateDraft(AdminActivitySaveRequest value) {
        unique(value.getTimeline().stream().map(AdminActivitySaveRequest.TimelineNode::getNodeKey).toList(), "nodeKey");
        unique(value.getSections().stream().map(AdminActivitySaveRequest.Section::getSectionKey).toList(), "sectionKey");
        unique(value.getActions().stream().map(AdminActivitySaveRequest.Action::getActionKey).toList(), "actionKey");
        unique(value.getContacts().stream().map(AdminActivitySaveRequest.Contact::getContactKey).toList(), "contactKey");
        unique(value.getAttachments().stream().map(AdminActivitySaveRequest.AttachmentLink::getAttachmentId).toList(), "attachmentId");
        if ((value.getCapacity() == null) != (value.getCapacityUnit() == null)) throw invalid("capacity与capacityUnit必须同时提供");
        if (value.getAudienceScope() != null) {
            boolean departments = "DEPARTMENTS".equals(value.getAudienceScope().getType());
            if (departments != (value.getAudienceScope().getDepartmentIds() != null
                    && !value.getAudienceScope().getDepartmentIds().isEmpty())) throw invalid("audienceScope结构不合法");
            if (departments) unique(value.getAudienceScope().getDepartmentIds(), "departmentId");
        }
        String mode = value.getRegistrationMode();
        if ("NONE".equals(mode) && value.getRegistrationForm() != null) throw invalid("NONE报名方式不能配置平台表单");
        if ("EXTERNAL".equals(mode) && value.getRegistrationForm() != null) throw invalid("EXTERNAL报名方式不能配置平台表单");
        if (("MINI_PROGRAM".equals(mode) || "MINI_PROGRAM_AND_EXTERNAL".equals(mode))
                && "TEAM".equals(value.getParticipantMode())) throw invalid("本期平台报名只支持个人");
        if ("TEAM".equals(value.getParticipantMode()) && value.getCapacityUnit() != null
                && !"TEAM".equals(value.getCapacityUnit())) throw invalid("团队参与的容量单位必须为TEAM");
        if ("INDIVIDUAL".equals(value.getParticipantMode()) && value.getCapacityUnit() != null
                && !"PERSON".equals(value.getCapacityUnit())) throw invalid("个人参与的容量单位必须为PERSON");
        Set<String> attachments = new HashSet<>(value.getAttachments().stream()
                .map(AdminActivitySaveRequest.AttachmentLink::getAttachmentId).toList());
        if (value.getCoverAttachmentId() != null && !attachments.contains(value.getCoverAttachmentId())) throw invalid("封面必须引用attachments");
        for (var action : value.getActions()) {
            if (action.getDescription() == null && action.getUrl() == null && action.getAttachmentId() == null) throw invalid("Action至少需要一种目标信息");
            if (action.getAttachmentId() != null && !attachments.contains(action.getAttachmentId())) throw invalid("Action附件必须引用attachments");
            if (Set.of("OFFICIAL_SITE", "EXTERNAL_REGISTRATION", "OFFICIAL_NOTICE").contains(action.getType())
                    && (action.getUrl() == null || !action.getUrl().matches("^https?://.+"))) throw invalid("该Action需要HTTP(S)地址");
            if ("EMAIL_SUBMISSION".equals(action.getType()) && (action.getUrl() == null || !action.getUrl().startsWith("mailto:"))) throw invalid("邮件提交需要mailto地址");
        }
        if (value.getRegistrationForm() != null) ActivityFormPolicy.schema(JSON.valueToTree(value.getRegistrationForm()));
    }

    private ActivityDetailResourceVO.RegistrationForm form(JsonNode schema) {
        if (schema == null) return null;
        List<ActivityDetailResourceVO.FormField> fields = new ArrayList<>();
        List<JsonNode> ordered = new ArrayList<>();
        schema.path("fields").forEach(ordered::add);
        ordered.sort(java.util.Comparator.comparingInt((JsonNode field) -> field.path("displayOrder").asInt())
                .thenComparing(ActivityFormPolicy::fieldKey));
        for (JsonNode field : ordered) {
            String type = field.path("type").asText();
            List<ActivityDetailResourceVO.FormOption> options = null;
            if (!"TEXT".equals(type)) {
                options = new ArrayList<>();
                for (JsonNode option : field.path("options")) options.add(new ActivityDetailResourceVO.FormOption(
                        option.path("optionKey").asText(), option.path("label").asText()));
            }
            fields.add(new ActivityDetailResourceVO.FormField(field.path("fieldKey").asText(), field.path("label").asText(),
                    field.path("purpose").asText(), type, field.path("required").asBoolean(),
                    field.path("displayOrder").asInt(), field.hasNonNull("helpText") ? field.get("helpText").asText() : null,
                    field.hasNonNull("maxLength") ? field.get("maxLength").asInt() : null, options));
        }
        return new ActivityDetailResourceVO.RegistrationForm(fields, schema.path("allowModification").asBoolean());
    }

    private List<EventContactBO> contacts(String value, String legacyName, String legacyContact) {
        if (value == null || value.isBlank()) return legacyContact == null ? List.of()
                : List.of(new EventContactBO("primary", legacyName == null ? "联系人" : legacyName, legacyContact, null));
        try {
            List<EventContactBO> result = new ArrayList<>();
            for (JsonNode item : JSON.readTree(value)) result.add(new EventContactBO(item.path("contactKey").asText(),
                    item.path("name").asText(), item.path("contact").asText(),
                    item.hasNonNull("remark") ? item.get("remark").asText() : null));
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored contacts", exception);
        }
    }

    private ActivitySummaryVO.AudienceScope audience(Integer mask, String json) {
        if (json != null && !json.isBlank()) {
            try {
                List<String> ids = new ArrayList<>();
                for (JsonNode item : JSON.readTree(json)) ids.add(item.asText());
                if (!ids.isEmpty()) return new ActivitySummaryVO.AudienceScope("DEPARTMENTS", ids);
            } catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid stored audience", exception); }
        }
        return mask == null ? null : new ActivitySummaryVO.AudienceScope("CAMPUS", null);
    }

    private String departmentJson(AdminActivitySaveRequest.AudienceScope value) {
        return value == null || !"DEPARTMENTS".equals(value.getType()) ? null : JSON.valueToTree(value.getDepartmentIds()).toString();
    }
    private Integer audienceMask(AdminActivitySaveRequest.AudienceScope value) { return value == null ? null : "CAMPUS".equals(value.getType()) ? 1 : 0; }
    private ActivityCategory category(String value) { return value == null ? null : switch (value) {
        case "LECTURE" -> ActivityCategory.ACADEMIC_SEMINAR; case "COMPETITION" -> ActivityCategory.TALENT_SHOW;
        case "SPORTS" -> ActivityCategory.SPORTS_EVENT; case "VOLUNTEERING" -> ActivityCategory.VOLUNTEER_SERVICE;
        case "THEMED" -> ActivityCategory.POLITICAL_THEME; case "OTHER" -> ActivityCategory.OTHER; default -> throw invalid("category不合法"); }; }
    private String category(ActivityCategory value) { return value == null ? null : switch (value) {
        case ACADEMIC_SEMINAR -> "LECTURE"; case TALENT_SHOW -> "COMPETITION"; case SPORTS_EVENT -> "SPORTS";
        case VOLUNTEER_SERVICE -> "VOLUNTEERING"; case POLITICAL_THEME -> "THEMED"; case OTHER -> "OTHER"; }; }
    private Integer publication(String value) { return value == null ? null : switch (value) { case "DRAFT" -> 0; case "PUBLISHED" -> 1; case "UNPUBLISHED" -> 2; default -> throw invalid("publishStatus不合法"); }; }
    private String publication(Integer value) { return Integer.valueOf(1).equals(value) ? "PUBLISHED" : Integer.valueOf(2).equals(value) ? "UNPUBLISHED" : "DRAFT"; }
    private Integer lifecycle(String value) { return value == null ? null : switch (value) { case "ACTIVE" -> 0; case "ENDED" -> 1; case "CANCELLED" -> 2; default -> throw invalid("lifecycleStatus不合法"); }; }
    private String lifecycle(Integer value) { return Integer.valueOf(1).equals(value) ? "ENDED" : Integer.valueOf(2).equals(value) ? "CANCELLED" : "ACTIVE"; }
    private Integer registrationMode(String value) { return value == null ? null : switch (value) { case "NONE" -> 1; case "MINI_PROGRAM" -> 2; case "EXTERNAL" -> 3; case "MINI_PROGRAM_AND_EXTERNAL" -> 4; default -> throw invalid("registrationMode不合法"); }; }
    private String registrationMode(Integer value) { return value == null ? null : switch (value) { case 2 -> "MINI_PROGRAM"; case 3 -> "EXTERNAL"; case 4 -> "MINI_PROGRAM_AND_EXTERNAL"; default -> "NONE"; }; }
    private Integer participantMode(String value) { return value == null ? null : "TEAM".equals(value) ? 2 : 1; }
    private String participantMode(Integer value) { return Integer.valueOf(2).equals(value) ? "TEAM" : value == null ? null : "INDIVIDUAL"; }
    private Integer capacityUnit(String value) { return value == null ? null : "TEAM".equals(value) ? 2 : 1; }
    private String capacityUnit(Integer value) { return Integer.valueOf(2).equals(value) ? "TEAM" : value == null ? null : "PERSON"; }
    private String timelineType(String value) { return TimelineSemantic.normalizeForRead(value); }
    private int actionType(String value) { return switch (value) { case "OFFICIAL_SITE" -> 1; case "JOIN_GROUP" -> 2; case "EMAIL_SUBMISSION" -> 3; case "OFFICIAL_NOTICE" -> 4; case "VIEW_ATTACHMENT" -> 5; case "DOWNLOAD" -> 6; case "EXTERNAL_REGISTRATION" -> 7; default -> 8; }; }
    private String actionType(Integer value) { return switch (value == null ? 8 : value) { case 1 -> "OFFICIAL_SITE"; case 2 -> "JOIN_GROUP"; case 3 -> "EMAIL_SUBMISSION"; case 4 -> "OFFICIAL_NOTICE"; case 5 -> "VIEW_ATTACHMENT"; case 6 -> "DOWNLOAD"; case 7 -> "EXTERNAL_REGISTRATION"; default -> "OTHER"; }; }
    private LocalDateTime local(OffsetDateTime value) { return value == null ? null : value.atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime(); }
    private OffsetDateTime time(LocalDateTime value) { return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime(); }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private String string(Long value) { return value == null ? null : value.toString(); }
    private int displayOrder(Integer value) { return value == null ? 0 : value; }
    private Long id(String value, String field) { if (value == null) return null; try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; } catch (NumberFormatException e) { throw invalid(field + "不是有效标识"); } }
    private void unique(List<String> values, String field) { if (new HashSet<>(values).size() != values.size()) throw invalid(field + "必须唯一"); }
    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private BusinessException invalid(String message) { return new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, message); }
    private cn.jualn.miniapp.common.exception.ContractProblemException contractInvalid(
            String pointer, String code, String message) {
        return cn.jualn.miniapp.common.exception.ContractProblemException.validation(
                new cn.jualn.miniapp.common.exception.ContractProblemException.Violation(
                        "body", pointer, code, message));
    }
}
