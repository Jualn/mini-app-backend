package cn.jualn.miniapp.module.audit.bo;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.MediaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditReserveBO {

    private AuditScene auditScene;

    private Long targetId;

    /**
     * 文本内容。为空则不预占文本审核记录。
     */
    private String textContent;

    /**
     * 媒体审核项。为空则不预占媒体审核记录。
     */
    private List<MediaItem> mediaItems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaItem {
        private MediaType mediaType;
        private String mediaUrl;
    }
}