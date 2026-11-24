package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.interact.service.InteractionCounts;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.comment.bo.*;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.user.service.UserService;

import java.time.LocalDateTime;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CanonicalCommentService {
    private final CommentMapper comments;
    private final ArticleService articles;
    private final ArticleSafetyChecks safety;
    private final ArticleCommentWriter writer;
    private final UserService users;
    private final UserAccountService userAccounts;
    private final InteractionCounts interactionCounts;
    private final InteractionLikeQueries interactionLikeQueries;
    private final CommentTargetAccess targets;

    public ArticleCommentBO create(long articleId, ArticleCommentCreateBO command) {
        long userId = ArticleCommentWriter.login();
        userAccounts.assertContentCreationAllowed(userId);
        articles.assertPublic(articleId);
        if (command == null || command.content() == null || ArticleBodyCodec.blank(command.content()) || ArticleBodyCodec.length(command.content()) > 1000
                || (command.replyToCommentId() != null && command.parentId() == null))
            ArticleBodyCodec.invalid("#/content", "Invalid comment");
        if (!articles.getPublic(articleId).commentsEnabled())
            throw ContractProblemException.conflict("comments-disabled", "Comments are disabled");
        try {
            safety.text(userId, command.content());
        } catch (ContractProblemException e) {
            if ("/problems/content-rejected".equals(e.getType()))
                ArticleBodyCodec.invalid("#/content", "Comment was rejected by safety checks");
            throw e;
        }
        return result(writer.create(articleId, command, userId));
    }

    @Transactional(readOnly = true)
    public ArticleCommentPageBO list(long articleId, Long parentId, String cursor, int size) {
        long userId = ArticleCommentWriter.login();
        articles.assertPublic(articleId);
        if (size < 1 || size > 50) ArticleBodyCodec.invalidQuery("#/pageSize", "pageSize must be 1..50");
        if (parentId != null) {
            targets.requireArticle(parentId, false);
            var root = comments.articleCommentIncludingDeleted(parentId);
            if (root.getParentId() != null || !Long.valueOf(articleId).equals(root.getTargetId()))
                ArticleWriter.require(null);
        }
        String context = "comments/" + userId + "/" + articleId + "/" + parentId;
        String position = ArticleCursor.decode(cursor, context);
        LocalDateTime time = null;
        Long afterId = null;
        if (position != null) try {
            String[] parts = position.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            time = LocalDateTime.parse(parts[0]);
            afterId = Long.parseLong(parts[1]);
            if (afterId <= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw ArticleCursor.invalid();
        }
        var rows = comments.articlePage(articleId, parentId, time, afterId, size + 1);
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        String next = null;
        if (more) {
            var last = page.get(page.size() - 1);
            next = ArticleCursor.encode(last.getCreatedAt() + "|" + last.getId(), context);
        }
        return new ArticleCommentPageBO(page.stream().map(this::result).toList(), next);
    }

    public void delete(long articleId, long commentId) {
        writer.delete(articleId, commentId);
    }

    private ArticleCommentBO result(Comment c) {
        var author = users.getSimpleInfo(c.getUserId());
        if (author == null) throw new IllegalStateException("Effective comment author projection is missing");
        return new ArticleCommentBO(c.getId(), c.getTargetId(), c.getContent(), author, c.getCreatedAt(), c.getParentId(), c.getReplyToCommentId(),
                interactionCounts.getLikeCount(TargetType.COMMENT, c.getId()), interactionLikeQueries.isLiked(TargetType.COMMENT, c.getId()), c.getParentId() == null ? comments.articleReplyCount(c.getId()) : 0);
    }
}
