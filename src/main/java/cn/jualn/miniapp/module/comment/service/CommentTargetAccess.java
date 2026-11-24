package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.service.ActivityPublicAccess;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.service.PostPublicAccess;
import cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CommentTargetAccess {
    private final CommentMapper comments;
    private final ArticleService articles;
    private final PostPublicAccess posts;
    private final ActivityPublicAccess activities;
    private final PublicEventPublicAccess events;
    private final cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper community;

    /**
     * Dispatches only supported interaction subjects; their owners decide visibility.
     */
    public void requireSubject(TargetType type, Long id, boolean mutation) {
        if (type == null || id == null)
            throw new BusinessException(ResultCode.BAD_REQUEST);
        switch (type) {
            case POST -> posts.require(id, mutation);
            case ACTIVITY -> activities.require(id, mutation);
            case EXAM -> events.require(id, mutation);
            case ARTICLE -> {
                if (mutation) articles.lockInteraction(id, false);
                else articles.assertPublic(id);
            }
            default ->
                    throw new BusinessException(ResultCode.INVALID_TARGET_TYPE);
        }
    }

    /**
     * A comment ID alone grants no access: verify its actual subject and root thread.
     */
    public void requirePublic(long commentId, boolean mutation) {
        Comment comment = comments.selectInteractionTarget(commentId);
        requireVisible(comment);
        TargetType type = TargetType.fromCode(comment.getTargetType());
        if (type == TargetType.ARTICLE) {
            requireArticle(commentId, mutation);
            return;
        }
        requireSubject(type, comment.getTargetId(), mutation);
        if (comment.getParentId() != null) {
            Comment parent = mutation ? (type==TargetType.POST && posts.isNormal(comment.getTargetId()) ? community.lock(comment.getParentId()) : comments.lockReplyTarget(comment.getParentId())) : comments.selectInteractionTarget(comment.getParentId());
            if(!(type==TargetType.POST && posts.isNormal(comment.getTargetId()) && Boolean.TRUE.equals(comment.getCommunityThread()) && parent!=null && java.util.Set.of(3,4).contains(parent.getStatus()))) requireVisible(parent);
            if (parent.getParentId() != null || !Objects.equals(parent.getTargetId(), comment.getTargetId())
                    || !Objects.equals(parent.getTargetType(), comment.getTargetType()))
                ArticleWriter.require(null);
        }
        if (mutation) {
            comment = comments.lockReplyTarget(commentId);
            requireVisible(comment);
        }
    }

    public Long articleId(long commentId) {
        var c = comments.articleCommentIncludingDeleted(commentId);
        return c != null && Integer.valueOf(TargetType.ARTICLE.getCode()).equals(c.getTargetType()) ? c.getTargetId() : null;
    }

    public void requireArticle(long commentId, boolean lock) {
        Comment c = ArticleWriter.require(comments.articleCommentIncludingDeleted(commentId));
        if (!Integer.valueOf(8).equals(c.getTargetType())) ArticleWriter.require(null);
        if (lock) {
            articles.lockInteraction(c.getTargetId(), false);
            if (c.getParentId() != null) requireVisible(comments.lockArticleComment(c.getParentId()));
            c = comments.lockArticleComment(commentId);
        } else {
            articles.assertPublic(c.getTargetId());
            if (c.getParentId() != null) requireVisible(comments.articleCommentIncludingDeleted(c.getParentId()));
        }
        requireVisible(c);
    }

    private void requireVisible(Comment c) {
        if (c == null || c.getDeletedAt() != null || !Integer.valueOf(1).equals(c.getStatus()))
            ArticleWriter.require(null);
    }

    public Comment requireReplyRoot(Long parentId, TargetType targetType, Long targetId) { return requireReplyRoot(parentId,targetType,targetId,false); }

    public Comment requireReplyRoot(Long parentId, TargetType targetType, Long targetId, boolean allowPlaceholder) {
        if (parentId == null)
            throw PostProblems.invalid("parentId", "REQUIRED", "Root comment is required");
        Comment parent = allowPlaceholder ? community.lock(parentId) : comments.lockReplyTarget(parentId);
        if (parent == null)
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        if (parent.getParentId() != null) {
            throw new BusinessException(ResultCode.COMMENT_REPLY_INVALID, "仅支持二级回复");
        }
        if (!Objects.equals(parent.getTargetType(), targetType.getCode())
                || !Objects.equals(parent.getTargetId(), targetId)) {
            throw new BusinessException(ResultCode.COMMENT_REPLY_INVALID, "父评论不属于该目标");
        }
        // TODO 当前仅允许回复审核通过的父评论。
        //      如果后续产品要求可回复短时间 PENDING 父评论，
        //      需要同时处理父评论被拒绝后子回复的隐藏和计数回滚问题。
        if (!Objects.equals(parent.getStatus(), CommentStatus.NORMAL.getCode()) && !(allowPlaceholder && java.util.Set.of(3,4).contains(parent.getStatus()))) {
            throw new BusinessException(ResultCode.COMMENT_REPLY_INVALID, "父评论不可回复");
        }
        return parent;
    }

    public boolean isQaPostComment(long id) {var c=community.get(id);return c!=null && c.getTargetType()==1 && posts.isQa(c.getTargetId());}
    public boolean isNormalPost(long id) { return posts.isNormal(id); }
    public void requireGrowthIfAny(TargetType type,long id){if(type==TargetType.POST && posts.isGrowth(id))posts.require(id,false);if(type==TargetType.COMMENT){var c=community.get(id);if(c!=null && c.getTargetType()==1 && posts.isGrowth(c.getTargetId()))posts.require(c.getTargetId(),false);}}
    public boolean isNormalPostComment(long id) { var c=community.get(id); return c!=null && c.getTargetType()==1 && posts.isNormal(c.getTargetId()); }
    public void lockForRemoval(long id) { var c=community.get(id); if(c==null)return; if(c.getTargetType()==1)posts.lockForTransition(c.getTargetId()); if(c.getParentId()!=null)community.lock(c.getParentId()); community.lock(id); }

    public static String namespace(Integer code) {
        var type = TargetType.fromCode(code);
        if (type != TargetType.POST && type != TargetType.ACTIVITY && type != TargetType.EXAM && type != TargetType.ARTICLE)
            throw new BusinessException(ResultCode.FORBIDDEN);
        return CurrentAuthorization.namespace(type);
    }
}
