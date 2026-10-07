package cn.jualn.miniapp.module.post.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.post.bo.PostCreateBO;
import cn.jualn.miniapp.module.post.bo.PostDetailBO;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.post.dto.request.PostPageQuery;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.common.enums.PostStatus;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.search.service.SearchService;
import cn.jualn.miniapp.module.post.vo.PostDetailVO;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    @BeforeAll
    static void initMybatisMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Post.class);
    }

    @Mock
    private PostMapper postMapper;
    @Mock
    private PostConverter postConverter;
    @Mock
    private MediaService mediaService;
    @Mock
    private UserService userService;
    @Mock
    private RedisService redisService;
    @Mock
    private InteractService interactService;
    @Mock
    private AuditReservationService auditReservationService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void pagePost_shouldDefaultToPublishedAndReturnCursor() {
        PostServiceImpl service = new PostServiceImpl(postMapper, postConverter, mediaService, userService, redisService, interactService, auditReservationService);
        Post first = Post.builder().id(20L).userId(7L).status(PostStatus.PUBLISHED.getCode()).auditStatus(AuditStatus.PASS.getCode()).build();
        Post second = Post.builder().id(10L).userId(8L).status(PostStatus.PUBLISHED.getCode()).auditStatus(AuditStatus.PASS.getCode()).build();
        when(postMapper.selectList(any())).thenReturn(List.of(first, second));
        when(userService.batchGetSimple(List.of(7L, 8L))).thenReturn(Map.of(
                7L, UserSimpleBO.builder().id(7L).nickname("u7").build(),
                8L, UserSimpleBO.builder().id(8L).nickname("u8").build()
        ));
        when(mediaService.batchListSimpleAttachments(eq(TargetType.POST), any())).thenReturn(Map.of(
                20L, List.of(MediaAttachmentSimpleBO.builder().id(1L).targetId(20L).build()),
                10L, List.of()
        ));
        when(interactService.batchIsLiked(eq(TargetType.POST), any())).thenReturn(Map.of(20L, false, 10L, false));
        when(interactService.batchGetLikeCount(eq(TargetType.POST), any())).thenReturn(Map.of(20L, 0, 10L, 0));
        when(postConverter.toListBOList(any(), any())).thenReturn(List.of(
                PostListBO.builder().id(20L).build(),
                PostListBO.builder().id(10L).build()
        ));

        PostPageQuery query = new PostPageQuery();
        query.setPageSize(1);
        query.setStatus(null);
        query.setLastId(50L);

        var result = service.pagePost(query);

        assertEquals(10L, result.getNextCursor());
        verify(postMapper).selectList(any());
    }

    @Test
    void getPostDetail_shouldLoadAndCacheWhenMiss() {
        PostServiceImpl service = new PostServiceImpl(postMapper, postConverter, mediaService, userService, redisService, interactService, auditReservationService);
        Post post = Post.builder().id(88L).userId(11L).status(PostStatus.PUBLISHED.getCode()).auditStatus(AuditStatus.PASS.getCode()).build();
        PostDetailBO detailBO = PostDetailBO.builder().id(88L).build();
        when(redisService.get(RedisKeyConstant.postDetail(88L), PostDetailBO.class)).thenReturn(null);
        when(postMapper.selectOne(any())).thenReturn(post);
        when(userService.getSimpleInfo(11L)).thenReturn(UserSimpleBO.builder().id(11L).nickname("author").build());
        when(mediaService.listSimpleAttachments(TargetType.POST, 88L)).thenReturn(List.of());
        when(postConverter.toDetailBO(post)).thenReturn(detailBO);
        when(postConverter.toDetailVO(detailBO)).thenReturn(PostDetailVO.builder().id(88L).build());
        when(interactService.isLiked(TargetType.POST, 88L)).thenReturn(false);

        service.getPostDetail(88L);

        verify(redisService).set(RedisKeyConstant.postDetail(88L), detailBO, RedisKeyConstant.POST_DETAIL_TTL);
    }

    @Test
    void removePost_shouldRequireOperatorOrAdminWhenNotOwner() {
        UserContext.setUserId(99L);
        PostServiceImpl service = new PostServiceImpl(postMapper, postConverter, mediaService, userService, redisService, interactService, auditReservationService);
        when(postMapper.selectUserIdById(88L)).thenReturn(11L);
        when(userService.getUserAuthInfo(99L)).thenReturn(UserAuthBO.builder().id(99L).role(UserRole.ADMIN).build());

        service.removePost(88L);

        verify(postMapper).update(any());
        verify(redisService).delete(RedisKeyConstant.postDetail(88L));
    }

    @Test
    void createPost_shouldReturnListBOAndEnqueueBatchAudit() {
        UserContext.setUserId(7L);
        PostServiceImpl service = new PostServiceImpl(postMapper, postConverter, mediaService, userService, redisService, interactService, auditReservationService);

        PostCreateBO command = PostCreateBO.builder()
                .title("t")
                .content("c")
                .attachmentItems(List.of(
                        AttachmentItemBO.builder().type(MediaType.IMAGE).url("https://img/1.png").sortOrder(0).build(),
                        AttachmentItemBO.builder().type(MediaType.IMAGE).url("https://img/2.png").sortOrder(1).build()
                ))
                .build();

        doAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            post.setId(100L);
            return 1;
        }).when(postMapper).insert(any(Post.class));
        when(userService.getSimpleInfo(7L)).thenReturn(UserSimpleBO.builder().id(7L).nickname("u7").build());
        when(auditReservationService.reserveAuditLogs(any())).thenReturn(
                AuditReserveResultBO.builder().textAuditLogId(9L).build());

        PostListBO created = service.createPost(command);

        assertEquals(100L, created.getId());
        verify(mediaService).replaceAttachments(any());
        verify(auditReservationService).reserveAuditLogs(any());
    }
}
