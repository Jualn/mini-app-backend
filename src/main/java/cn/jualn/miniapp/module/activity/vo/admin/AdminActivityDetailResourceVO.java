package cn.jualn.miniapp.module.activity.vo.admin;

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
        String formVersion) {
}
