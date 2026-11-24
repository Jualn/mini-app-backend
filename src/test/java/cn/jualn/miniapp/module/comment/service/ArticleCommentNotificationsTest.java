package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArticleCommentNotificationsTest {
    @Test void replyCarriesBothArticleTargetsWithoutAnEnablePropertyAndSkipsSelfNotification() {
        var notifications = mock(NotifyService.class);
        var service = new ArticleCommentNotifications(mock(UserService.class), notifications);
        service.reply(7, 8, 101, 202, 201, "title", "reply", "quote");
        var payload = ArgumentCaptor.forClass(NotifyPayload.class);
        verify(notifications).processNotificationPayload(payload.capture());
        assertEquals(TargetType.ARTICLE, payload.getValue().getTargetType());
        assertEquals(101L, payload.getValue().getTargetId());
        assertEquals(service.target(101, 202), payload.getValue().getSnapshot().target());
        assertEquals("ARTICLE_DETAIL", payload.getValue().getSnapshot().target().type());
        service.reply(7, 7, 101, 203, 201, "title", "reply", "quote");
        verifyNoMoreInteractions(notifications);
    }
}
