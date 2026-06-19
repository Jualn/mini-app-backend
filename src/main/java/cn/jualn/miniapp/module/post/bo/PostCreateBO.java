package cn.jualn.miniapp.module.post.bo;

import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostCreateBO {

    /** 帖子标题，可为空。 */
    private String title;

    /** 帖子正文。 */
    private String content;

    /** 帖子图片 URL 列表，最多 9 张。 */
    private List<AttachmentItemBO> attachmentItems;
}
