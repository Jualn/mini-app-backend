package cn.jualn.miniapp.module.activity.vo.admin;

import cn.jualn.miniapp.module.media.vo.AdminAttachmentQueryVO;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminActivityDetailResourceVO(
        String activityId,
        AdminActivityDraftResourceVO draft,
        String publishStatus,
        String lifecycleStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime publishedAt,
        String formVersion,
        AdminAttachmentQueryVO.Projection attachmentDetails, OffsetDateTime deletedAt, String deletedBy) {
    public AdminActivityDetailResourceVO(String activityId, AdminActivityDraftResourceVO draft, String publishStatus,
            String lifecycleStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime publishedAt, String formVersion) {
        this(activityId, draft, publishStatus, lifecycleStatus, createdAt, updatedAt, publishedAt, formVersion, null, null, null);
    }
    public AdminActivityDetailResourceVO withDeletion(OffsetDateTime at, String actor) {
        return new AdminActivityDetailResourceVO(activityId, draft, publishStatus, lifecycleStatus, createdAt, updatedAt, publishedAt, formVersion, attachmentDetails, at, actor);
    }
    public AdminActivityDetailResourceVO withAttachmentDetails(AdminAttachmentQueryVO.Projection details) {
        return new AdminActivityDetailResourceVO(activityId, draft, publishStatus, lifecycleStatus, createdAt, updatedAt, publishedAt, formVersion, details, deletedAt, deletedBy);
    }
}
