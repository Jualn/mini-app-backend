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
    private Long parentId;
    private String content;
    private Integer likeCount;
    private String createdAt;
    private UserSimpleBO author;
    private UserSimpleBO replyToUser;
    private Boolean liked;
}
