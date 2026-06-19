package cn.jualn.miniapp.module.post.dto.request;

import cn.jualn.miniapp.module.media.dto.request.AttachmentItemRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建帖子请求。
 */
@Data
public class PostCreateRequest {

    /** 帖子标题，可为空。 */
    @Size(max = 128, message = "标题不能超过128字")
    private String title;

    /** 帖子正文。 */
    @NotBlank(message = "帖子内容不能为空")
    @Size(max = 5000, message = "内容不能超过5000字")
    private String content;

    /** 帖子图片 URL 列表，最多 9 张。 */
    @Size(max = 9, message = "最多上传9张图片")
    private List<AttachmentItemRequest> attachmentItems;
}