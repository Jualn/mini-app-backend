package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ArticleCommentNotifications {
    private final UserService users;
    private final NotifyService notifications;

    public NotificationCenterBO.Target target(long articleId, long commentId) {
        return new NotificationCenterBO.Target("ARTICLE_DETAIL", null, Long.toString(commentId), null, null, Long.toString(articleId));
    }

    public void reply(long actorId, long recipient, long articleId, long newCommentId, long repliedCommentId, String articleTitle, String content, String quote) {
        if (actorId == recipient) return;
        var user = users.getSimpleInfo(actorId);
        var actor = user == null ? null : new NotificationCenterBO.Actor(Long.toString(actorId), user.getNickname(), user.getAvatarUrl());
        var snapshot = new NotificationCenterBO.Snapshot(new NotificationCenterBO.Presentation("有人回复了你", excerpt(content), excerpt(quote), null, null, articleTitle, excerpt(quote)),
                actor, new NotificationCenterBO.Subject("COMMENT", Long.toString(repliedCommentId)), target(articleId, newCommentId));
        notifications.processNotificationPayload(NotifyPayload.builder().receiverId(recipient).senderId(actorId).type(NotifyType.COMMENT_REPLIED)
                .title("有人回复了你").content(excerpt(content)).targetType(TargetType.ARTICLE).targetId(articleId).snapshot(snapshot).inAppOnly(true)
                .sourceKey("reply:" + newCommentId + ":receiver:" + recipient + ":approved").build());
    }

    private String excerpt(String text) {
        if (text == null) return "";
        return text.codePointCount(0, text.length()) <= 100 ? text : text.substring(0, text.offsetByCodePoints(0, 100));
    }
}
