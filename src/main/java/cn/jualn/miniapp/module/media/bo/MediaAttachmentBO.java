package cn.jualn.miniapp.module.media.bo;

import cn.jualn.miniapp.module.media.enums.MediaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 附件信息 BO（服务层/跨服务传输）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAttachmentBO {
    private Integer targetType;
    private Long targetId;

    private Long id;
    private MediaType type;
    private String kind;
    private Long registeredBy;
    private String retention;
    private java.time.LocalDateTime expiresAt;
    private Boolean deleted;
    private String objectKey;
    private String url;
    private String originalName;
    private Integer sortOrder;

    private String contentType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Double durationSeconds;
    private Boolean verified;
}
