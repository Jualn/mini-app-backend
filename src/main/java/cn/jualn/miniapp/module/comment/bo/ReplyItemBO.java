package cn.jualn.miniapp.module.comment.bo;

import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.Builder;
import lombok.Data;

/**
 * Reply read facts; HTTP formatting belongs to the controller boundary.
 */
@Data
@Builder
public class ReplyItemBO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private String kind;
    private cn.jualn.miniapp.module.post.bo.PostWritePermission replyPermission;
    private Long parentId;
    private Long replyToCommentId;
    private String content;
    private Integer likeCount;
    private java.time.LocalDateTime createdAt;
    private UserSimpleBO author;
    private UserSimpleBO replyToUser;
    private Boolean liked;
}
