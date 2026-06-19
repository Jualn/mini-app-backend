package cn.jualn.miniapp.module.media.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAttachmentSimpleBO {

    private Long id;

    private Long targetId;

    private String url;

    private Integer sortOrder;
}
