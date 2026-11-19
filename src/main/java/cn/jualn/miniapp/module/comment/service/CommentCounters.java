package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.post.service.PostInteractionSubject;
import cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject;
import cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject;
import cn.jualn.miniapp.module.article.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal comment transition effects; parent and subject counters share the caller's transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class CommentCounters {
    private final CommentMapper comments;
    private final PostInteractionSubject posts;
    private final ActivityInteractionSubject activities;
    private final PublicEventInteractionSubject events;
    private final ArticleService articles;

    public void decrease(Integer type,Long targetId,Long parentId) {
        if (parentId!=null) comments.decreaseReplyCount(parentId);
        var target=TargetType.fromCode(type);
        if (target==null) return;
        switch(target) {
            case POST -> posts.commentRemoved(targetId);
            case ACTIVITY -> activities.commentRemoved(targetId);
            case EXAM -> events.commentRemoved(targetId);
            case ARTICLE -> articles.rebuildStatistics(targetId);
        }
    }
    public void increase(Integer type,Long targetId,Long parentId) {
        if (parentId!=null) comments.increaseReplyCount(parentId);
        var target=TargetType.fromCode(type);
        if (target==null) return;
        switch(target) {
            case POST -> posts.commentApproved(targetId);
            case ACTIVITY -> activities.commentApproved(targetId);
            case EXAM -> events.commentApproved(targetId);
            case ARTICLE -> articles.rebuildStatistics(targetId);
        }
    }
}
