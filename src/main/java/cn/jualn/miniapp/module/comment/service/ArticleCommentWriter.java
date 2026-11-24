package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.comment.bo.ArticleCommentCreateBO;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.user.service.UserAccountService;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ArticleCommentWriter {
    private final CommentMapper comments;
    private final ArticleService articles;
    private final UserAccountService users;
    private final ArticleCommentNotifications notifications;

    @Transactional(rollbackFor = Exception.class)
    public Comment create(long articleId, ArticleCommentCreateBO command, long checkedUserId) {
        long userId = login();
        if (userId != checkedUserId) throw new IllegalStateException("Comment identity changed");
        users.assertContentCreationAllowed(userId);
        articles.lockInteraction(articleId, true);
        Comment root = null, reply = null;
        if (command.parentId() != null) {
            root = validReference(comments.lockArticleComment(command.parentId()), articleId);
            if (root.getParentId() != null) invalidReference();
            reply = root;
        }
        if (command.replyToCommentId() != null) {
            if (root == null) invalidReference();
            reply = validReference(comments.lockArticleComment(command.replyToCommentId()), articleId);
            if (!Objects.equals(reply.getId(), root.getId()) && !Objects.equals(reply.getParentId(), root.getId()))
                invalidReference();
        }
        Comment value = Comment.builder().targetType(TargetType.ARTICLE.getCode()).targetId(articleId).userId(userId)
                .content(command.content()).parentId(command.parentId()).replyToCommentId(command.replyToCommentId())
                .replyToUid(reply == null ? null : reply.getUserId()).status(1).auditStatus(1).likeCount(0).replyCount(0).createdAt(ArticleWriter.now().withNano(0)).updatedAt(ArticleWriter.now().withNano(0)).build();
        if (comments.insert(value) != 1) throw new IllegalStateException("Comment insert failed");
        if (root != null) {
            comments.increaseReplyCount(root.getId());
            notifications.reply(userId, reply.getUserId(), articleId, value.getId(), reply.getId(), articles.publicTitle(articleId), value.getContent(), reply.getContent());
        }
        articles.rebuildStatistics(articleId);
        return value;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long articleId, long commentId) {
        long userId = login();
        // Serialize against article interactions even when the Article is unpublished.
        Comment existing = ArticleWriter.require(comments.articleCommentIncludingDeleted(commentId));
        if (!Integer.valueOf(8).equals(existing.getTargetType()) || !Long.valueOf(articleId).equals(existing.getTargetId()))
            ArticleWriter.require(null);
        articles.lockExisting(articleId);
        if (existing.getParentId() != null) comments.lockArticleComment(existing.getParentId());
        Comment value = ArticleWriter.require(comments.lockArticleComment(commentId));
        if (!Long.valueOf(userId).equals(value.getUserId()))
            throw new ContractProblemException(HttpStatus.FORBIDDEN, "/problems/forbidden", "Only the comment author can delete it");
        if (value.getDeletedAt() != null) return;
        if (comments.deleteArticleComment(commentId, userId, ArticleWriter.now()) != 1)
            throw new IllegalStateException("Comment delete failed");
        if (value.getParentId() != null && Integer.valueOf(1).equals(value.getStatus()))
            comments.decreaseReplyCount(value.getParentId());
        articles.rebuildStatistics(articleId);
    }

    public static long login() {
        Long userId = UserContext.getUserId();
        if (userId == null)
            throw new ContractProblemException(HttpStatus.UNAUTHORIZED, "/problems/unauthorized", "Login required");
        return userId;
    }

    private Comment validReference(Comment c, long articleId) {
        if (c == null || c.getDeletedAt() != null || !Integer.valueOf(1).equals(c.getStatus()) || !Long.valueOf(articleId).equals(c.getTargetId()))
            invalidReference();
        return c;
    }

    private void invalidReference() {
        throw ContractProblemException.validation(new ContractProblemException.Violation("body", "#/parentId", "INVALID_REFERENCE", "Use a visible comment in this Article thread"));
    }
}
