package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.comment.service.ArticleCommentNotifications;
import cn.jualn.miniapp.module.comment.service.CommentNotificationFactsService;
import cn.jualn.miniapp.module.comment.service.CommentTargetAccess;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.post.service.PostNotificationFactsService;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;


/** Interact 内部点赞通知快照与稳定 sourceKey；仅由实际新增点赞触发。 */
@Service
@RequiredArgsConstructor
public class LikeNotifications {
    private final PostNotificationFactsService postNotificationFacts;
    private final CommentNotificationFactsService commentNotificationFacts;
    private final UserService userService;
    private final NotifyService notifyService;
    private final ArticleService articleService;
    private final CommentTargetAccess commentTargetAccess;
    private final ArticleCommentNotifications articleCommentNotifications;
    private final cn.jualn.miniapp.module.post.service.PostPublicAccess postAccess;

    public void notifyLike(Long actorId, TargetType targetType, Long targetId) {
        Long recipient = null;
        String context = null;
        NotificationCenterBO.Target target = null;
        String subjectTitle = null;
        String quote = null;
        NotifyType type;
        if (targetType == TargetType.POST) {
            var fact = postNotificationFacts.published(targetId);
            if (fact == null) {
                return;
            }
            if (java.util.Set.of("NORMAL","MENTOR_QA").contains(postAccess.require(targetId,false).postType())) return;
            recipient = fact.ownerId();
            context = fact.title();
            subjectTitle = fact.title();
            quote = fact.preview();
            type = NotifyType.POST_LIKED;
            target = new NotificationCenterBO.Target("POST_DETAIL", targetId.toString(), null, null, null);
        } else if (targetType == TargetType.COMMENT) {
            var fact = commentNotificationFacts.published(targetId);
            if (fact == null) {
                return;
            }
            if(fact.targetType()==TargetType.POST.getCode() && java.util.Set.of("NORMAL","MENTOR_QA").contains(postAccess.require(fact.targetId(),false).postType())) return;
            recipient = fact.ownerId();
            context = fact.preview();
            quote = fact.preview();
            type = NotifyType.COMMENT_LIKED;
            if (fact.targetType() == TargetType.POST.getCode()) {
                var post = postNotificationFacts.published(fact.targetId());
                subjectTitle = post == null ? null : post.title();
                target = new NotificationCenterBO.Target("POST_DETAIL", String.valueOf(fact.targetId()), targetId.toString(), null, null);
            }
            if (fact.targetType() == TargetType.ARTICLE.getCode()) {
                subjectTitle = articleService.publicTitle(fact.targetId());
                target = articleCommentNotifications.target(fact.targetId(), targetId);
            }
        } else {
            return;
        }
        if (recipient.equals(actorId)) {
            return;
        }
        var actor = userService.getSimpleInfo(actorId);
        var actorSnapshot = actor == null || actor.getNickname() == null || actor.getNickname().isBlank() ? null
                : new NotificationCenterBO.Actor(actorId.toString(), actor.getNickname(),
                actor.getAvatarUrl() == null || actor.getAvatarUrl().isBlank() ? null : actor.getAvatarUrl());
        String title = targetType == TargetType.POST ? "有人赞了你的帖子" : "有人赞了你的评论";
        var snapshot = new NotificationCenterBO.Snapshot(
                new NotificationCenterBO.Presentation(title, null, context, null, null, subjectTitle, quote),
                actorSnapshot, new NotificationCenterBO.Subject(targetType.name(), targetId.toString()), target);
        Long articleId = targetType == TargetType.COMMENT ? commentTargetAccess.articleId(targetId) : null;
        TargetType notificationTarget = articleId == null ? targetType : target == null ? null : TargetType.ARTICLE;
        Long notificationTargetId = articleId == null ? targetId : target == null ? null : articleId;
        // First positive fact per actor/subject; unlike/re-like does not promise another notification.
        notifyService.processNotificationPayload(cn.jualn.miniapp.module.notify.payload.NotifyPayload.builder()
                .receiverId(recipient).senderId(actorId).type(type).title(title).content(context == null ? "" : context)
                .targetType(notificationTarget).targetId(notificationTargetId).snapshot(snapshot)
                .inAppOnly(articleId != null)
                .sourceKey("like:" + targetType.name() + ":" + targetId + ":actor:" + actorId + ":recipient:" + recipient).build());
    }
}
