package cn.jualn.miniapp.module.exam.vo.admin;

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
        OffsetDateTime publishedAt) {}
