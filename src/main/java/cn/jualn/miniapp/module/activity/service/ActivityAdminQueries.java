package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.activity.mapper.AdminActivityListRow;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.activity.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 管理活动查询，不拥有草稿或生命周期写入。
 */
@Service
@RequiredArgsConstructor
public class ActivityAdminQueries {
    private final CurrentAuthorization authorization;
    private final EventContentService eventContentService;
    private final ActivityMapper activityMapper;
    private final ActivityEnrollmentService activityEnrollmentService;
    private final MediaQueries mediaQueries;
    private final TimelineService timelineService;

    public AdminActivityPageBO pageAdminActivities(AdminActivityQueryBO query) {
        authorization.require(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "activity:read", "ACTIVITY", "activity:read");

        int page = query.getPage() == null ? 1 : query.getPage();
        int pageSize = query.getPageSize() == null ? 20 : query.getPageSize();
        Long keywordId = parseId(query.getKeyword());
        List<AdminActivityListRow> rows = activityMapper.selectAdminActivityPage(
                query.isDeleted(), query.getPublishStatus(),
                query.getLifecycleStatus(),
                query.getKeyword(),
                keywordId,
                (page - 1) * pageSize,
                pageSize);
        var now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        var timelines = timelineService.listTimelinesByTargets(TargetType.ACTIVITY, rows.stream().map(AdminActivityListRow::getId).toList());
        List<AdminActivityListBO> items = rows.stream().map(row -> {
            var item = toAdminListBO(row);
            item.setDeletedAt(row.getDeletedAt());
            item.setEvaluatedAt(now);
            item.setStageState(ActivityStagePolicy.state(row.getLifecycleStatus(), timelines.getOrDefault(row.getId(), List.of()), now));
            return item;
        }).toList();
        long totalItems = activityMapper.countAdminActivities(
                query.isDeleted(), query.getPublishStatus(), query.getLifecycleStatus(), query.getKeyword(), keywordId);

        return AdminActivityPageBO.builder()
                .items(items)
                .page(page)
                .pageSize(pageSize)
                .totalItems(totalItems)
                .build();
    }

    public AdminActivityDetailBO getAdminActivityDetail(Long id) {
        authorization.require(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "activity:read", "ACTIVITY", "activity:read");

        return readAfterWrite(id);
    }

    AdminActivityDetailBO readAfterWrite(Long id) {
        Activity activity = activityMapper.selectAdminActivityById(id);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        return AdminActivityDetailBO.builder()
                .deletedAt(activity.getDeletedAt()).deletedBy(activity.getDeletedBy())
                .allowRegistrationAfterStart(activity.getAllowRegistrationAfterStart())
                .contractVersion(activity.getContractVersion()).lifecycleStatus(activity.getLifecycleStatus()).formVersion(activity.getFormVersion())
                .formSchema(ActivityFormPolicy.parse(activity.getFormSchema()))
                .cancelledAt(activity.getCancelledAt()).cancelReason(activity.getCancelReason())
                .publishStatus(activity.getPublishStatus())
                .summary(activity.getSummary()).audienceSummary(activity.getAudienceSummary())
                .audienceDepartmentIds(activity.getAudienceDepartmentIds()).contactsJson(activity.getContactsJson())
                .registrationMode(activity.getRegistrationMode()).participantMode(activity.getParticipantMode())
                .capacityUnit(activity.getCapacityUnit()).coverAttachmentId(activity.getCoverAttachmentId())
                .sections(eventContentService.sections(TargetType.ACTIVITY, id))
                .actions(eventContentService.actions(TargetType.ACTIVITY, id))
                .id(activity.getId())
                .userId(activity.getUserId())
                .title(activity.getTitle())
                .location(activity.getLocation())
                .category(ActivityCategory.fromCode(activity.getCategory()))
                .organizer(activity.getOrganizer())
                .officialCapacity(activity.getCapacity())
                .commentCount(activity.getCommentCount())
                .likeCount(activity.getLikeCount())
                .viewCount(activity.getViewCount())
                .subscriberCount(activityEnrollmentService.countActiveSubscribers(id))
                .notifyEnabledSubscriberCount(activityEnrollmentService.countNotifyEnabledSubscribers(id))
                .publishedAt(activity.getPublishedAt())
                .createdAt(activity.getCreatedAt())
                .updatedAt(activity.getUpdatedAt())
                .attachments(mediaQueries.listAttachments(TargetType.ACTIVITY, id))
                .timeline(timelineService.listTimelinesByTarget(TargetType.ACTIVITY, id))
                .build();
    }

    public AdminActivityDetailBO getAdminActivityDraft(Long id) {
        authorization.require(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "activity:read", "ACTIVITY", "activity:read");
        return getAdminActivityDetail(id);
    }

    private Long parseId(String keyword) {
        if (keyword == null) {
            return null;
        }
        try {
            return Long.valueOf(keyword);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private AdminActivityListBO toAdminListBO(AdminActivityListRow row) {
        return AdminActivityListBO.builder()
                .id(row.getId())
                .title(row.getTitle())
                .summary(row.getSummary())
                .category(ActivityCategory.fromCode(row.getCategory()))
                .publishStatus(row.getPublishStatus())
                .lifecycleStatus(row.getLifecycleStatus())
                .organizer(row.getOrganizer())
                .location(row.getLocation())
                .subscriberCount(row.getSubscriberCount())
                .capacity(row.getCapacity())
                .startTime(row.getStartTime())
                .endTime(row.getEndTime())
                .enrollDeadline(row.getEnrollDeadline())
                .viewCount(row.getViewCount())
                .updatedAt(row.getUpdatedAt())
                .build();
    }

}
