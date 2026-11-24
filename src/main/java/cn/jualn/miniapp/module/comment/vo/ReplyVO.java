package cn.jualn.miniapp.module.comment.vo;

import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.Builder;
import lombok.Data;

/**
 * Reply response item.
 */
@Data
@Builder
public class ReplyVO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private String kind;
    private cn.jualn.miniapp.module.post.bo.PostWritePermission replyPermission;
    private Long parentId;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Long replyToCommentId;
    private String content;
    private Integer likeCount;
    private String createdAt;
    private UserSimpleBO author;
    private UserSimpleBO replyToUser;
    private Boolean liked;
}
