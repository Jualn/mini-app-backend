package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.bo.ActivityDetailBO;
import cn.jualn.miniapp.module.activity.bo.ActivityListBO;
import cn.jualn.miniapp.module.activity.bo.ActivityPageBO;
import cn.jualn.miniapp.module.activity.bo.ActivitySubscriptionBO;
import cn.jualn.miniapp.module.activity.service.ActivityFormPolicy;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailResourceVO;
import cn.jualn.miniapp.module.activity.vo.ActivitySummaryVO;
import cn.jualn.miniapp.module.activity.vo.ActivitySubscriptionVO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import cn.jualn.miniapp.module.timeline.service.TimelineSchedulePolicy;

/** Representation conversion only; eligibility and count snapshots come from ActivityService. */
@Component
@RequiredArgsConstructor
public class ActivityResourceConverter {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> DISCOVERY_DEPARTMENTS =
            Set.of("information", "science", "finance", "humanities", "foundation");
    private final ObjectMapper objectMapper;

    public ActivityPageBO query(String cursor, int pageSize, String query, String sort,
            String category, String lifecycleStatus) {
        return query(cursor, pageSize, query, sort, category, lifecycleStatus, null, null);
    }

    public ActivityPageBO query(String cursor, int pageSize, String query, String sort,
            String category, String lifecycleStatus, String audienceFilter, String departmentId) {
        if (!"-publishedAt".equals(sort)) {
            throw queryError("sort", "sort only supports -publishedAt");
        }
        if (audienceFilter != null && !Set.of("ALL", "CAMPUS", "DEPARTMENT").contains(audienceFilter)) {
            throw queryError("audienceFilter", "invalid audienceFilter");
        }
        if ("DEPARTMENT".equals(audienceFilter)) {
            if (departmentId == null || !DISCOVERY_DEPARTMENTS.contains(departmentId)) {
                throw queryError("departmentId", "DEPARTMENT requires a canonical departmentId");
            }
        } else if (departmentId != null) {
            throw queryError("departmentId", "departmentId is only allowed with DEPARTMENT");
        }
        var result = new ActivityPageBO();
        if (query != null && query.isBlank()) {
            throw queryError("q", "q must not be blank");
        }
        result.setCursor(cursor);
        result.setPageSize(pageSize);
        result.setKeyword(query == null ? null : query.trim());
        // The current identity model has no confirmed department fact. Contract fallback is campus-only discovery.
        result.setCampusAudienceOnly(audienceFilter == null || "CAMPUS".equals(audienceFilter));
        result.setAudienceFilter(audienceFilter);
        result.setDepartmentId(departmentId);
        result.setCategory(category == null ? null : switch (category) {
            case "LECTURE" -> ActivityCategory.ACADEMIC_SEMINAR.getCode();
            case "SPORTS" -> ActivityCategory.SPORTS_EVENT.getCode();
            case "VOLUNTEERING" -> ActivityCategory.VOLUNTEER_SERVICE.getCode();
            case "THEMED" -> ActivityCategory.POLITICAL_THEME.getCode();
            case "COMPETITION" -> ActivityCategory.TALENT_SHOW.getCode();
            case "OTHER" -> ActivityCategory.OTHER.getCode();
            default -> throw queryError("category", "invalid category");
        });
        result.setLifecycleStatus(lifecycleStatus == null ? null : switch (lifecycleStatus) {
            case "ACTIVE" -> 0;
            case "ENDED" -> 1;
            case "CANCELLED" -> 2;
            default -> throw queryError("lifecycleStatus", "invalid lifecycleStatus");
        });
        return result;
    }

    private static ContractProblemException queryError(String field, String detail) {
        return ContractProblemException.validation(
                new ContractProblemException.Violation("query", "/" + field, "INVALID", detail));
    }

    public ActivitySummaryVO summary(ActivityListBO value) {
        var result = new ActivitySummaryVO();
        result.setActivityId(value.getId().toString());
        result.setTitle(value.getTitle());
        result.setSummary(value.getSummary());
        result.setCategory(category(value.getCategory()));
        result.setOrganizer(value.getOrganizer());
        result.setAudienceScope(audience(value.getAudienceScope(), value.getAudienceDepartmentIds()));
        result.setAudienceSummary(value.getAudienceSummary());
        result.setPrimaryLocation(value.getLocation());
        result.setRegistrationMode(registrationMode(value.getRegistrationMode(), value.getId()));
        if (value.getCardTimeline() != null) result.setCardTimeline(timeline(value.getCardTimeline()));
        result.setParticipantMode(participantMode(value.getParticipantMode(), value.getId()));
        result.setCapacity(value.getCapacity());
        result.setCapacityUnit(capacityUnit(value.getCapacityUnit()));
        result.setPublishStatus(publication(value.getPublishStatus()));
        result.setLifecycleStatus(lifecycle(value.getLifecycleStatus()));
        result.setAvailability(new ActivitySummaryVO.Availability(Objects.requireNonNull(value.getParticipationState(), "participation state"), time(Objects.requireNonNull(value.getEvaluatedAt(), "evaluation time"))));
        if (value.getSubmittedCount() != null) {
            result.setPlatformRegistrationCount(new ActivitySummaryVO.PlatformRegistrationCount(
                    value.getSubmittedCount(), time(value.getEvaluatedAt())));
        }
        if (value.getCoverAttachment() != null) {
            result.setCover(attachment(value.getCoverAttachment()));
        }
        return result;
    }

    public ActivityDetailResourceVO detail(ActivityDetailBO value) {
        var result = new ActivityDetailResourceVO();
        result.setActivityId(value.getId().toString());
        result.setTitle(value.getTitle());
        result.setSummary(value.getSummary());
        result.setCategory(category(value.getCategory()));
        result.setOrganizer(value.getOrganizer());
        result.setAudienceScope(audience(value.getAudienceScope(), value.getAudienceDepartmentIds()));
        result.setAudienceSummary(value.getAudienceSummary());
        result.setPrimaryLocation(value.getLocation());
        result.setRegistrationMode(registrationMode(value.getRegistrationMode(), value.getId()));
        result.setParticipantMode(participantMode(value.getParticipantMode(), value.getId()));
        result.setCapacity(value.getCapacity());
        result.setCapacityUnit(capacityUnit(value.getCapacityUnit()));
        result.setPublishStatus(publication(value.getPublishStatus()));
        result.setLifecycleStatus(lifecycle(value.getLifecycleStatus()));
        result.setAvailability(new ActivitySummaryVO.Availability(Objects.requireNonNull(value.getParticipationState(), "participation state"), time(Objects.requireNonNull(value.getEvaluatedAt(), "evaluation time"))));
        if (value.getSubmittedCount() != null) {
            result.setPlatformRegistrationCount(new ActivitySummaryVO.PlatformRegistrationCount(
                    value.getSubmittedCount(), time(value.getEvaluatedAt())));
        }

        result.setTimeline(value.getTimelineItems().stream()
                .sorted(java.util.Comparator.comparing(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO::getSortOrder)
                        .thenComparing(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO::getNodeKey)).map(this::timeline).toList());
        if (value.getCardTimeline() != null) result.setCardTimeline(timeline(value.getCardTimeline()));
        result.setSections(value.getSections().stream()
                .sorted(java.util.Comparator.comparing(cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO::getSortOrder)
                        .thenComparing(cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO::getSectionKey)).map(item -> new ActivityDetailResourceVO.Section(
                Objects.requireNonNull(item.getSectionKey(), "section key"), item.getTitle(), item.getContent(),
                Integer.valueOf(1).equals(item.getContentFormat()) ? "MARKDOWN" : "PLAIN_TEXT", item.getSortOrder())).toList());
        result.setActions(value.getActions().stream()
                .sorted(java.util.Comparator.comparing(cn.jualn.miniapp.module.eventcontent.bo.EventActionBO::getSortOrder)
                        .thenComparing(cn.jualn.miniapp.module.eventcontent.bo.EventActionBO::getActionKey)).map(item -> new ActivityDetailResourceVO.Action(
                Objects.requireNonNull(item.getActionKey(), "action key"), actionType(item.getActionType()),
                item.getLabel(), item.getDescription(), item.getTargetValue(),
                item.getAttachmentId() == null ? null : item.getAttachmentId().toString(), item.getSortOrder())).toList());
        result.setContacts(value.getContacts().stream().map(item -> new ActivityDetailResourceVO.Contact(
                item.contactKey(), item.name(), item.contact(), item.remark())).toList());
        result.setAttachments(value.getAttachmentItems().stream().map(this::attachment).toList());
        value.getAttachmentItems().stream().filter(item -> item.getId().equals(value.getCoverAttachmentId()))
                .findFirst().ifPresent(item -> result.setCover(attachment(item)));
        if (value.getRegistrationForm() != null) {
            result.setFormVersion(Objects.requireNonNull(value.getRegistrationForm().formVersion(), "published form version"));
            result.setRegistrationForm(form(value.getRegistrationForm().formSchema()));
        }
        return result;
    }

    public ActivitySubscriptionVO subscription(ActivitySubscriptionBO value) {
        return new ActivitySubscriptionVO(value.subscribed(), time(value.subscribedAt()));
    }

    private ActivitySummaryVO.Attachment attachment(MediaAttachmentBO value) {
        return new ActivitySummaryVO.Attachment(value.getId().toString(), switch (value.getType()) {
            case IMAGE -> "IMAGE";
            case PDF -> "PDF";
            case WORD -> "WORD";
            case URL -> "LINK";
        }, value.getOriginalName() == null ? "attachment-" + value.getId() : value.getOriginalName(), value.getUrl());
    }

    private ActivityDetailResourceVO.RegistrationForm form(JsonNode schema) {
        Objects.requireNonNull(schema, "published form");
        var fields = new ArrayList<ActivityDetailResourceVO.FormField>();
        int order = 0;
        for (JsonNode field : schema.path("fields")) {
            String type = ActivityFormPolicy.fieldType(field);
            boolean text = "text".equals(type);
            List<ActivityDetailResourceVO.FormOption> options = null;
            if (!text) {
                options = new ArrayList<>();
                for (JsonNode option : field.path("options")) {
                    options.add(new ActivityDetailResourceVO.FormOption(
                            ActivityFormPolicy.optionKey(option), option.path("label").asText()));
                }
            }
            fields.add(new ActivityDetailResourceVO.FormField(ActivityFormPolicy.fieldKey(field),
                    field.path("label").asText(), field.path("purpose").asText(), switch (type) {
                        case "text" -> "TEXT";
                        case "single_select" -> "SINGLE_SELECT";
                        case "multi_select" -> "MULTI_SELECT";
                        default -> throw new IllegalStateException("Unsupported stored form field type");
                    }, field.path("required").asBoolean(), field.path("displayOrder").asInt(order++),
                    field.hasNonNull("helpText") ? field.get("helpText").asText() : null,
                    text ? field.path("maxLength").asInt() : null, options));
        }
        fields.sort(java.util.Comparator.comparingInt(ActivityDetailResourceVO.FormField::displayOrder)
                .thenComparing(ActivityDetailResourceVO.FormField::fieldKey));
        return new ActivityDetailResourceVO.RegistrationForm(fields, schema.path("allowModification").asBoolean(true));
    }

    private ActivitySummaryVO.AudienceScope audience(Integer mask, String departmentJson) {
        if (departmentJson != null && !departmentJson.isBlank()) {
            try {
                JsonNode ids = objectMapper.readTree(departmentJson);
                if (!ids.isArray()) {
                    throw new IllegalStateException("Stored department IDs must be an array");
                }
                var values = new ArrayList<String>();
                for (JsonNode id : ids) {
                    if (!id.isTextual() || id.asText().isBlank()) {
                        throw new IllegalStateException("Stored department ID must be a nonempty string");
                    }
                    values.add(id.asText());
                }
                if (!values.isEmpty()) {
                    return new ActivitySummaryVO.AudienceScope("DEPARTMENTS", values);
                }
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Invalid stored department IDs", exception);
            }
        }
        if (mask == null || mask == 0 || (mask & 1) != 0) {
            return new ActivitySummaryVO.AudienceScope("CAMPUS", null);
        }
        var ids = new ArrayList<String>();
        for (int bit = 1; bit < 6; bit++) {
            if ((mask & (1 << bit)) != 0) {
                ids.add(Integer.toString(bit));
            }
        }
        return new ActivitySummaryVO.AudienceScope("DEPARTMENTS", ids);
    }

    private String category(ActivityCategory value) {
        return switch (Objects.requireNonNull(value, "activity category")) {
            case ACADEMIC_SEMINAR -> "LECTURE";
            case SPORTS_EVENT -> "SPORTS";
            case VOLUNTEER_SERVICE -> "VOLUNTEERING";
            case POLITICAL_THEME -> "THEMED";
            case TALENT_SHOW -> "COMPETITION";
            case OTHER -> "OTHER";
        };
    }
    private String registrationMode(Integer value, Long activityId) {
        return switch (Objects.requireNonNull(value, "registration mode")) {
            case 1 -> "NONE";
            case 2 -> "MINI_PROGRAM";
            case 3 -> "EXTERNAL";
            case 4 -> "MINI_PROGRAM_AND_EXTERNAL";
            default -> throw new IllegalStateException(
                    "Unsupported registration mode " + value + " for activity " + activityId);
        };
    }

    private ActivityDetailResourceVO.TimelineNode timeline(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO item) {
        return new ActivityDetailResourceVO.TimelineNode(
                Objects.requireNonNull(item.getNodeKey(), "timeline key"), timelineType(item.getNodeType()),
                item.getLabel(), item.getDescription(), TimelineSchedulePolicy.view(item), item.getLocation(), item.getSortOrder());
    }
    private String participantMode(Integer value, Long activityId) {
        return switch (Objects.requireNonNull(value, "participant mode")) {
            case 1 -> "INDIVIDUAL";
            case 2 -> "TEAM";
            default -> throw new IllegalStateException(
                    "Unsupported participant mode " + value + " for activity " + activityId);
        };
    }
    private String capacityUnit(Integer value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case 1 -> "PERSON";
            case 2 -> "TEAM";
            default -> throw new IllegalStateException("Unsupported capacity unit");
        };
    }
    private String publication(Integer value) {
        return switch (Objects.requireNonNull(value, "publication")) {
            case 0 -> "DRAFT";
            case 1 -> "PUBLISHED";
            case 2 -> "UNPUBLISHED";
            default -> throw new IllegalStateException("Unsupported publication");
        };
    }
    private String lifecycle(Integer value) {
        return switch (Objects.requireNonNull(value, "lifecycle")) {
            case 0 -> "ACTIVE";
            case 1 -> "ENDED";
            case 2 -> "CANCELLED";
            default -> throw new IllegalStateException("Unsupported lifecycle");
        };
    }
    private String timelineType(String value) {
        String normalized = Objects.requireNonNull(value, "timeline type").toUpperCase(java.util.Locale.ROOT);
        return Set.of("REGISTRATION_START", "REGISTRATION_END", "MATERIAL_SUBMISSION", "PRELIMINARY",
                "SEMIFINAL", "FINAL", "EXAM", "RESULT", "CERTIFICATE_COLLECTION", "ADMISSION_TICKET")
                .contains(normalized) ? normalized : "OTHER";
    }
    private String actionType(Integer value) {
        return switch (Objects.requireNonNull(value, "action type")) {
            case 0, 8 -> "OTHER";
            case 1 -> "OFFICIAL_SITE";
            case 2 -> "JOIN_GROUP";
            case 3 -> "EMAIL_SUBMISSION";
            case 4 -> "OFFICIAL_NOTICE";
            case 5 -> "VIEW_ATTACHMENT";
            case 6 -> "DOWNLOAD";
            case 7 -> "EXTERNAL_REGISTRATION";
            default -> throw new IllegalStateException("Unsupported action type");
        };
    }
    private OffsetDateTime time(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }
}
