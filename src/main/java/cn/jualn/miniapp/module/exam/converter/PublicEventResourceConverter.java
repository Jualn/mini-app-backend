package cn.jualn.miniapp.module.exam.converter;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.bo.ExamPageBO;
import cn.jualn.miniapp.module.exam.bo.PublicEventSubscriptionBO;
import cn.jualn.miniapp.module.exam.vo.PublicEventDetailVO;
import cn.jualn.miniapp.module.exam.vo.PublicEventSummaryVO;
import cn.jualn.miniapp.module.exam.vo.PublicEventSubscriptionVO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import cn.jualn.miniapp.module.timeline.service.TimelineSchedulePolicy;

@Component
public class PublicEventResourceConverter {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public ExamPageBO query(String cursor, int pageSize, String query, String sort,
            String type, String lifecycleStatus) {
        if (!"-publishedAt".equals(sort)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "sort only supports -publishedAt");
        }
        var result = new ExamPageBO();
        result.setCursor(cursor);
        result.setPageSize(pageSize);
        result.setKeyword(query);
        result.setEventType(type == null ? null : switch (type) {
            case "OTHER" -> 0;
            case "EXAM" -> 1;
            case "COMPETITION" -> 2;
            case "CERTIFICATION" -> 3;
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "invalid type");
        });
        result.setLifecycleStatus(lifecycleStatus == null ? null : switch (lifecycleStatus) {
            case "ACTIVE" -> 0;
            case "ENDED" -> 1;
            case "CANCELLED" -> 2;
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "invalid lifecycleStatus");
        });
        return result;
    }

    public PublicEventSummaryVO summary(ExamDetailBO value) {
        var result = new PublicEventSummaryVO();
        summary(value, result);
        return result;
    }

    private void summary(ExamDetailBO value, PublicEventSummaryVO result) {
        result.setPublicEventId(value.getId().toString());
        result.setTitle(value.getTitle());
        result.setSummary(value.getSummary());
        result.setType(switch (Objects.requireNonNull(value.getEventType(), "public event type")) {
            case 0 -> "OTHER";
            case 1 -> "EXAM";
            case 2 -> "COMPETITION";
            case 3 -> "CERTIFICATION";
            default -> throw new IllegalStateException("Unsupported public event type");
        });
        result.setSourceName(value.getSourceName());
        result.setSourceUrl(value.getSourceUrl());
        result.setOfficialUrl(value.getOfficialUrl());
        result.setPublishStatus(publication(value.getPublishStatus()));
        result.setLifecycleStatus(lifecycle(value.getLifecycleStatus()));
        if (value.getCardTimeline() != null) result.setCardTimeline(timeline(value.getCardTimeline()));
    }

    public PublicEventDetailVO detail(ExamDetailBO value) {
        var result = new PublicEventDetailVO();
        summary(value, result);
        result.setTimeline(value.getTimelineItems().stream()
                .sorted(java.util.Comparator.comparing(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO::getSortOrder)
                        .thenComparing(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO::getNodeKey)).map(this::timeline).toList());
        result.setSections(value.getSections().stream()
                .sorted(java.util.Comparator.comparing(cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO::getSortOrder)
                        .thenComparing(cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO::getSectionKey)).map(item -> new PublicEventDetailVO.Section(
                Objects.requireNonNull(item.getSectionKey(), "section key"), item.getTitle(), item.getContent(),
                Integer.valueOf(1).equals(item.getContentFormat()) ? "MARKDOWN" : "PLAIN_TEXT", item.getSortOrder())).toList());
        result.setActions(value.getActions().stream()
                .sorted(java.util.Comparator.comparing(cn.jualn.miniapp.module.eventcontent.bo.EventActionBO::getSortOrder)
                        .thenComparing(cn.jualn.miniapp.module.eventcontent.bo.EventActionBO::getActionKey)).map(item -> new PublicEventDetailVO.Action(
                Objects.requireNonNull(item.getActionKey(), "action key"), actionType(item.getActionType()),
                item.getLabel(), item.getDescription(), item.getTargetValue(),
                item.getAttachmentId() == null ? null : item.getAttachmentId().toString(), item.getSortOrder())).toList());
        result.setContacts(value.getContacts().stream().map(item -> new PublicEventDetailVO.Contact(
                item.contactKey(), item.name(), item.contact(), item.remark())).toList());
        result.setAttachments(value.getAttachmentItems().stream().map(this::attachment).toList());
        value.getAttachmentItems().stream().filter(item -> item.getId().equals(value.getCoverAttachmentId()))
                .findFirst().ifPresent(item -> result.setCover(attachment(item)));
        return result;
    }

    public PublicEventSubscriptionVO subscription(PublicEventSubscriptionBO value) {
        return new PublicEventSubscriptionVO(value.subscribed(), time(value.subscribedAt()));
    }

    private PublicEventSummaryVO.Attachment attachment(MediaAttachmentBO value) {
        String kind = value.getKind();
        if (kind == null) kind = switch (value.getType()) {
            case IMAGE -> "IMAGE";
            case PDF -> "PDF";
            case WORD -> "WORD";
            case URL -> "LINK";
        };
        return new PublicEventSummaryVO.Attachment(value.getId().toString(), kind,
                value.getOriginalName() == null ? "attachment-" + value.getId() : value.getOriginalName(), value.getUrl());
    }

    private PublicEventDetailVO.TimelineNode timeline(cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO item) {
        return new PublicEventDetailVO.TimelineNode(
                Objects.requireNonNull(item.getNodeKey(), "timeline key"), timelineType(item.getNodeType()),
                item.getLabel(), item.getDescription(), TimelineSchedulePolicy.view(item), item.getLocation(), item.getSortOrder());
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
            case 1 -> "OFFICIAL_SITE";
            case 2 -> "JOIN_GROUP";
            case 3 -> "EMAIL_SUBMISSION";
            case 4 -> "OFFICIAL_NOTICE";
            case 5 -> "VIEW_ATTACHMENT";
            case 6 -> "DOWNLOAD";
            case 7 -> "EXTERNAL_REGISTRATION";
            case 8 -> "OTHER";
            default -> throw new IllegalStateException("Unsupported action type");
        };
    }
    private OffsetDateTime time(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }
}
