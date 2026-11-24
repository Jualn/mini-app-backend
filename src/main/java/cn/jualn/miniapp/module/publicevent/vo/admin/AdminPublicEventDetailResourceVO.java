package cn.jualn.miniapp.module.publicevent.vo.admin;

import cn.jualn.miniapp.module.media.vo.AdminAttachmentQueryVO;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminPublicEventDetailResourceVO(
        String publicEventId,
        AdminPublicEventDraftResourceVO draft,
        String publishStatus,
        String lifecycleStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime publishedAt,
        AdminAttachmentQueryVO.Projection attachmentDetails) {
    public AdminPublicEventDetailResourceVO(String publicEventId, AdminPublicEventDraftResourceVO draft, String publishStatus,
            String lifecycleStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime publishedAt) {
        this(publicEventId, draft, publishStatus, lifecycleStatus, createdAt, updatedAt, publishedAt, null);
    }
    public AdminPublicEventDetailResourceVO withAttachmentDetails(AdminAttachmentQueryVO.Projection details) {
        return new AdminPublicEventDetailResourceVO(publicEventId, draft, publishStatus, lifecycleStatus, createdAt, updatedAt, publishedAt, details);
    }
}
