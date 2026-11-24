package cn.jualn.miniapp.module.comment.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.Data;

/**
 * Comment create command.
 */
@Data
public class CommentCreateBO {

    private TargetType targetType;
    private Long targetId;
    private Long parentId;
    private Long replyToCommentId;
    private Long replyToUid;
    private String questionVersion;
    private String content;
    private String imageUrl;
    private String imageObjectKey;
}
