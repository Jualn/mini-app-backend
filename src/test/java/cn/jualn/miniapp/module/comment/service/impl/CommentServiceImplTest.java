package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.audit.payload.AuditTextPayload;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.comment.bo.CommentPageBO;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.vo.CommentVO;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.post.service.PostService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private CommentConverter commentConverter;
    @Mock
    private PostService postService;
    @Mock
    private ActivityService activityService;
    @Mock
    private ExamService examService;
    @Mock
    private UserService userService;
    @Mock
    private TargetValidator targetValidator;
    @Mock
    private QueueProducer queueProducer;
    @Mock
    private InteractService interactService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createComment_shouldInsertIncreaseCountAndSendAudit() {
        UserContext.setUserId(7L);
        CommentServiceImpl service = new CommentServiceImpl(
                commentMapper, commentConverter, postService, activityService, examService, userService, targetValidator, queueProducer,interactService);

        CommentCreateBO command = new CommentCreateBO();
        command.setTargetType(TargetType.POST);
        command.setTargetId(100L);
        command.setContent("hello");
        doAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(501L);
            return 1;
        }).when(commentMapper).insert(any(Comment.class));

        Long commentId = service.createComment(command);

        assertEquals(501L, commentId);
        verify(targetValidator).assertExists(TargetType.POST, 100L);
        verify(commentMapper).insert(any(Comment.class));
        verify(postService).increaseCommentCount(100L);
        verify(queueProducer).send(any(AuditTextPayload.class));
        verify(commentMapper, never()).increaseReplyCount(anyLong());
    }

    @Test
    void pageComment_shouldReturnMappedListAndNextCursor() {
        CommentServiceImpl service = new CommentServiceImpl(
                commentMapper, commentConverter, postService, activityService, examService, userService, targetValidator, queueProducer,interactService);

        Comment first = Comment.builder().id(20L).userId(10L).targetType(TargetType.POST.getCode()).targetId(100L).status(CommentStatus.NORMAL.getCode()).build();
        Comment second = Comment.builder().id(18L).userId(11L).replyToUid(99L).targetType(TargetType.POST.getCode()).targetId(100L).status(CommentStatus.NORMAL.getCode()).build();
        when(commentMapper.selectList(any())).thenReturn(List.of(first, second));
        when(userService.batchGetSimple(Set.of(10L, 11L))).thenReturn(Map.of(
                10L, UserSimpleBO.builder().id(10L).nickname("u10").build(),
                11L, UserSimpleBO.builder().id(11L).nickname("u11").build()
        ));
        when(commentConverter.toVOList(any(), any())).thenReturn(List.of(
                CommentVO.builder().id(20L).build(),
                CommentVO.builder().id(18L).build()
        ));

        CommentPageBO query = new CommentPageBO();
        query.setTargetType(TargetType.POST);
        query.setTargetId(100L);
        query.setPageSize(2);
        query.setLastId(30L);

        var result = service.pageComment(query);

        assertEquals(18L, result.getNextCursor());
        verify(targetValidator).assertExists(TargetType.POST, 100L);
        verify(userService).batchGetSimple(Set.of(10L, 11L));
        verify(commentConverter).toVOList(any(), any());
    }

}

