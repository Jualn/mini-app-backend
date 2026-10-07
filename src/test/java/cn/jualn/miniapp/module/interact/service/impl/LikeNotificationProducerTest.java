package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ShareRecordMapper;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.service.PostNotificationFactsService;
import cn.jualn.miniapp.module.comment.service.CommentNotificationFactsService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;

class LikeNotificationProducerTest {
    private final LikeRecordMapper likes = mock(LikeRecordMapper.class);
    private final PostNotificationFactsService posts = mock(PostNotificationFactsService.class);
    private final CommentNotificationFactsService comments = mock(CommentNotificationFactsService.class);
    private final UserService users = mock(UserService.class);
    private final NotifyService notifications = mock(NotifyService.class);
    private InteractServiceImpl service() {
        UserContext.setUserId(7L);
        return new InteractServiceImpl(likes, mock(ShareRecordMapper.class), mock(RedisService.class),
                mock(TargetValidator.class), mock(PostMapper.class), mock(ActivityMapper.class), mock(ExamInfoMapper.class),
                posts, comments, users, notifications);
    }
    @AfterEach void clear() { UserContext.clear(); }
    @Test void actualNewPostLikeFreezesActorAndUsesStableSourceWhileDuplicateInsertProducesNothing() {
        when(posts.published(42)).thenReturn(new PostNotificationFactsService.Fact(42, 8, "old post title", "old post content"));
        UserSimpleBO actor = UserSimpleBO.builder().id(7L).nickname("old nickname").avatarUrl("https://example.com/avatar.png").build();
        when(users.getSimpleInfo(7L)).thenReturn(actor);
        when(likes.insert(any(cn.jualn.miniapp.module.interact.entity.LikeRecord.class))).thenReturn(1).thenThrow(new DuplicateKeyException("synthetic duplicate"));
        var service = service();
        service.like(TargetType.POST, 42L); service.like(TargetType.POST, 42L);
        var payload = ArgumentCaptor.forClass(NotifyPayload.class);
        verify(notifications, times(1)).processNotificationPayload(payload.capture());
        actor.setNickname("changed later");
        assertEquals(NotifyType.POST_LIKED, payload.getValue().getType());
        assertEquals("old nickname", payload.getValue().getSnapshot().actor().nickname());
        assertEquals("old post title", payload.getValue().getSnapshot().presentation().context());
        assertEquals("old post title", payload.getValue().getSnapshot().presentation().subjectTitle());
        assertEquals("old post content", payload.getValue().getSnapshot().presentation().quote());
        assertEquals("42", payload.getValue().getSnapshot().target().postId());
        assertEquals("like:POST:42:actor:7:recipient:8", payload.getValue().getSourceKey());
    }
    @Test void selfActionAndNonContractActivityLikesDoNotInventNotifications() {
        when(likes.insert(any(cn.jualn.miniapp.module.interact.entity.LikeRecord.class))).thenReturn(1);
        when(posts.published(42)).thenReturn(new PostNotificationFactsService.Fact(42, 7, "self"));
        var service = service();
        service.like(TargetType.POST, 42L); service.like(TargetType.ACTIVITY, 43L);
        verifyNoInteractions(notifications);
    }
    @Test void nonPostCommentLikeRemainsReadableWithoutAnInventedTarget() {
        when(likes.insert(any(cn.jualn.miniapp.module.interact.entity.LikeRecord.class))).thenReturn(1);
        when(comments.published(19)).thenReturn(new CommentNotificationFactsService.Fact(19, 8, 2, 42, "comment preview"));
        var service = service(); service.like(TargetType.COMMENT, 19L);
        var payload = ArgumentCaptor.forClass(NotifyPayload.class);
        verify(notifications).processNotificationPayload(payload.capture());
        assertEquals(NotifyType.COMMENT_LIKED, payload.getValue().getType());
        assertNull(payload.getValue().getSnapshot().target());
        assertEquals("COMMENT", payload.getValue().getSnapshot().subject().type());
        assertEquals("comment preview", payload.getValue().getSnapshot().presentation().quote());
        assertNull(payload.getValue().getSnapshot().presentation().subjectTitle());
    }

    @Test void commentLikeSeparatesPostTitleAndOriginalQuoteAndKeepsAvatar() {
        when(likes.insert(any(cn.jualn.miniapp.module.interact.entity.LikeRecord.class))).thenReturn(1);
        when(comments.published(19)).thenReturn(new CommentNotificationFactsService.Fact(19, 8, 1, 42, "original comment"));
        when(posts.published(42)).thenReturn(new PostNotificationFactsService.Fact(42, 8, "post title"));
        when(users.getSimpleInfo(7L)).thenReturn(UserSimpleBO.builder().id(7L).nickname("actor").avatarUrl("https://example.com/a.png").build());
        service().like(TargetType.COMMENT, 19L);
        var payload = ArgumentCaptor.forClass(NotifyPayload.class);
        verify(notifications).processNotificationPayload(payload.capture());
        var frozen = payload.getValue().getSnapshot();
        assertEquals("post title", frozen.presentation().subjectTitle());
        assertEquals("original comment", frozen.presentation().quote());
        assertEquals("original comment", frozen.presentation().context());
        assertEquals("https://example.com/a.png", frozen.actor().avatarUrl());
    }
}
