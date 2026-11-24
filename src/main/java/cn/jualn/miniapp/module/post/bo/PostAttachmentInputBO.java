package cn.jualn.miniapp.module.post.bo;

import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Optional declaration properties are omitted, while title/category null remain explicit.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostAttachmentInputBO(MediaType type, String objectKey, String url, String originalName,
                                    Integer sortOrder) {
    public AttachmentItemBO input() {
        return AttachmentItemBO.builder().type(type).objectKey(objectKey).url(url).originalName(originalName).sortOrder(sortOrder).build();
    }
}
