package cn.jualn.miniapp.module.comment.bo;

import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Comment read facts; HTTP formatting belongs to the controller boundary.
 */
@Data
@Builder
public class CommentItemBO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private String kind;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private cn.jualn.miniapp.module.post.bo.MentorQaBO.Attribution mentorAnswer;
    private cn.jualn.miniapp.module.post.bo.PostWritePermission replyPermission;
    private String content;
    private String imageUrl;
    private Integer likeCount;
    private Integer replyCount;
    private java.time.LocalDateTime createdAt;
    private UserSimpleBO author;
    private List<ReplyItemBO> previewReplies;
    private Boolean liked;
}
