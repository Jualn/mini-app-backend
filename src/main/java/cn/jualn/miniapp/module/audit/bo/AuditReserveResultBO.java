package cn.jualn.miniapp.module.audit.bo;

import cn.jualn.miniapp.common.enums.MediaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditReserveResultBO {

    private Long textAuditLogId;

    @Builder.Default
    private List<MediaItem> mediaItems = Collections.emptyList();

    public boolean hasAuditTask() {
        return textAuditLogId != null || (mediaItems != null && !mediaItems.isEmpty());
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaItem {
        private Long auditLogId;
        private MediaType mediaType;
        private String mediaUrl;
    }
}