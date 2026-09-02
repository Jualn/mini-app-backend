package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.bo.AdminActivityDetailBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityListBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityPageBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivityQueryBO;
import cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityPageQuery;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivitySaveRequest;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityDetailVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityDraftVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityListVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityPageVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivitySummaryVO;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class AdminActivityConverter {

    public AdminActivityQueryBO toQueryBO(AdminActivityPageQuery query) {
        ActivityCategory category = toCategory(query.getCategory());
        return AdminActivityQueryBO.builder()
                .keyword(trimToNull(query.getKeyword()))
                .status(toStatusCode(query.getStatus()))
                .category(category == null ? null : category.getCode())
                .audienceMask(toAudienceMask(query.getAudience()))
                .sort(query.getSort())
                .cursor(query.getCursor())
                .pageSize(query.getPageSize())
                .build();
    }

    public AdminActivitySaveBO toSaveBO(AdminActivitySaveRequest request, Long id, Long operatorId) {
        List<TimelineItemBO> timelineItems = new ArrayList<>();
        for (int index = 0; index < safeList(request.getTimeline()).size(); index++) {
            AdminActivitySaveRequest.TimelineItem item = safeList(request.getTimeline()).get(index);
            timelineItems.add(TimelineItemBO.builder()
                    .label(item.getLabel().trim())
                    .description(trimToNull(item.getDescription()))
                    .startTime(item.getStartTime())
                    .endTime(item.getEndTime())
                    .sortOrder(index)
                    .build());
        }

        List<AttachmentItemBO> attachmentItems = new ArrayList<>();
        for (int index = 0; index < safeList(request.getAttachments()).size(); index++) {
            AdminActivitySaveRequest.AttachmentItem item = safeList(request.getAttachments()).get(index);
            MediaType mediaType = toMediaType(item.getTypeCode());
            String objectKey = trimToNull(item.getObjectKey());
            String url = trimToNull(item.getUrl());
            if (mediaType == MediaType.URL) {
                if (url == null || !url.startsWith("https://")) {
                    throw new BusinessException(ResultCode.INVALID_OPERATION, "附件外链必须使用 HTTPS");
                }
                objectKey = null;
            } else {
                if (objectKey == null) {
                    throw new BusinessException(ResultCode.INVALID_OPERATION, "COS 附件必须提交 objectKey");
                }
                url = null;
            }
            attachmentItems.add(AttachmentItemBO.builder()
                    .type(mediaType)
                    .objectKey(objectKey)
                    .url(url)
                    .originalName(trimToNull(item.getName()))
                    .sortOrder(index)
                    .build());
        }

        String qrcodeUrl = trimToNull(request.getQrcodeUrl());
        if (qrcodeUrl != null && !qrcodeUrl.startsWith("https://")) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "报名二维码地址必须使用 HTTPS");
        }

        return AdminActivitySaveBO.builder()
                .id(id)
                .operatorId(operatorId)
                .title(request.getTitle().trim())
                .content(request.getContent().trim())
                .location(request.getLocation().trim())
                .category(toCategory(request.getCategory()))
                .organizer(request.getOrganizer().trim())
                .audienceScope(toAudienceScope(request.getAudienceCodes()))
                .contactName(trimToNull(request.getContactName()))
                .contactPhone(trimToNull(request.getContactPhone()))
                .joinMethod(trimToNull(request.getJoinMethod()))
                .qrcodeUrl(qrcodeUrl)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .enrollDeadline(request.getEnrollDeadline())
                .maxParticipants(request.getMaxParticipants())
                .timelineItems(timelineItems)
                .attachmentItems(attachmentItems)
                .build();
    }

    public AdminActivityPageVO toPageVO(AdminActivityPageBO page) {
        List<AdminActivityListVO> items = page.getItems().stream().map(this::toListVO).toList();
        return AdminActivityPageVO.builder()
                .items(items)
                .summary(AdminActivitySummaryVO.builder()
                        .enrolling(page.getSummary().getEnrolling())
                        .ongoing(page.getSummary().getOngoing())
                        .reviewing(page.getSummary().getReviewing())
                        .startingSoon(page.getSummary().getStartingSoon())
                        .startingSoonWindowDays(7)
                        .build())
                .hasMore(page.getHasMore())
                .nextCursor(page.getNextCursor())
                .pageSize(page.getPageSize())
                .build();
    }

    public AdminActivityDetailVO toDetailVO(AdminActivityDetailBO detail) {
        UserSimpleBO author = detail.getAuthor();
        return AdminActivityDetailVO.builder()
                .id(detail.getId().toString())
                .title(detail.getTitle())
                .content(detail.getContent())
                .location(detail.getLocation())
                .category(toCategoryCode(detail.getCategory()))
                .status(toStatusCode(detail.getStatus()))
                .auditStatus(toAuditStatusCode(detail.getAuditStatus()))
                .rejectReason(detail.getRejectReason())
                .organizer(detail.getOrganizer())
                .audienceCodes(toAudienceCodes(detail.getAudienceScope()))
                .contactName(detail.getContactName())
                .contactPhoneMasked(maskPhone(detail.getContactPhone()))
                .joinMethod(detail.getJoinMethod())
                .qrcodeUrl(detail.getQrcodeUrl())
                .startTime(detail.getStartTime())
                .endTime(detail.getEndTime())
                .enrollDeadline(detail.getEnrollDeadline())
                .capacity(detail.getCapacity())
                .isPinned(detail.getPinned())
                .commentCount(detail.getCommentCount())
                .likeCount(detail.getLikeCount())
                .viewCount(detail.getViewCount())
                .subscriberCount(detail.getSubscriberCount())
                .notifyEnabledSubscriberCount(detail.getNotifyEnabledSubscriberCount())
                .publishedAt(detail.getPublishedAt())
                .createdAt(detail.getCreatedAt())
                .updatedAt(detail.getUpdatedAt())
                .author(author == null ? null : AdminActivityDetailVO.Author.builder()
                        .id(author.getId().toString())
                        .displayName(author.getNickname())
                        .avatarUrl(author.getAvatarUrl())
                        .build())
                .attachments(safeList(detail.getAttachments()).stream().map(this::toAttachmentVO).toList())
                .timeline(safeList(detail.getTimeline()).stream().map(this::toTimelineVO).toList())
                .build();
    }

    public AdminActivityDraftVO toDraftVO(AdminActivityDetailBO detail) {
        List<AdminActivityDraftVO.TimelineItem> timeline = new ArrayList<>();
        List<TimelineItemDTO> timelineItems = safeList(detail.getTimeline());
        for (int index = 0; index < timelineItems.size(); index++) {
            TimelineItemDTO item = timelineItems.get(index);
            timeline.add(AdminActivityDraftVO.TimelineItem.builder()
                    .id("timeline-" + detail.getId() + "-" + index)
                    .label(item.getLabel())
                    .description(emptyIfNull(item.getDescription()))
                    .startTime(item.getStartTime())
                    .endTime(item.getEndTime())
                    .build());
        }

        List<AdminActivityDraftVO.Attachment> attachments = new ArrayList<>();
        for (MediaAttachmentBO item : safeList(detail.getAttachments())) {
            attachments.add(AdminActivityDraftVO.Attachment.builder()
                    .id(item.getId().toString())
                    .type(toDraftAttachmentType(item.getType()))
                    .objectKey(item.getObjectKey())
                    .name(item.getOriginalName() == null ? item.getUrl() : item.getOriginalName())
                    .detail(item.getUrl())
                    .build());
        }
        if (detail.getQrcodeUrl() != null) {
            attachments.add(AdminActivityDraftVO.Attachment.builder()
                    .id("qrcode-" + detail.getId())
                    .type("qrcode")
                    .name("报名二维码")
                    .detail(detail.getQrcodeUrl())
                    .build());
        }

        return AdminActivityDraftVO.builder()
                .id(detail.getId().toString())
                .title(detail.getTitle())
                .category(toCategoryCode(detail.getCategory()))
                .organizer(emptyIfNull(detail.getOrganizer()))
                .content(detail.getContent())
                .location(emptyIfNull(detail.getLocation()))
                .audienceCodes(toAudienceCodes(detail.getAudienceScope()))
                .contactName(emptyIfNull(detail.getContactName()))
                .contactPhone(emptyIfNull(detail.getContactPhone()))
                .joinMethod(emptyIfNull(detail.getJoinMethod()))
                .startTime(detail.getStartTime())
                .endTime(detail.getEndTime())
                .enrollDeadline(detail.getEnrollDeadline())
                .maxParticipants(detail.getCapacity() == null ? "" : detail.getCapacity().toString())
                .timeline(timeline)
                .attachments(attachments)
                .build();
    }

    private AdminActivityListVO toListVO(AdminActivityListBO item) {
        return AdminActivityListVO.builder()
                .id(item.getId().toString())
                .title(item.getTitle())
                .summary(item.getSummary())
                .category(toCategoryCode(item.getCategory()))
                .status(toStatusCode(item.getStatus()))
                .auditStatus(toAuditStatusCode(item.getAuditStatus()))
                .organizer(item.getOrganizer())
                .location(item.getLocation())
                .audienceCodes(toAudienceCodes(item.getAudienceScope()))
                .isPinned(item.getPinned())
                .subscriberCount(item.getSubscriberCount())
                .capacity(item.getCapacity())
                .startTime(item.getStartTime())
                .endTime(item.getEndTime())
                .enrollDeadline(item.getEnrollDeadline())
                .viewCount(item.getViewCount())
                .updatedAt(item.getUpdatedAt())
                .build();
    }

    private AdminActivityDetailVO.Attachment toAttachmentVO(MediaAttachmentBO item) {
        return AdminActivityDetailVO.Attachment.builder()
                .id(item.getId().toString())
                .typeCode(toMediaTypeCode(item.getType()))
                .objectKey(item.getObjectKey())
                .url(item.getUrl())
                .name(item.getOriginalName())
                .sortOrder(item.getSortOrder())
                .build();
    }

    private AdminActivityDetailVO.TimelineItem toTimelineVO(TimelineItemDTO item) {
        return AdminActivityDetailVO.TimelineItem.builder()
                .label(item.getLabel())
                .description(item.getDescription())
                .startTime(item.getStartTime())
                .endTime(item.getEndTime())
                .sortOrder(item.getSortOrder())
                .build();
    }

    private ActivityCategory toCategory(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "other" -> ActivityCategory.OTHER;
            case "culture" -> ActivityCategory.TALENT_SHOW;
            case "volunteer" -> ActivityCategory.VOLUNTEER_SERVICE;
            case "ideology" -> ActivityCategory.POLITICAL_THEME;
            case "lecture" -> ActivityCategory.ACADEMIC_SEMINAR;
            case "sports" -> ActivityCategory.SPORTS_EVENT;
            default -> null;
        };
    }

    private String toCategoryCode(ActivityCategory category) {
        return switch (category) {
            case OTHER -> "other";
            case TALENT_SHOW -> "culture";
            case VOLUNTEER_SERVICE -> "volunteer";
            case POLITICAL_THEME -> "ideology";
            case ACADEMIC_SEMINAR -> "lecture";
            case SPORTS_EVENT -> "sports";
        };
    }

    private Integer toStatusCode(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "draft" -> ActivityStatus.DRAFT.getCode();
            case "reviewing" -> ActivityStatus.PENDING.getCode();
            case "enrolling" -> ActivityStatus.SIGNUP.getCode();
            case "ongoing" -> ActivityStatus.ONGOING.getCode();
            case "ended" -> ActivityStatus.ENDED.getCode();
            case "cancelled" -> ActivityStatus.CANCELED.getCode();
            case "rejected" -> ActivityStatus.REJECTED.getCode();
            case "removed" -> ActivityStatus.DELETED.getCode();
            default -> null;
        };
    }

    private String toStatusCode(ActivityStatus status) {
        return switch (status) {
            case DRAFT -> "draft";
            case PENDING -> "reviewing";
            case SIGNUP -> "enrolling";
            case ONGOING -> "ongoing";
            case ENDED -> "ended";
            case CANCELED -> "cancelled";
            case REJECTED -> "rejected";
            case DELETED -> "removed";
        };
    }

    private String toAuditStatusCode(Integer auditStatus) {
        if (auditStatus == null || auditStatus == 0) {
            return "pending";
        }
        return auditStatus == 1 ? "approved" : "rejected";
    }

    private Integer toAudienceMask(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "college" -> 1;
            case "information" -> 2;
            case "science" -> 4;
            case "finance" -> 8;
            case "humanities" -> 16;
            case "foundation" -> 32;
            default -> null;
        };
    }

    private Integer toAudienceScope(List<String> codes) {
        int scope = 0;
        for (String code : safeList(codes)) {
            scope |= toAudienceMask(code);
        }
        return scope;
    }

    private List<String> toAudienceCodes(Integer scope) {
        if (scope == null || scope == 0) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        addAudienceCode(codes, scope, 1, "college");
        addAudienceCode(codes, scope, 2, "information");
        addAudienceCode(codes, scope, 4, "science");
        addAudienceCode(codes, scope, 8, "finance");
        addAudienceCode(codes, scope, 16, "humanities");
        addAudienceCode(codes, scope, 32, "foundation");
        return codes;
    }

    private void addAudienceCode(List<String> codes, int scope, int mask, String code) {
        if ((scope & mask) != 0) {
            codes.add(code);
        }
    }

    private MediaType toMediaType(String code) {
        return switch (code) {
            case "link" -> MediaType.URL;
            case "image" -> MediaType.IMAGE;
            case "pdf" -> MediaType.PDF;
            case "word" -> MediaType.WORD;
            default -> null;
        };
    }

    private String toMediaTypeCode(MediaType type) {
        return switch (type) {
            case URL -> "link";
            case IMAGE -> "image";
            case PDF -> "pdf";
            case WORD -> "word";
        };
    }

    private String toDraftAttachmentType(MediaType type) {
        return switch (type) {
            case URL -> "link";
            case IMAGE -> "cover";
            case PDF, WORD -> "document";
        };
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
