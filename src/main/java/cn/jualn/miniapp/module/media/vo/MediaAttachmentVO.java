package cn.jualn.miniapp.module.media.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件响应。
 */
@Data
@Builder
public class MediaAttachmentVO {

    private Long id;
    private Integer type;
    private String url;
    private String originalName;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
