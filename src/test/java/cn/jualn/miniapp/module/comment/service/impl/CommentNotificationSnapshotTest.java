package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommentNotificationSnapshotTest {
    @BeforeAll static void metadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Comment.class);
        TableInfoHelper.initTableInfo(assistant, Post.class);
    }
    private final CommentMapper comments = mock(CommentMapper.class);
    private final PostMapper posts = mock(PostMapper.class);
    private final UserService users = mock(UserService.class);
    private final NotifyService notifications = mock(NotifyService.class);
    private final cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper community = mock(cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper.class);
    private CommentAuditCallback callback() {
        return new CommentAuditCallback(comments, mock(cn.jualn.miniapp.module.audit.service.AuditLogService.class), notifications, users, new cn.jualn.miniapp.module.interact.service.InteractionSubjects(java.util.List.of(new cn.jualn.miniapp.module.post.service.PostInteractionSubject(posts,mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class)),new cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject(mock(cn.jualn.miniapp.module.activity.mapper.ActivityMapper.class),mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class)),new cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject(mock(cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper.class)))), mock(cn.jualn.miniapp.module.comment.service.CommentTargetAccess.class), community);
    }
    private Comment comment(Long parent) {
        var row = Comment.builder().id(19L).parentId(parent).userId(7L).targetType(TargetType.POST.getCode()).targetId(42L)
                .content("new response").status(0).auditStatus(0).createdAt(java.time.LocalDateTime.of(2026,11,19,10,20)).build();
        when(comments.selectById(19L)).thenReturn(row);
        when(comments.update(any(Wrapper.class))).thenReturn(1);
        when(posts.selectOne(any(Wrapper.class))).thenReturn(Post.builder().id(42L).userId(8L).title("frozen post title").content("frozen post body").build());
        return row;
    }
    @Test void replyKeepsOriginalQuoteNewBodyAndPostTitleWithAvatar() {
        var row = comment(18L);
        var parent = Comment.builder().id(18L).userId(8L).content("original parent comment").status(1).build();
        when(community.get(18L)).thenReturn(parent);
        when(comments.selectById(18L)).thenReturn(parent);
        var actor = UserSimpleBO.builder().id(7L).nickname("old nickname").avatarUrl("https://example.com/a.png").build();
        when(users.getSimpleInfo(7L)).thenReturn(actor);
        callback().activateCommentAfterAuditPass(19L);
        var captured = ArgumentCaptor.forClass(NotifyPayload.class);
        verify(notifications).processNotificationPayload(captured.capture());
        var frozen = captured.getValue().getSnapshot();
        parent.setContent("changed parent"); row.setContent("changed reply"); actor.setNickname("changed actor");
        assertEquals("new response", frozen.presentation().body());
        assertEquals("original parent comment", frozen.presentation().quote());
        assertEquals("original parent comment", frozen.presentation().context());
        assertEquals("frozen post title", frozen.presentation().subjectTitle());
        assertEquals("COMMENT", frozen.subject().type()); assertEquals("18", frozen.subject().resourceId());
        assertEquals("old nickname", frozen.actor().nickname());
        assertEquals("https://example.com/a.png", frozen.actor().avatarUrl());
        verify(users, times(1)).getSimpleInfo(7L);
    }
    @Test void topLevelCommentKeepsActorWithoutAvatarAndDoesNotQuoteNewComment() {
        comment(null);
        when(users.getSimpleInfo(7L)).thenReturn(UserSimpleBO.builder().id(7L).nickname("actor").avatarUrl("").build());
        callback().activateCommentAfterAuditPass(19L);
        var captured = ArgumentCaptor.forClass(NotifyPayload.class);
        verify(notifications).processNotificationPayload(captured.capture());
        var frozen = captured.getValue().getSnapshot();
        assertEquals("frozen post title", frozen.presentation().subjectTitle());
        assertEquals("frozen post title", frozen.presentation().context());
        assertEquals(cn.jualn.miniapp.module.notify.enums.NotifyType.POST_COMMENTED, captured.getValue().getType());
        var data = captured.getValue().getContentData().toMap();
        assertEquals("frozen post body", data.get("originalContent"));
        assertEquals("actor", data.get("replyUserName"));
        assertEquals(java.time.LocalDateTime.of(2026,11,19,10,20), data.get("replyTime"));
        assertNull(frozen.presentation().quote()); assertNotNull(frozen.actor()); assertNull(frozen.actor().avatarUrl());
    }

    @Test void commentingOnOwnPostDoesNotCreateNotification() {
        comment(null);
        when(posts.selectOne(any(Wrapper.class))).thenReturn(Post.builder().id(42L).userId(7L).title("own post").build());
        callback().activateCommentAfterAuditPass(19L);
        verifyNoInteractions(notifications);
    }

    @Test void repeatedApprovalWithoutStateTransitionDoesNotNotify() {
        comment(null);
        when(comments.update(any(Wrapper.class))).thenReturn(0);
        callback().activateCommentAfterAuditPass(19L);
        verifyNoInteractions(notifications);
    }
}
