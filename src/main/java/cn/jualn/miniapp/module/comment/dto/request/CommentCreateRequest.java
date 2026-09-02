package cn.jualn.miniapp.module.comment.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Create comment request.
 */
@Data
public class CommentCreateRequest {

    /** Target type: 1-post, 2-activity, 3-exam. 4-comment*/
    @NotNull(message = "targetType 不能为空")
    private TargetType targetType;

    /** Target id. */
    @NotNull(message = "targetId 不能为空")
    private Long targetId;

    /** Parent comment id, null for top-level. */
    private Long parentId;

    /** Reply-to user id, for displaying "reply @user". */
    private Long replyToUid;

    /** Comment content. */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容不能超过1000字")
    private String content;

    /** Optional image URL. */
    @Size(max = 512, message = "图片URL长度不能超过512")
    private String imageUrl;

    /** Optional COS object key. The server derives imageUrl from this field. */
    @Size(max = 512, message = "图片objectKey长度不能超过512")
    private String imageObjectKey;
}
