package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;

/**
 * Author-facing creation/deletion; public reads and moderation are separate capabilities.
 */
public interface CommentService {
    Long createComment(CommentCreateBO command);

    void removeComment(Long commentId);
}
