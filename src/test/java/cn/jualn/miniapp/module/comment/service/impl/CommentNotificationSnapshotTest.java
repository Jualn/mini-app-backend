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
    private CommentAuditCallback callback() {
        return new CommentAuditCallback(comments, mock(cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper.class),
                notifications, posts, mock(cn.jualn.miniapp.module.activity.mapper.ActivityMapper.class),
                mock(cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper.class), users,
                mock(cn.jualn.miniapp.module.activity.service.ActivityService.class),
                mock(cn.jualn.miniapp.module.exam.service.ExamService.class), mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class));
    }
    private Comment comment(Long parent) {
        var row = Comment.builder().id(19L).parentId(parent).userId(7L).targetType(TargetType.POST.getCode()).targetId(42L)
                .content("new response").build();
        when(comments.selectById(19L)).thenReturn(row);
        when(comments.update(any(Wrapper.class))).thenReturn(1);
        when(posts.selectOne(any(Wrapper.class))).thenReturn(Post.builder().id(42L).userId(8L).title("frozen post title").build());
        return row;
    }
    @Test void replyKeepsOriginalQuoteNewBodyAndPostTitleWithAvatar() {
        var row = comment(18L);
        var parent = Comment.builder().id(18L).userId(8L).content("original parent comment").build();
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
        assertNull(frozen.presentation().quote()); assertNotNull(frozen.actor()); assertNull(frozen.actor().avatarUrl());
    }
}
