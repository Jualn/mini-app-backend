package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.article.service.ArticleVisibility;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Live Article-comment/root visibility for legacy existence consumers, without broad service cycles.
 */
@Service
@RequiredArgsConstructor
public class CommentSubjectVisibility {
    private final CommentMapper comments;
    private final ArticleVisibility articles;

    /**
     * Null means a non-Article comment, whose legacy existence caching remains applicable.
     */
    public Boolean articleVisibility(long id) {
        var comment = comments.selectById(id);
        return comment != null && Objects.equals(comment.getTargetType(), TargetType.ARTICLE.getCode())
                ? visible(comment) : null;
    }

    public boolean articleCommentVisible(long id) {
        var comment = comments.selectById(id);
        return comment != null && Objects.equals(comment.getTargetType(), TargetType.ARTICLE.getCode()) && visible(comment);
    }

    private boolean visible(Comment comment) {
        if (comment.getDeletedAt() != null || !Integer.valueOf(1).equals(comment.getStatus())
                || !articles.visible(comment.getTargetId())) return false;
        if (comment.getParentId() == null) return true;
        var root = comments.selectById(comment.getParentId());
        return root != null && root.getParentId() == null && root.getDeletedAt() == null
                && Integer.valueOf(1).equals(root.getStatus())
                && Objects.equals(root.getTargetType(), comment.getTargetType())
                && Objects.equals(root.getTargetId(), comment.getTargetId());
    }
}
