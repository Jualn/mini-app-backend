package cn.jualn.miniapp.module.media.bo;

import cn.jualn.miniapp.common.enums.MediaType;
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

    private Long id;
    private MediaType type;
    private String url;
    private String originalName;
    private Integer sortOrder;
}