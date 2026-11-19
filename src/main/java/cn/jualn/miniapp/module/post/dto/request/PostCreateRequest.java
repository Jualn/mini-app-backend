package cn.jualn.miniapp.module.post.dto.request;

import cn.jualn.miniapp.module.media.dto.request.AttachmentItemRequest;
import cn.jualn.miniapp.module.post.PostType;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建帖子请求。
 */
@Data
public class PostCreateRequest {

    /**
     * 帖子标题，可为空。
     */
    @Size(max = 128, message = "标题不能超过128字")
    private String title;
    private PostType postType = PostType.NORMAL;
    private Long categoryId;
    @JsonSetter(nulls = Nulls.FAIL)
    private List<Long> topicIds;

    /**
     * 帖子正文。
     */
    @jakarta.validation.constraints.NotNull
    @Size(max = 5000, message = "内容不能超过5000字")
    private String content;

    /**
     * 帖子图片 URL 列表，最多 9 张。
     */
    @Size(max = 9, message = "最多上传9张图片")
    @Valid
    private List<AttachmentItemRequest> attachmentItems;
}
