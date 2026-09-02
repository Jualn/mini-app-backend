package cn.jualn.miniapp.module.media.bo;

import cn.jualn.miniapp.common.enums.MediaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentItemBO {

    private MediaType type;
    private String objectKey;
    private String url;
    private String originalName;
    private Integer sortOrder;
}
