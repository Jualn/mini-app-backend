package cn.jualn.miniapp.module.comment.vo;

import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Comment response item.
 */
@Data
@Builder
public class CommentVO {
    private Long id;
    private String content;
    private String imageUrl;
    private Integer likeCount;
    private Integer replyCount;
    private String createdAt;
    private UserSimpleBO author;
    private List<ReplyVO> previewReplies;
    private Boolean liked;
}
