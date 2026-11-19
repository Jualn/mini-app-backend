package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.comment.bo.CommentPageBO;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.bo.CommentItemBO;
import cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.post.service.PostPublicAccess;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.media.TestMediaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll
    static void initMybatisMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Comment.class);
    }

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private CommentConverter commentConverter;
    @Mock
    private PostPublicAccess postService;
    @Mock
    private ActivityInteractionSubject activityService;
    @Mock
    private PublicEventInteractionSubject examService;
    @Mock
    private cn.jualn.miniapp.module.user.service.UserService userService;
    @Mock private UserAccountService userAccounts;
    @Mock
    private SubjectExistenceService targetValidator;
    @Mock
    private AuditReservationService auditReservationService;
    @Mock
    private InteractionLikeQueries interactService;
    @Mock
    private TestMediaService mediaService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createComment_shouldInsertIncreaseCountAndSendAudit() {
        UserContext.setUserId(7L);
        CommentServiceImpl service = new CommentServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), new cn.jualn.miniapp.module.comment.service.PostQaCommentPolicy(new cn.jualn.miniapp.module.post.service.PostCommentPermissions(new cn.jualn.miniapp.module.post.service.UnavailableMentorQualification())), commentMapper, postService, userAccounts, org.mockito.Mockito.mock(cn.jualn.miniapp.module.comment.service.CommentTargetAccess.class), auditReservationService, mediaService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.comment.service.CanonicalCommentService.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.comment.service.CommentCounters.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper.class));

        CommentCreateBO command = new CommentCreateBO();
        command.setTargetType(TargetType.POST);
        command.setTargetId(100L);
        command.setContent("hello");
        doAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(501L);
            return 1;
        }).when(commentMapper).insert(any(Comment.class));
        when(auditReservationService.reserveAuditLogs(any())).thenReturn(
                AuditReserveResultBO.builder().textAuditLogId(9L).build());

        Long commentId = service.createComment(command);

        assertEquals(501L, commentId);
        verify(postService).require(100L,true);
        verify(commentMapper).insert(any(Comment.class));
        verify(auditReservationService).reserveAuditLogs(any());
        verify(commentMapper, never()).increaseReplyCount(anyLong());
    }

    @Test
    void pageComment_shouldReturnMappedListAndNextCursor() {
        var service = new cn.jualn.miniapp.module.comment.service.CommentQueries(new cn.jualn.miniapp.module.comment.service.PostQaCommentPolicy(new cn.jualn.miniapp.module.post.service.PostCommentPermissions(new cn.jualn.miniapp.module.post.service.UnavailableMentorQualification())), commentMapper, commentConverter, postService, userService, org.mockito.Mockito.mock(cn.jualn.miniapp.module.comment.service.CommentTargetAccess.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.interact.service.InteractionCounts.class), interactService);

        Comment first = Comment.builder().id(20L).userId(10L).targetType(TargetType.POST.getCode()).targetId(100L).status(CommentStatus.NORMAL.getCode()).build();
        Comment second = Comment.builder().id(18L).userId(11L).replyToUid(99L).targetType(TargetType.POST.getCode()).targetId(100L).status(CommentStatus.NORMAL.getCode()).build();
        when(commentMapper.selectList(any())).thenReturn(List.of(first, second));
        when(userService.batchGetSimple(Set.of(10L, 11L))).thenReturn(Map.of(
                10L, UserSimpleBO.builder().id(10L).nickname("u10").build(),
                11L, UserSimpleBO.builder().id(11L).nickname("u11").build()
        ));
        when(commentConverter.toItemBOList(any(), any())).thenReturn(List.of(
                CommentItemBO.builder().id(20L).build(),
                CommentItemBO.builder().id(18L).build()
        ));

        CommentPageBO query = new CommentPageBO();
        query.setTargetType(TargetType.POST);
        query.setTargetId(100L);
        query.setPageSize(2);
        query.setLastId(30L);

        var result = service.pageComment(query);

        assertEquals(18L, result.getNextCursor());
        verify(postService).require(100L,false);
        verify(userService).batchGetSimple(Set.of(10L, 11L));
        verify(commentConverter).toItemBOList(any(), any());
    }

}

